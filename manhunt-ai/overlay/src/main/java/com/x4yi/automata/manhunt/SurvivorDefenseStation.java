package com.x4yi.automata.manhunt;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.items.CapabilityItemHandler;
import com.x4yi.automata.AutomataMod;

/** Service an administrator-designated, already configured turret base. Does not change owner, trust or targets. */
final class SurvivorDefenseStation {
    private final SurvivorPlayer bot;
    private BlockPos station,approach;
    private TileEntity tile;
    private ResourceLocation ammo;
    private EnumFacing side;
    private int nextCheck,expires;
    private String status="idle";
    SurvivorDefenseStation(SurvivorPlayer bot){this.bot=bot;}
    String status(){return status;}
    BlockPos destination(){return approach;}
    void clear(){station=null;approach=null;tile=null;status="idle";}
    boolean register(BlockPos p,ResourceLocation ammunition,EnumFacing input,int age){
        if(p.distanceSq(bot.homePosition())>144||!bot.world.isBlockLoaded(p)||!SurvivorPermissions.interact(bot,p))return false;
        var id=bot.world.getBlockState(p).getBlock().getRegistryName();
        if(id==null||!id.getNamespace().equals("openmodularturrets"))return false;
        TileEntity candidate=bot.world.getTileEntity(p);
        if(candidate==null||!candidate.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY,input)||!net.minecraftforge.fml.common.registry.ForgeRegistries.ITEMS.containsKey(ammunition))return false;
        station=p.toImmutable();tile=candidate;ammo=ammunition;side=input;expires=age+24000;nextCheck=age;status="registered";return true;
    }
    private int ammoSlot(){
        for(int i=0;i<36;i++){ItemStack s=bot.inventory.getStackInSlot(i);if(!s.isEmpty()&&ammo.equals(s.getItem().getRegistryName()))return i;}
        return -1;
    }
    void tick(int age){
        if(station==null||age<nextCheck)return;
        nextCheck=age+100;
        if(age>=expires||!bot.world.isBlockLoaded(station)||tile.isInvalid()||bot.world.getTileEntity(station)!=tile||!SurvivorPermissions.interact(bot,station)){clear();return;}
        try{
            var energy=tile.getCapability(CapabilityEnergy.ENERGY,side);
            if(energy==null)energy=tile.getCapability(CapabilityEnergy.ENERGY,null);
            String rf=energy==null?"RF unknown":"RF "+energy.getEnergyStored()+"/"+energy.getMaxEnergyStored();
            status=rf;
            int slot=ammoSlot();if(slot<0){approach=null;status+="; no carried ammo";return;}
            if(bot.getDistanceSqToCenter(station)>16){
                approach=null;double d=Double.MAX_VALUE;
                for(EnumFacing f:EnumFacing.HORIZONTALS){BlockPos p=station.offset(f);if(SurvivorTerrain.stand(bot.world,p)&&bot.getDistanceSqToCenter(p)<d){approach=p;d=bot.getDistanceSqToCenter(p);}}
                return;
            }
            var hit=bot.world.rayTraceBlocks(bot.getPositionEyes(1),new net.minecraft.util.math.Vec3d(station).add(.5,.5,.5),false,true,false);
            if(hit==null||!station.equals(hit.getBlockPos()))return;
            approach=null;
            var handler=tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY,side);
            if(handler==null){status+="; no ammo handler";return;}
            ItemStack source=bot.inventory.getStackInSlot(slot);
            // Never remove any contents or force invalid items into a slot. Use the designated sided input.
            int count=Math.min(8,source.getCount());
            for(int i=0;i<Math.min(64,handler.getSlots());i++){
                ItemStack old=handler.getStackInSlot(i);
                if(!old.isEmpty()&&(!ItemStack.areItemsEqual(old,source)||!ItemStack.areItemStackTagsEqual(old,source)||old.getCount()>=16))continue;
                ItemStack offer=source.copy();offer.setCount(count);
                ItemStack test=handler.insertItem(i,offer.copy(),true);
                if(test.getCount()>=count)continue;
                ItemStack remainder=handler.insertItem(i,offer.copy(),false);
                int inserted=Math.max(0,Math.min(count,count-remainder.getCount()));
                if(inserted>0){source.shrink(inserted);bot.inventory.markDirty();tile.markDirty();status+="; supplied "+inserted;return;}
            }
            status+="; no accepted ammo slot";
        }catch(RuntimeException|LinkageError ex){AutomataMod.LOGGER.warn("AI defense station disabled",ex);clear();}
    }
}
