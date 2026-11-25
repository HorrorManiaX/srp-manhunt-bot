/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Levosilimo
 * https://github.com/Levosilimo/Everlasting-Skins
 */

package levosilimo.everlastingskins.skinchanger;

import com.mojang.authlib.properties.Property;
import levosilimo.everlastingskins.Config;
import levosilimo.everlastingskins.EverlastingSkins;
import levosilimo.everlastingskins.metrics.SkinMetrics;
import levosilimo.everlastingskins.skinchanger.responses.mojang.MojangSkinDataResult;
import levosilimo.everlastingskins.util.CustomSkinProperty;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class SkinRestorer {

    private static volatile SkinStorage skinStorage;
    private static volatile SkinIO skinIO;
    private static volatile MinecraftServer server;

    @Nullable
    public static SkinStorage getSkinStorage() {
        return skinStorage;
    }

    public static MinecraftServer getServer() {
        return server;
    }

    /**
     * Test-only: replaces the static server reference without a full FML
     * lifecycle. Public because the test harness lives in a separate package.
     */
    public static void setServer(MinecraftServer server) {
        SkinRestorer.server = server;
    }

    public static void onServerStarting(FMLServerStartingEvent event) {
        server = event.getServer();
        Path dataDir = server.getFile("EverlastingSkins").toPath();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            EverlastingSkins.logger.error("Failed to create skin data directory", e);
        }
        skinIO = new SkinIO(dataDir);
        // Startup integrity sweep: quarantine bit-rotten records before the
        // first player logs in, so SkinStorage only ever sees verified files.
        skinIO.validateAllFiles();
        skinStorage = new SkinStorage(skinIO);
        Config.load(new File(server.getFile("config"), "everlastingskins.cfg"));
        SkinCommand.register(server);
    }

    /** Forge fires this after player NBT loading, before initial tab/spawn packets. */
    @SubscribeEvent
    public void onPlayerLoad(net.minecraftforge.event.entity.player.PlayerEvent.LoadFromFile event) {
        if (!(event.getEntityPlayer() instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.getEntityPlayer();
        if (skinStorage == null || server == null || player.mcServer != server || player.connection != null) return;
        LoginSkinLoader.applySavedBeforeJoin(player, skinStorage);
    }

    /**
     * Applies the player's saved skin on login.
     *
     * NOTE: PlayerLoggedInEvent fires after the player is already visible to
     * other players on the server. This means there is a brief flash of the
     * default/vanilla skin before the saved custom skin is applied.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        SkinMetrics.INSTANCE.recordPlayerJoined();

        LoginSkinLoader.onLogin(player, skinStorage);
    }

    /**
     * Saves the player's skin data on disconnect so it persists across sessions.
     */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        SkinMetrics.INSTANCE.recordPlayerLeft();
        if (skinStorage.getSkin(player.getUniqueID()) != null) {
            skinStorage.saveSkin(player.getUniqueID());
        }
    }

    /**
     * Saves all online players' skin data during graceful server shutdown.
     */
    public static void onServerStopping() {
        if (server == null) return;
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            skinStorage.saveSkin(player.getUniqueID());
        }
        skinStorage.flushPending();
    }
}
