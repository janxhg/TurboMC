/*
 * ==============================================================================
 * [ TURBOMC PROPRIETARY SOURCE CODE ]
 * This specific modification, logic, and implementation are property of TurboMC.
 * 
 * This code is NOT part of the PaperMC project and is strictly prohibited from 
 * being redistributed, merged, or used in any fork without the express written 
 * consent of the TurboMC Development Team.
 * 
 * (c) 2025-2026 TurboMC - Performance Reimagined.
 * ==============================================================================
 */
package io.papermc.paper.event.entity;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when an entity (non-player) moves.
 */
public class EntityMoveEvent extends EntityEvent implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private boolean cancelled;
    private final Location from;
    private final Location to;

    public EntityMoveEvent(@NotNull LivingEntity entity, @NotNull Location from, @NotNull Location to) {
        super(entity);
        this.from = from;
        this.to = to;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @NotNull
    public Location getFrom() {
        return from;
    }

    @NotNull
    public Location getTo() {
        return to;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return handlers;
    }

    @NotNull
    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }
}
