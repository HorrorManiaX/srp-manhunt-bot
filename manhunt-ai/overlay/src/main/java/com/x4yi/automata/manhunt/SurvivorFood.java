package com.x4yi.automata.manhunt;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;

/** Local food recovery; hostile defense always preempts it. No attacks on owned or enclosed livestock. */
final class SurvivorFood {
    private final SurvivorPlayer bot;
    private EntityAnimal quarry;
    private int nextScan,nextHit,expires;
    SurvivorFood(SurvivorPlayer bot){this.bot=bot;}
    void interrupt(){quarry=null;}
    BlockPos destination(){return quarry==null?null:quarry.getPosition();}
    private int provisions(){
        int n=0;
        for(int i=0;i<36;i++){
            ItemStack s=bot.inventory.getStackInSlot(i);
            if(!s.isEmpty()&&s.getItem() instanceof ItemFood&&bot.safeFood(s))n+=s.getCount();
        }
        return n;
    }
    private boolean allowed(EntityAnimal animal){
        if(animal==null||!animal.isEntityAlive()||animal.isChild()||animal.hasCustomName()||animal.isInLove()||animal.getLeashed()
            ||animal.world!=bot.world||animal.getPosition().distanceSq(bot.homePosition())>128*128
            ||!bot.world.isBlockLoaded(animal.getPosition())||!SurvivorPermissions.allowed(bot,animal.getPosition(),false))return false;
        if(!(animal instanceof EntityCow||animal instanceof EntityPig||animal instanceof EntityChicken||animal instanceof EntitySheep))return false;
        // A local fence/wall or nearby authenticated human is a reason to leave the animal alone.
        BlockPos center=animal.getPosition();
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            BlockPos p=center.add(x,0,z);
            if(!bot.world.isBlockLoaded(p))return false;
            var b=bot.world.getBlockState(p).getBlock();
            if(b instanceof net.minecraft.block.BlockFence||b instanceof net.minecraft.block.BlockFenceGate||b instanceof net.minecraft.block.BlockWall)return false;
        }
        for(var p:bot.getServer().getPlayerList().getPlayers())if(p.world==bot.world&&p.getDistanceSq(animal)<64)return false;
        return true;
    }
    void plan(int age,boolean stressed){
        if(stressed||!bot.autonomousEnabled()||provisions()>=3){interrupt();return;}
        if(quarry!=null&&(age>=expires||!quarry.isEntityAlive()||quarry.getPosition().distanceSq(bot.homePosition())>128*128)){interrupt();}
        if(quarry!=null||age<nextScan)return;
        nextScan=age+100;
        if(!bot.furnaceBusy()&&bot.hasFurnaceStation()){
            for(int i=0;i<36;i++){
                ItemStack s=bot.inventory.getStackInSlot(i);
                if(s.isEmpty())continue;
                var item=s.getItem();
                if(item==Items.BEEF||item==Items.PORKCHOP||item==Items.CHICKEN||item==Items.MUTTON||item==Items.RABBIT||item==Items.POTATO){
                    if(bot.smelt(item.getRegistryName()))return;
                }
            }
        }
        if(SurvivorCrafting.nearbyBench(bot)&&SurvivorCrafting.craftOutput(bot,Items.BREAD,true))return;
        if(bot.bestMelee()<0&&bot.getFoodStats().getFoodLevel()>=12)return;
        double best=144;int tested=0;
        for(EntityAnimal animal:bot.world.getEntitiesWithinAABB(EntityAnimal.class,bot.getEntityBoundingBox().grow(12))){
            if(++tested>12)break;
            double d=bot.getDistanceSq(animal);
            if(d<best&&bot.canEntityBeSeen(animal)&&allowed(animal)){quarry=animal;best=d;}
        }
        if(quarry!=null)expires=age+200;
    }
    boolean act(int age){
        if(quarry==null)return false;
        if(!quarry.isEntityAlive()){interrupt();return false;}
        if(bot.getDistanceSq(quarry)>6.25||!bot.canEntityBeSeen(quarry))return false;
        if(!allowed(quarry)){interrupt();return false;}
        int slot=bot.bestMelee();if(slot>=0)bot.equipInventorySlot(slot);else if(!bot.getHeldItemMainhand().isEmpty()){interrupt();return false;}bot.faceEntityForFood(quarry);
        bot.moveForward=0;bot.moveStrafing=0;
        if(age>=nextHit&&bot.getCooledAttackStrength(0)>=.9f){
            nextHit=age+20;bot.attackTargetEntityWithCurrentItem(quarry);bot.swingArm(EnumHand.MAIN_HAND);
        }
        return true;
    }
}
