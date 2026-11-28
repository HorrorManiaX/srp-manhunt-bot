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
    private static final class AmmoApi {
        static Class<?> magazine,bullet;
        static Field magType,bulletType,name,acceptedMagazines,acceptedBullets;
        static boolean available;
        static {
            try{
                magazine=Class.forName("com.modularwarfare.common.guns.ItemAmmo");
                bullet=Class.forName("com.modularwarfare.common.guns.ItemBullet");
                magType=magazine.getField("type");bulletType=bullet.getField("type");
                name=Class.forName("com.modularwarfare.common.type.BaseType").getField("internalName");
                Class<?> gt=Class.forName("com.modularwarfare.common.guns.GunType");
                acceptedMagazines=gt.getField("acceptedAmmo");acceptedBullets=gt.getField("acceptedBullets");
                available=true;
            }catch(ReflectiveOperationException|RuntimeException|LinkageError e){available=false;}
        }
    }
    private static final class Enhanced {
        static java.lang.reflect.Constructor<?> stop;
        static Method handle;
        static Field count, slot, gun, unload, prognosis;
        static java.util.Map<java.util.UUID,Object> tasks;
        static boolean available;
        @SuppressWarnings("unchecked") static void init(){
            if(available)return;
            try{
                Class<?> handler=Class.forName("com.modularwarfare.common.handler.ServerTickHandler");
                Class<?> task=Class.forName("com.modularwarfare.common.handler.data.DataGunReloadEnhancedTask");
                Class<?> packet=Class.forName("com.modularwarfare.common.network.PacketGunReloadEnhancedStop");
                tasks=(java.util.Map<java.util.UUID,Object>)handler.getField("reloadEnhancedTask").get(null);
                count=task.getField("reloadCount");slot=task.getField("gunSlot");gun=task.getField("reloadGun");
                unload=task.getField("isUnload");prognosis=task.getField("prognosisAmmo");
                stop=packet.getConstructor(int.class,boolean.class,boolean.class);
                handle=packet.getMethod("handleServerSide",net.minecraft.entity.player.EntityPlayerMP.class);
                available=true;
            }catch(ReflectiveOperationException|RuntimeException|LinkageError e){AutomataMod.LOGGER.warn("AI enhanced reload unavailable",e);}
        }
        static {init();}
    }
    private record Pending(Object task,int slot,ItemStack gun,int finish){}
    private static final java.util.Map<SurvivorPlayer,Pending> pending=new java.util.WeakHashMap<>();
    static void cancelReload(SurvivorPlayer bot){
        Pending p=pending.remove(bot);
        if(p!=null&&Enhanced.tasks!=null)Enhanced.tasks.remove(bot.getUniqueID(),p.task());
    }
    static boolean advanceReload(SurvivorPlayer bot,int age){
        Pending p=pending.get(bot);if(p==null)return false;
        if(!bot.isEntityAlive()||bot.getHeldItemMainhand()!=p.gun()||bot.inventory.currentItem!=p.slot()
            ||Enhanced.tasks.get(bot.getUniqueID())!=p.task()){cancelReload(bot);return false;}
        if(age<p.finish())return true;
        try{
            int count=Math.max(0,Enhanced.count.getInt(p.task()));
            if(count>0&&!Enhanced.unload.getBoolean(p.task())&&Enhanced.prognosis.get(p.task()) instanceof ItemStack stack&&!stack.isEmpty()){
                Enhanced.handle.invoke(Enhanced.stop.newInstance(count,true,true),bot);
                bot.inventory.markDirty();
            }
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){AutomataMod.LOGGER.warn("AI enhanced reload completion failed",e);}
        finally{cancelReload(bot);}
        return false;
    }
    static boolean isAmmo(ItemStack s){return AmmoApi.available&&!s.isEmpty()&&(AmmoApi.magazine.isInstance(s.getItem())||AmmoApi.bullet.isInstance(s.getItem()));}
    private static java.util.List<net.minecraft.item.Item> ammoItems;
    private static boolean accepts(String[] names,String name){
        if(names==null||name==null)return false;
        for(String n:names)if(name.equals(n))return true;
        return false;
    }
    private static boolean matchesAmmo(net.minecraft.item.Item item,Object gunType)throws ReflectiveOperationException{
        if(AmmoApi.magazine.isInstance(item))
            return accepts((String[])AmmoApi.acceptedMagazines.get(gunType),(String)AmmoApi.name.get(AmmoApi.magType.get(item)));
        if(AmmoApi.bullet.isInstance(item))
            return accepts((String[])AmmoApi.acceptedBullets.get(gunType),(String)AmmoApi.name.get(AmmoApi.bulletType.get(item)));
        return false;
    }
    static boolean craftAmmo(SurvivorPlayer bot){
        if(!Api.available||!AmmoApi.available)return false;
        try{
            ItemStack gun=ItemStack.EMPTY;
            for(int i=0;i<36;i++){ItemStack s=bot.inventory.getStackInSlot(i);if(isGun(s)){gun=s;break;}}
            if(gun.isEmpty())return false;
            Object type=Api.type.get(gun.getItem());
            if(ammoItems==null){
                ammoItems=new java.util.ArrayList<>();
                for(var item:net.minecraftforge.fml.common.registry.ForgeRegistries.ITEMS.getValuesCollection())
                    if(AmmoApi.magazine.isInstance(item)||AmmoApi.bullet.isInstance(item))ammoItems.add(item);
            }
            int magazines=0,bullets=0;
            for(int i=0;i<36;i++){
                ItemStack s=bot.inventory.getStackInSlot(i);
                if(!s.isEmpty()&&matchesAmmo(s.getItem(),type)){
                    if(AmmoApi.magazine.isInstance(s.getItem()))magazines+=s.getCount();else bullets+=s.getCount();
                }
            }
            int attempts=0;
            for(var item:ammoItems){
                if(!matchesAmmo(item,type))continue;
                if(AmmoApi.magazine.isInstance(item)?magazines>=2:bullets>=32)continue;
                for(var id:SurvivorCrafting.recipesFor(item)){
                    if(++attempts>8)return false;
                    if(SurvivorCrafting.craft(bot,id,true))return true;
                }
            }
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){AmmoApi.available=false;AutomataMod.LOGGER.warn("AI ammo adapter disabled",e);}
        return false;
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
    static int reload(SurvivorPlayer bot,int age){
        if(!Api.available || Api.reloadHandler==null)return 0;
        try{
            if(Boolean.TRUE.equals(Api.isReloading.invoke(null,bot)))return 0;
            for(int i=0;i<36;i++){
                ItemStack s=bot.inventory.getStackInSlot(i);
                if(s.isEmpty()||!Api.gun.isInstance(s.getItem())||Boolean.TRUE.equals(Api.hasShot.invoke(null,s)))continue;
                Object type=Api.type.get(s.getItem());
                boolean enhanced="ENHANCED".equals(String.valueOf(Api.animation.get(type)));
                if(enhanced&&!Enhanced.available)continue;
                bot.equipInventorySlot(i);
                Api.reloadHandler.invoke(Api.reloadPacket.newInstance(false),bot);
                int wait=Math.max(20,Api.reloadTime.getInt(type));
                if(enhanced){
                    Object task=Enhanced.tasks.get(bot.getUniqueID());
                    if(task==null)continue;
                    if(Enhanced.unload.getBoolean(task)){Enhanced.tasks.remove(bot.getUniqueID(),task);continue;}
                    int slot=Enhanced.slot.getInt(task);
                    if(slot!=bot.inventory.currentItem||Enhanced.gun.get(task)!=bot.getHeldItemMainhand()){Enhanced.tasks.remove(bot.getUniqueID(),task);continue;}
                    pending.put(bot,new Pending(task,slot,bot.getHeldItemMainhand(),age+wait));
                }
                return wait;
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
