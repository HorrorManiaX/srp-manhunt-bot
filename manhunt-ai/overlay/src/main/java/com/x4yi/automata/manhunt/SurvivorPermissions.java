package com.x4yi.automata.manhunt;
import java.lang.reflect.Method;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
/** If Clans is present but its policy cannot be checked, deny autonomous edits. */
final class SurvivorPermissions {
    private static final boolean clans=net.minecraftforge.fml.common.Loader.isModLoaded("clans");
    private static Method breakCheck,placeCheck,rightClickCheck;
    static {
        if(clans) try {
            Class<?> c=Class.forName("the_fireplace.clans.legacy.logic.LandProtectionLogic");
            breakCheck=c.getMethod("shouldCancelBlockBroken",World.class,BlockPos.class,EntityPlayerMP.class);
            placeCheck=c.getMethod("shouldCancelBlockPlacement",World.class,BlockPos.class,EntityPlayerMP.class,EntityEquipmentSlot.class);
            rightClickCheck=c.getMethod("shouldCancelRightClickBlock",World.class,BlockPos.class,EntityPlayerMP.class,net.minecraft.item.ItemStack.class,net.minecraft.util.EnumHand.class);
        } catch(ReflectiveOperationException | LinkageError e) {breakCheck=null;placeCheck=null;}
    }
    static boolean interact(SurvivorPlayer bot,BlockPos p){
        if(!allowed(bot,p,true))return false;
        if(!clans)return true;
        try{return rightClickCheck!=null&&Boolean.FALSE.equals(rightClickCheck.invoke(null,bot.world,p,bot,bot.getHeldItemMainhand(),net.minecraft.util.EnumHand.MAIN_HAND));}
        catch(ReflectiveOperationException|LinkageError|RuntimeException e){return false;}
    }
    static boolean allowed(SurvivorPlayer bot,BlockPos p,boolean placing) {
        if(!bot.world.isBlockLoaded(p)||!bot.world.getWorldBorder().contains(p)||bot.getServer().isBlockProtected((WorldServer)bot.world,p,bot)) return false;
        if(!clans)return true;
        try {
            if(placing)return placeCheck!=null && Boolean.FALSE.equals(placeCheck.invoke(null,bot.world,p,bot,EntityEquipmentSlot.MAINHAND));
            return breakCheck!=null && Boolean.FALSE.equals(breakCheck.invoke(null,bot.world,p,bot));
        } catch(ReflectiveOperationException | LinkageError | RuntimeException e) {return false;}
    }
}
