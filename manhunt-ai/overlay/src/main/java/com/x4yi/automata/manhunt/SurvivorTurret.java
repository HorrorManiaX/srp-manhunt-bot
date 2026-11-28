package com.x4yi.automata.manhunt;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.energy.CapabilityEnergy;
import com.x4yi.automata.AutomataMod;

/** One turret installed by this bot using normal placement. Never edits another owner's turret. */
final class SurvivorTurret {
    private static final ResourceLocation BASE=new ResourceLocation("openmodularturrets:turret_base");
    private static final ResourceLocation HEAD=new ResourceLocation("openmodularturrets:disposable_item_turret");
    private final SurvivorPlayer bot;
    private final SurvivorTechnology recipes;
    private static final ResourceLocation GENERATOR=new ResourceLocation("openmodularturrets:generator");
    private BlockPos base,approach,generator;
    private TileEntity generatorTile;
    private TileEntity owned;
    private int next;
    private boolean disabled;
    private String status="waiting";
    SurvivorTurret(SurvivorPlayer bot){this.bot=bot;recipes=new SurvivorTechnology(bot);}
    String status(){return status;}
    BlockPos destination(){return approach;}
    private void approachBase(){
        approach=null;double best=Double.MAX_VALUE;
        for(EnumFacing f:EnumFacing.HORIZONTALS){BlockPos p=base.offset(f);if(SurvivorTerrain.stand(bot.world,p)&&bot.getDistanceSqToCenter(p)<best){approach=p;best=bot.getDistanceSqToCenter(p);}}
    }
    private void supplyPower(){
        var target=owned.getCapability(CapabilityEnergy.ENERGY,EnumFacing.DOWN);
        if(target==null||!target.canReceive())return;
        if(target.getEnergyStored()>=target.getMaxEnergyStored()/2)return;
        for(int i=0;i<36;i++){
            ItemStack stack=bot.inventory.getStackInSlot(i);
            if(stack.isEmpty()||stack.getCount()!=1||stack.getItem().getRegistryName()==null||!stack.getItem().getRegistryName().getNamespace().equals("thermalexpansion"))continue;
            var battery=stack.getCapability(CapabilityEnergy.ENERGY,null);
            if(battery==null||!battery.canExtract())continue;
            int accepted=target.receiveEnergy(Math.min(4000,battery.extractEnergy(4000,true)),true);
            if(accepted<=0)continue;
            int extracted=battery.extractEnergy(accepted,false);
            if(extracted>0){target.receiveEnergy(extracted,false);bot.inventory.markDirty();owned.markDirty();}
            return;
        }
    }
    private boolean serviceGenerator() throws ReflectiveOperationException {
        if(generatorTile!=null&&(generatorTile.isInvalid()||!bot.world.isBlockLoaded(generator)||bot.world.getTileEntity(generator)!=generatorTile)){
            generatorTile=null;generator=null;
        }
        if(generatorTile==null){
            if(!has(GENERATOR,-1))return recipes.produceOnce(GENERATOR,-1);
            for(EnumFacing f:EnumFacing.HORIZONTALS){
                BlockPos p=base.offset(f);
                if(bot.world.isBlockLoaded(p)&&bot.world.isBlockLoaded(p.down())&&bot.world.isAirBlock(p)&&bot.world.getBlockState(p.down()).isFullCube()&&bot.requestBuild(p,GENERATOR))return true;
            }
            return false;
        }
        if(!SurvivorPermissions.interact(bot,generator)||bot.getDistanceSqToCenter(generator)>16)return false;
        var hit=bot.world.rayTraceBlocks(bot.getPositionEyes(1),new net.minecraft.util.math.Vec3d(generator).add(.5,.5,.5),false,true,false);
        if(hit==null||!generator.equals(hit.getBlockPos()))return false;
        IItemHandler fuel=(IItemHandler)generatorTile.getClass().getMethod("getFuel").invoke(generatorTile);
        for(int j=0;j<Math.min(16,fuel.getSlots());j++){
            if(fuel.getStackInSlot(j).getCount()>=8)continue;
            for(int i=0;i<36;i++){
                ItemStack s=bot.inventory.getStackInSlot(i);
                if(s.isEmpty()||s.getItem()!=net.minecraft.init.Items.COAL||s.getCount()<5)continue;
                ItemStack offer=s.copy();offer.setCount(Math.min(4,s.getCount()-4));
                int amount=offer.getCount();
                ItemStack rest=fuel.insertItem(j,offer,false);
                int moved=Math.max(0,Math.min(amount,amount-rest.getCount()));
                if(moved>0){s.shrink(moved);bot.inventory.markDirty();generatorTile.markDirty();status="generator fueled";return true;}
            }
        }
        return false;
    }
    private boolean has(ResourceLocation id,int meta){
        for(int i=0;i<36;i++){ItemStack s=bot.inventory.getStackInSlot(i);if(!s.isEmpty()&&id.equals(s.getItem().getRegistryName())&&(meta<0||s.getMetadata()==meta))return true;}
        return false;
    }
    private boolean valid(){
        return base!=null&&owned!=null&&!owned.isInvalid()&&bot.world.isBlockLoaded(base)&&bot.world.getTileEntity(base)==owned&&SurvivorPermissions.interact(bot,base);
    }
    void placed(BlockPos p){
        if(!bot.world.isBlockLoaded(p))return;
        if(GENERATOR.equals(bot.world.getBlockState(p).getBlock().getRegistryName())){
            TileEntity t=bot.world.getTileEntity(p);
            if(t!=null&&t.getClass().getName().equals("omtteam.openmodularturrets.tileentity.misc.GeneratorTileEntity")){
                generator=p.toImmutable();generatorTile=t;
            }
            return;
        }
        if(valid())return;
        if(!BASE.equals(bot.world.getBlockState(p).getBlock().getRegistryName()))return;
        TileEntity t=bot.world.getTileEntity(p);
        if(t==null||!t.getClass().getName().equals("omtteam.openmodularturrets.tileentity.base.TurretBase"))return;
        // Normal BlockTurretBase placement has assigned ownership already.
        try{
            t.getClass().getMethod("setAttacksPlayers",boolean.class).invoke(t,false);
            t.getClass().getMethod("setAttacksNeutrals",boolean.class).invoke(t,false);
            t.getClass().getMethod("setAttacksMobs",boolean.class).invoke(t,true);
            t.markDirty();base=p.toImmutable();owned=t;status="base installed";
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){disabled=true;AutomataMod.LOGGER.warn("AI OMT placement setup failed",e);}
    }
    boolean plan(int age){
        if(disabled||!net.minecraftforge.fml.common.Loader.isModLoaded("openmodularturrets")){approach=null;return false;}
        if(age<next)return false;
        next=age+160;
        if(!valid()){
            approach=null;
            // Do not claim an existing tile or one placed by another entity.
            if(base!=null&&bot.world.isBlockLoaded(base)&&bot.world.getTileEntity(base)!=owned){base=null;owned=null;}
            if(base!=null)return false;
            if(!has(BASE,0)){status="crafting tier 1 base";return recipes.produceOnce(BASE,0);}
            for(EnumFacing f:EnumFacing.HORIZONTALS){
                BlockPos p=bot.homePosition().offset(f,4);
                if(bot.world.isBlockLoaded(p)&&bot.world.isBlockLoaded(p.up())&&bot.world.isBlockLoaded(p.down())&&bot.world.isAirBlock(p)&&bot.world.isAirBlock(p.up())&&bot.world.getBlockState(p.down()).isFullCube()&&bot.requestBuild(p,BASE))return true;
            }
            status="no clear mounting site";return false;
        }
        BlockPos head=base.up();
        if(!bot.world.isBlockLoaded(head))return false;
        if(bot.world.isAirBlock(head)){
            if(!has(HEAD,-1)){status="crafting tier 1 head";return recipes.produceOnce(HEAD,-1);}
            return bot.requestBuild(head,HEAD);
        }
        if(!HEAD.equals(bot.world.getBlockState(head).getBlock().getRegistryName())){status="mount occupied";return false;}
        if(bot.getDistanceSqToCenter(base)>16){approachBase();return false;}
        approach=null;
        var hit=bot.world.rayTraceBlocks(bot.getPositionEyes(1),new net.minecraft.util.math.Vec3d(base).add(.5,.5,.5),false,true,false);
        if(hit==null||!base.equals(hit.getBlockPos()))return false;
        try{
            var clazz=owned.getClass();
            supplyPower();
            if(serviceGenerator())return true;
            // Keep the owned emplacement PvE-only; no automatic targeting of human or parasite players.
            clazz.getMethod("setAttacksPlayers",boolean.class).invoke(owned,false);
            clazz.getMethod("setAttacksNeutrals",boolean.class).invoke(owned,false);
            Object inv=clazz.getMethod("getInventory").invoke(owned);
            IItemHandler ammo=(IItemHandler)inv.getClass().getMethod("getAmmoSlots").invoke(inv);
            var damage=Class.forName("omtteam.openmodularturrets.config.DisposableAmmo").getMethod("getDamage",ItemStack.class);
            for(int i=0;i<36;i++){
                ItemStack s=bot.inventory.getStackInSlot(i);
                if(s.isEmpty()||s.getItem()!=net.minecraft.item.Item.getItemFromBlock(net.minecraft.init.Blocks.COBBLESTONE)||s.getCount()<=24||((Number)damage.invoke(null,s)).intValue()<=0)continue;
                for(int j=0;j<Math.min(9,ammo.getSlots());j++){
                    if(ammo.getStackInSlot(j).getCount()>=32)continue;
                    ItemStack offer=s.copy();offer.setCount(Math.min(8,s.getCount()-24));
                    int n=offer.getCount();
                    ItemStack rest=ammo.insertItem(j,offer,false);
                    int moved=Math.max(0,Math.min(n,n-rest.getCount()));
                    if(moved>0){s.shrink(moved);bot.inventory.markDirty();owned.markDirty();break;}
                }
                break;
            }
            int rf=((Number)clazz.getMethod("getEnergyStored",EnumFacing.class).invoke(owned,EnumFacing.DOWN)).intValue();
            status="installed; RF="+rf;
            return false;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){disabled=true;approach=null;status="adapter disabled";AutomataMod.LOGGER.warn("AI OMT service failed",e);return false;}
    }
}
