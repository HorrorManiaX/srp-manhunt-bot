package com.vagrantvision.client;

import com.srpcotesia.util.ParasiteInteractions;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Client-only parasite detector. RenderGameOverlayEvent.Pre(ALL) fires before
 * vanilla HUD elements, after the 3D world and first-person hand have rendered.
 */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = "vagrantvision")
public final class ParasiteVisionEvents {
    private static final ParasiteVisionRenderer RENDERER = new ParasiteVisionRenderer();

    private ParasiteVisionEvents() {}

    @SubscribeEvent
    public static void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) {
            return;
        }

        final boolean isParasite;
        try {
            isParasite = ParasiteInteractions.isParasite(mc.player);
        } catch (LinkageError | RuntimeException ex) {
            return;
        }

        if (isParasite) {
            RENDERER.render(mc);
        }
    }
}
