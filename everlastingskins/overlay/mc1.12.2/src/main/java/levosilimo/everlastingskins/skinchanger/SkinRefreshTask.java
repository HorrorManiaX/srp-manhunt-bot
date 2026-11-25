/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Levosilimo
 * https://github.com/Levosilimo/Everlasting-Skins
 */

package levosilimo.everlastingskins.skinchanger;

import com.mojang.authlib.GameProfile;
import levosilimo.everlastingskins.EverlastingSkins;
import levosilimo.everlastingskins.broadcast.SkinBroadcaster;
import levosilimo.everlastingskins.broadcast.VanillaSkinBroadcaster;
import levosilimo.everlastingskins.metrics.SkinMetrics;
import levosilimo.everlastingskins.util.CustomSkinProperty;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Server-tick half of a skin change: enqueues the async disk flush, mutates
 * the GameProfile, and forces every viewer to re-read it. The flush is
 * enqueued before the mutation so a failed enqueue leaves the applied
 * profile untouched (applied and on-disk state stay consistent). Runs on the
 * main thread via {@code addScheduledTask} so packet sends are thread-safe.
 */
final class SkinRefreshTask {

    /**
     * Seam for the per-viewer packet fan-out. Production uses
     * {@link VanillaSkinBroadcaster}; tests inject a recording fake to
     * assert call shape without re-implementing the REMOVE+ADD+cascade
     * pipeline. Static so the {@code task} entry point stays
     * signature-compatible with all existing callers.
     */
    private static volatile SkinBroadcaster broadcaster = new VanillaSkinBroadcaster();

    /** Test seam: replace the broadcaster. */
    static void setBroadcaster(SkinBroadcaster newBroadcaster) {
        broadcaster = newBroadcaster;
    }

    /** Current broadcaster (mainly for tests asserting the wiring). */
    static SkinBroadcaster getBroadcaster() {
        return broadcaster;
    }

    private SkinRefreshTask() {
    }

    static void task(EntityPlayerMP target, CustomSkinProperty property, long fetchNanos) {
        if (target == null) return;

        long startNanos = System.nanoTime();
        SkinMetrics.INSTANCE.recordRefreshStarted(target.getUniqueID());

        if (property == null || property.isEmpty()) {
            // /skin clear with no Mojang profile: storage is already null, so
            // drop the applied textures property; stale applied textures are
            // re-broadcast + cascaded, a textureless profile is a silent no-op.
            try {
                clearCascade(target);
            } catch (Throwable t) {
                EverlastingSkins.logger.error("Skin clear failed for {}", target.getName(), t);
                SkinMetrics.INSTANCE.recordRefreshFailed(target.getUniqueID());
            }
            return;
        }

        try {
            cascade(target, property, startNanos, fetchNanos);
            long durationNanos = System.nanoTime() - startNanos;
            SkinMetrics.INSTANCE.recordTaskDuration(durationNanos);
            if (durationNanos > 50_000_000L) {
                EverlastingSkins.logger.warn("SkinRefresh spike: {}ms for player {}",
                    durationNanos / 1_000_000, target.getName());
            }
        } catch (Throwable t) {
            // Fail soft: a partial cascade (profile mutated, observers stale) must
            // not abort the server tick or crash scheduled-task execution.
            EverlastingSkins.logger.error("Skin refresh failed for {}", target.getName(), t);
            SkinMetrics.INSTANCE.recordRefreshFailed(target.getUniqueID());
        }
    }

    private static void cascade(EntityPlayerMP target, CustomSkinProperty property, long startNanos,
            long fetchNanos) {
        // Atomicity invariant: the disk flush is enqueued BEFORE the
        // GameProfile is mutated. saveSkinAsync serializes the property
        // synchronously, so when the enqueue throws, the applied profile is
        // left untouched and in-memory and on-disk state still agree — a
        // mutated profile with no persisted skin would silently revert on the
        // next server restart. The in-memory map was already updated by the
        // caller; the flush itself runs off-tick.
        long saveStartNanos = System.nanoTime();
        SkinRestorer.getSkinStorage().saveSkinAsync(target.getUniqueID(), property);
        long saveNanos = System.nanoTime() - saveStartNanos;

        // GameProfile mutation is what every client ultimately reads textures from.
        mutateProfile(target, property);

        // Tab-list re-add to ALL online players (global, no dimension scoping).
        long broadcastNanos = broadcastProfileChange(target);

        // Never recreate the client's player for a cosmetic change.

        SkinMetrics.INSTANCE.recordRefreshCompleted(
            target.getUniqueID(), startNanos, fetchNanos, saveNanos, broadcastNanos);
    }

    static void loginRefresh(EntityPlayerMP target, CustomSkinProperty property) {
        if (property == null || property.isEmpty()) return;
        com.mojang.authlib.properties.Property desired = property.getOriginalProperty();
        for (com.mojang.authlib.properties.Property current : target.getGameProfile().getProperties().get("textures")) {
            if (java.util.Objects.equals(current.getValue(), desired.getValue())
                    && java.util.Objects.equals(current.getSignature(), desired.getSignature())) return;
        }
        try {
            mutateProfile(target, property);
            broadcastProfileChange(target);
        } catch (Exception e) {
            EverlastingSkins.logger.warn("Login skin refresh failed for {}", target.getName(), e);
        }
    }

    private static void mutateProfile(EntityPlayerMP target, CustomSkinProperty property) {
        target.getGameProfile().getProperties().removeAll("textures");
        target.getGameProfile().getProperties().put("textures", property.getOriginalProperty());
    }

    private static void clearAppliedProfile(EntityPlayerMP target) {
        target.getGameProfile().getProperties().removeAll("textures");
    }

    /**
     * Clear half of the cascade: drop the applied textures property and, only
     * when stale textures actually existed, re-broadcast REMOVE+ADD so
     * observers re-learn the profile without respawning the target.
     * Existing rendered entities may require a normal reconnect. A textureless profile has
     * nothing to revert or re-learn: the clear is a silent no-op with no
     * broadcast, no cascade and no failure metric. Storage was already cleared
     * by the caller, so nothing is persisted here.
     */
    private static void clearCascade(EntityPlayerMP target) {
        boolean hadAppliedTextures = !target.getGameProfile().getProperties().get("textures").isEmpty();
        clearAppliedProfile(target);
        if (!hadAppliedTextures) {
            return;
        }
        broadcastProfileChange(target);
    }

    /**
     * Tab-list REMOVE+ADD to ALL online players (global, no dimension scoping);
     * returns the measured broadcast nanos. Per-viewer packet fan-out is
     * delegated to the injected {@link SkinBroadcaster}; the call shape is
     * broadcaster-agnostic so config-flag tests can swap in a recording
     * fake without rebuilding the REMOVE+ADD+cascade pipeline.
     */
    private static long broadcastProfileChange(EntityPlayerMP target) {
        long broadcastStartNanos = System.nanoTime();
        broadcaster.broadcastProfileChange(target.getGameProfile(), target);
        return System.nanoTime() - broadcastStartNanos;
    }

}
