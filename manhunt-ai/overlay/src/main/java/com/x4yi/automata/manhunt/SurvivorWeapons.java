package com.x4yi.automata.manhunt;
import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.compat.ManhuntCompat;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
/** Optional adapter to Modular Warfare. Never synthesizes client hits or damage. */
final class SurvivorWeapons {
    private static final class Api {
        static Class<?> gun;
        static Method hasShot, mode, build, fire;
        static Field type, delay, range;
        static boolean available;
        static java.lang.reflect.Constructor<?> reloadPacket;
        static Method reloadHandler,isReloading;
        static Field animation,reloadTime;
        static {
            try {
                gun = Class.forName("com.modularwarfare.common.guns.ItemGun");
                Class<?> gt = Class.forName("com.modularwarfare.common.guns.GunType");
                Class<?> fm = Class.forName("com.modularwarfare.common.guns.WeaponFireMode");
                Class<?> data = Class.forName("com.modularwarfare.common.guns.manager.FireManager$FireData");
                Class<?> manager = Class.forName("com.modularwarfare.common.guns.manager.FireManager");
                hasShot = gun.getMethod("hasNextShot", ItemStack.class);
                mode = gt.getMethod("getFireMode", ItemStack.class);
                type = gun.getField("type"); delay = gt.getField("fireTickDelay"); range = gt.getField("weaponMaxRange");
                build = data.getMethod("buildServer", float.class, float.class, World.class, ItemStack.class, gun, fm, boolean.class, Float.class, Float.class);
                fire = manager.getMethod("fire", EntityLivingBase.class, data);
                available = true;
                try{
                    Class<?> packet=Class.forName("com.modularwarfare.common.network.PacketGunReload");
                    reloadPacket=packet.getConstructor(boolean.class);
                    reloadHandler=packet.getMethod("handleServerSide",net.minecraft.entity.player.EntityPlayerMP.class);
                    isReloading=gun.getMethod("isServerReloading",EntityPlayer.class);
                    animation=gt.getField("animationType");reloadTime=gt.getField("reloadTime");
                }catch(ReflectiveOperationException | LinkageError ex){reloadHandler=null;}
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
                AutomataMod.LOGGER.warn("Manhunt AI: Modular Warfare integration unavailable: " + ex);
            }
        }
    }
    static boolean isGun(ItemStack s){return Api.available&&!s.isEmpty()&&Api.gun.isInstance(s.getItem());}
    static boolean isLoaded(ItemStack s){
        try{return isGun(s)&&Boolean.TRUE.equals(Api.hasShot.invoke(null,s));}
        catch(ReflectiveOperationException|RuntimeException|LinkageError e){return false;}
    }
    static int loadedGun(SurvivorPlayer bot) {
        if (!Api.available) return -1;
        try {
            for (int i = 0; i < 36; i++) {
                ItemStack s = bot.inventory.getStackInSlot(i);
                if (!s.isEmpty() && Api.gun.isInstance(s.getItem()) && Boolean.TRUE.equals(Api.hasShot.invoke(null, s))) return i;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) { disable(ex); }
        return -1;
    }
    static int reload(SurvivorPlayer bot){
        if(!Api.available || Api.reloadHandler==null)return 0;
        try{
            if(Boolean.TRUE.equals(Api.isReloading.invoke(null,bot)))return 0;
            for(int i=0;i<36;i++){
                ItemStack s=bot.inventory.getStackInSlot(i);
                if(s.isEmpty()||!Api.gun.isInstance(s.getItem())||Boolean.TRUE.equals(Api.hasShot.invoke(null,s)))continue;
                Object type=Api.type.get(s.getItem());
                // Enhanced reload needs a real animation-task handshake. Never fake completion.
                if("ENHANCED".equals(String.valueOf(Api.animation.get(type))))continue;
                bot.equipInventorySlot(i);
                Api.reloadHandler.invoke(Api.reloadPacket.newInstance(false),bot);
                return Math.max(20,Api.reloadTime.getInt(type));
            }
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){Api.reloadHandler=null;AutomataMod.LOGGER.warn("Manhunt AI basic reload disabled",ex);}
        return 0;
    }
    static int fire(SurvivorPlayer bot, EntityLivingBase target) {
        if (!Api.available || !ManhuntCompat.isHostile(target) || !bot.canEntityBeSeen(target)) return 20;
        try {
            ItemStack s = bot.getHeldItemMainhand();
            if (!Api.gun.isInstance(s.getItem()) || !Boolean.TRUE.equals(Api.hasShot.invoke(null, s)) || (Api.isReloading!=null&&Boolean.TRUE.equals(Api.isReloading.invoke(null,bot)))) return 20;
            Object t = Api.type.get(s.getItem());
            if (bot.getDistanceSq(target) > Math.pow(Math.min(24, ((Number)Api.range.get(t)).doubleValue()), 2)) return 10;
            Vec3d start = bot.getPositionEyes(1F), end = start.add(bot.getLookVec().scale(Math.min(24, ((Number)Api.range.get(t)).doubleValue())));
            if (target.getEntityBoundingBox().grow(.15).calculateIntercept(start, end) == null) return 3;
            for (EntityPlayer ally : bot.world.getEntitiesWithinAABB(EntityPlayer.class, bot.getEntityBoundingBox().grow(25))) {
                if (ally != bot && !ManhuntCompat.isHostile(ally) && ally.getEntityBoundingBox().grow(.8).calculateIntercept(start, end) != null) return 10;
            }
            Object m = Api.mode.invoke(null, s);
            if (m == null) return 20;
            Object data = Api.build.invoke(null, bot.rotationPitch, bot.rotationYaw, bot.world, s, s.getItem(), m, true, null, null);
            Api.fire.invoke(null, bot, data);
            return Math.max(2, Api.delay.getInt(t)) + bot.getRNG().nextInt(2);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) { disable(ex); return 100; }
    }
    private static void disable(Throwable ex) {
        Api.available = false;
        AutomataMod.LOGGER.warn("Manhunt AI: disabling experimental gun adapter", ex);
    }
}
