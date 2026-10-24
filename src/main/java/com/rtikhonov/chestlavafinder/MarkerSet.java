package com.rtikhonov.chestlavafinder;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import java.util.Iterator;

/** Набор маркеров: позиция блока -> светящаяся сущность. */
public class MarkerSet {
    private static int nextId = -1_000_000; // отрицательные id не пересекаются с серверными

    private final Long2ObjectOpenHashMap<MarkerEntity> markers = new Long2ObjectOpenHashMap<>();

    /** Привести маркеры к нужному набору позиций (pos -> цвет). */
    public void sync(ClientWorld world, Long2IntMap desired) {
        Iterator<Long2ObjectMap.Entry<MarkerEntity>> it = markers.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<MarkerEntity> e = it.next();
            MarkerEntity m = e.getValue();
            long pos = e.getLongKey();
            if (!desired.containsKey(pos) || m.isRemoved() || desired.get(pos) != m.color) {
                remove(world, m);
                it.remove();
            }
        }
        for (Long2IntMap.Entry e : desired.long2IntEntrySet()) {
            long pos = e.getLongKey();
            if (markers.containsKey(pos)) continue;
            MarkerEntity m = new MarkerEntity(world, e.getIntValue());
            m.setId(nextId--);
            m.setPosition(BlockPos.unpackLongX(pos) + 0.5, BlockPos.unpackLongY(pos), BlockPos.unpackLongZ(pos) + 0.5);
            world.addEntity(m);
            markers.put(pos, m);
        }
    }

    /** Убрать все маркеры из мира. */
    public void clear(ClientWorld world) {
        if (markers.isEmpty()) return;
        for (MarkerEntity m : markers.values()) remove(world, m);
        markers.clear();
    }

    /** Мир сменился/выгружен — просто забыть ссылки. */
    public void forget() {
        markers.clear();
    }

    public int size() {
        return markers.size();
    }

    private static void remove(ClientWorld world, MarkerEntity m) {
        if (!m.isRemoved() && world != null) {
            world.removeEntity(m.getId(), Entity.RemovalReason.DISCARDED);
        }
    }
}
