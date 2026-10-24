package com.rtikhonov.chestlavafinder;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.world.World;

/**
 * Невидимый клиентский шалкер со свечением (эффект как у "Свечения").
 * Его контур рисуется ванильным шейдером обводки и виден сквозь любые блоки,
 * а сами блоки мира при этом никак не меняются (это не X-ray).
 * Сущность существует только у тебя на клиенте — сервер о ней не знает.
 */
public class MarkerEntity extends ShulkerEntity {
    final int color;

    public MarkerEntity(World world, int color) {
        super(EntityType.SHULKER, world);
        this.color = color;
        this.setInvisible(true);
        this.setNoGravity(true);
        this.setSilent(true);
    }

    // Никакой логики: маркер просто стоит на месте.
    @Override
    public void tick() {
    }

    @Override
    public boolean isGlowing() {
        return true;
    }

    @Override
    public boolean isInvisible() {
        return true;
    }

    // Цвет обводки.
    @Override
    public int getTeamColorValue() {
        return color;
    }

    // Нельзя навестись/ударить — клики проходят к сундуку.
    @Override
    public boolean canHit() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    // Без @Override специально: сигнатура менялась между версиями,
    // так код скомпилируется в любом случае.
    public boolean isCollidable() {
        return false;
    }

    public boolean isCollidable(Entity other) {
        return false;
    }

    // Рисовать на любом расстоянии (в пределах прогрузки).
    @Override
    public boolean shouldRender(double distance) {
        return true;
    }
}
