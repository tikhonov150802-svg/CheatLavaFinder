package com.rtikhonov.chestlavafinder;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

public class ChestLavaFinderClient implements ClientModInitializer {
    // Цвета обводки (RGB)
    private static final int COLOR_CHEST = 0xFFAA00;   // обычный сундук — оранжевый
    private static final int COLOR_TRAPPED = 0xFF3030; // сундук-ловушка — красный
    private static final int COLOR_ENDER = 0xB040FF;   // эндер-сундук — фиолетовый
    private static final int COLOR_BARREL = 0x40C0FF;  // бочка — голубая
    private static final int COLOR_LAVA = 0xFF5500;    // лава — ярко-оранжевый

    private static KeyBinding chestKey;
    private static KeyBinding lavaKey;

    private boolean chestsOn = true;
    private boolean lavaOn = true;

    private final MarkerSet chestMarkers = new MarkerSet();
    private final MarkerSet lavaMarkers = new MarkerSet();
    private final LavaScanner lavaScanner = new LavaScanner();
    private ClientWorld lastWorld;
    private int ticks;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("chestlavafinder", "main"));
        chestKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestlavafinder.chests", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
        lavaKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestlavafinder.lava", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, category));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) {
            chestMarkers.forget();
            lavaMarkers.forget();
            lavaScanner.reset();
            lastWorld = null;
            return;
        }
        if (world != lastWorld) { // вход в мир / смена измерения
            chestMarkers.forget();
            lavaMarkers.forget();
            lavaScanner.reset();
            lastWorld = world;
        }

        while (chestKey.wasPressed()) {
            chestsOn = !chestsOn;
            if (!chestsOn) chestMarkers.clear(world);
            client.player.sendMessage(Text.literal("Подсветка сундуков: " + (chestsOn ? "§aВКЛ" : "§cВЫКЛ")), true);
        }
        while (lavaKey.wasPressed()) {
            lavaOn = !lavaOn;
            if (!lavaOn) {
                lavaMarkers.clear(world);
                lavaScanner.reset();
            }
            client.player.sendMessage(Text.literal("Подсветка лавовых озёр: " + (lavaOn ? "§aВКЛ" : "§cВЫКЛ")), true);
        }

        ticks++;

        // Сундуки — раз в полсекунды
        if (chestsOn && ticks % 10 == 0) {
            chestMarkers.sync(world, findChests(client, world));
        }

        // Лава — только в верхнем мире
        if (lavaOn && world.getRegistryKey() == World.OVERWORLD) {
            lavaScanner.tick(client, world, COLOR_LAVA);
            LavaScanner.Result r = lavaScanner.poll();
            if (r != null) lavaMarkers.sync(world, r.markers());
        } else if (lavaMarkers.size() > 0) {
            lavaMarkers.clear(world);
            lavaScanner.reset();
        }
    }

    private static Long2IntOpenHashMap findChests(MinecraftClient client, ClientWorld world) {
        Long2IntOpenHashMap out = new Long2IntOpenHashMap();
        int radius = client.options.getViewDistance().getValue() + 1;
        int pcx = client.player.getBlockX() >> 4;
        int pcz = client.player.getBlockZ() >> 4;
        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                WorldChunk chunk = world.getChunk(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    int color;
                    if (be instanceof TrappedChestBlockEntity) color = COLOR_TRAPPED;
                    else if (be instanceof ChestBlockEntity) color = COLOR_CHEST;
                    else if (be instanceof EnderChestBlockEntity) color = COLOR_ENDER;
                    else if (be instanceof BarrelBlockEntity) color = COLOR_BARREL;
                    else continue;
                    out.put(be.getPos().asLong(), color);
                }
            }
        }
        return out;
    }
}
