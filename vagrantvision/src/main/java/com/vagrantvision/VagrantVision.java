package com.vagrantvision;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.relauncher.Side;

@Mod(
    modid = VagrantVision.MODID,
    name = VagrantVision.NAME,
    version = VagrantVision.VERSION,
    dependencies = "required-after:srpcotesia"
)
@Mod.EventBusSubscriber(Side.CLIENT)
public final class VagrantVision {
    public static final String MODID = "vagrantvision";
    public static final String NAME = "VagrantVision";
    public static final String VERSION = "1.0.0";

    private VagrantVision() {}
}
