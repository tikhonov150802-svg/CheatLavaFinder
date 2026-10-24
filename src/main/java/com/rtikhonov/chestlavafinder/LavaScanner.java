package com.rtikhonov.chestlavafinder;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Постепенно сканирует прогруженные чанки (несколько штук за тик, чтобы не лагало),
 * собирает блоки-источники лавы, затем в фоновом потоке делит их на связные озёра
 * и отбирает озёра, где источников больше MIN_SOURCES.
 */
public class LavaScanner {
    public static final int MIN_SOURCES = 10;       // озеро считается, если источников > 10
    private static final int CHUNKS_PER_TICK = 6;
    private static final int MAX_MARKERS_PER_LAKE = 400;
    private static final int MAX_MARKERS_TOTAL = 3000;

    private static final Predicate<BlockState> IS_LAVA_SOURCE =
            s -> s.getFluidState().getFluid() == Fluids.LAVA; // Fluids.LAVA = только источник

    private long[] chunkQueue = new long[0];
    private int index = 0;
    private LongArrayList found = new LongArrayList();
    private CompletableFuture<Result> pending;

    public record Result(Long2IntOpenHashMap markers, int lakes) {}

    public void reset() {
        chunkQueue = new long[0];
        index = 0;
        found = new LongArrayList();
        pending = null;
    }

    public void tick(MinecraftClient client, ClientWorld world, int color) {
        if (index >= chunkQueue.length) {
            if (pending != null) return; // ждём фоновый подсчёт
            if (chunkQueue.length > 0) {
                long[] all = found.toLongArray();
                pending = CompletableFuture.supplyAsync(() -> computeLakes(all, color));
            }
            startPass(client);
            return;
        }
        int end = Math.min(chunkQueue.length, index + CHUNKS_PER_TICK);
        for (; index < end; index++) {
            long cp = chunkQueue[index];
            scanChunk(world, (int) cp, (int) (cp >> 32));
        }
    }

    /** Готовый результат прохода или null. */
    public Result poll() {
        if (pending != null && pending.isDone()) {
            Result r = pending.getNow(null);
            pending = null;
            return r;
        }
        return null;
    }

    private void startPass(MinecraftClient client) {
        int radius = client.options.getViewDistance().getValue() + 1;
        int pcx = client.player.getBlockX() >> 4;
        int pcz = client.player.getBlockZ() >> 4;
        List<int[]> list = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                list.add(new int[]{pcx + dx, pcz + dz, dx * dx + dz * dz});
        list.sort(Comparator.comparingInt(a -> a[2]));
        chunkQueue = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            int[] a = list.get(i);
            chunkQueue[i] = (a[0] & 0xFFFFFFFFL) | ((long) a[1] << 32);
        }
        index = 0;
        found = new LongArrayList();
    }

    private void scanChunk(ClientWorld world, int cx, int cz) {
        if (!world.isChunkLoaded(cx, cz)) return;
        WorldChunk chunk = world.getChunk(cx, cz);
        if (chunk == null) return;
        ChunkSection[] sections = chunk.getSectionArray();
        int bx = cx << 4, bz = cz << 4;
        for (int i = 0; i < sections.length; i++) {
            ChunkSection s = sections[i];
            if (s == null || !s.hasAny(IS_LAVA_SOURCE)) continue; // быстрая проверка палитры
            int by = chunk.sectionIndexToCoord(i) << 4;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (IS_LAVA_SOURCE.test(s.getBlockState(x, y, z)))
                            found.add(BlockPos.asLong(bx + x, by + y, bz + z));
        }
    }

    /** Фоновый поток: связные компоненты (6 соседей), фильтр > MIN_SOURCES, верхний слой. */
    private static Result computeLakes(long[] all, int color) {
        LongOpenHashSet set = new LongOpenHashSet(all);
        LongOpenHashSet visited = new LongOpenHashSet();
        Long2IntOpenHashMap out = new Long2IntOpenHashMap();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongArrayList lake = new LongArrayList();
        int lakes = 0;

        for (long start : all) {
            if (out.size() >= MAX_MARKERS_TOTAL) break;
            if (!visited.add(start)) continue;
            lake.clear();
            queue.enqueue(start);
            while (!queue.isEmpty()) {
                long p = queue.dequeueLong();
                lake.add(p);
                int x = BlockPos.unpackLongX(p), y = BlockPos.unpackLongY(p), z = BlockPos.unpackLongZ(p);
                visit(set, visited, queue, BlockPos.asLong(x + 1, y, z));
                visit(set, visited, queue, BlockPos.asLong(x - 1, y, z));
                visit(set, visited, queue, BlockPos.asLong(x, y + 1, z));
                visit(set, visited, queue, BlockPos.asLong(x, y - 1, z));
                visit(set, visited, queue, BlockPos.asLong(x, y, z + 1));
                visit(set, visited, queue, BlockPos.asLong(x, y, z - 1));
            }
            if (lake.size() <= MIN_SOURCES) continue;
            lakes++;
            int added = 0;
            for (int i = 0; i < lake.size() && added < MAX_MARKERS_PER_LAKE && out.size() < MAX_MARKERS_TOTAL; i++) {
                long p = lake.getLong(i);
                long above = BlockPos.asLong(BlockPos.unpackLongX(p), BlockPos.unpackLongY(p) + 1, BlockPos.unpackLongZ(p));
                if (!set.contains(above)) { // подсвечиваем только поверхность озера
                    out.put(p, color);
                    added++;
                }
            }
        }
        return new Result(out, lakes);
    }

    private static void visit(LongOpenHashSet set, LongOpenHashSet visited, LongArrayFIFOQueue queue, long p) {
        if (set.contains(p) && visited.add(p)) queue.enqueue(p);
    }
}
