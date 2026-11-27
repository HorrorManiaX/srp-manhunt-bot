package com.x4yi.automata.manhunt;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.AxisAlignedBB;

/** Opt-in lava placement into a prebuilt stone basin; never creates open flowing curtains. */
final class SurvivorLavaTrap {
    private final SurvivorPlayer bot;
    private BlockPos target,approach;
    private int expires,nextAuto,segment;
    private boolean autoEnabled=true;
    private String status="idle";
    SurvivorLavaTrap(SurvivorPlayer bot){this.bot=bot;}
    String status(){return status;}
    BlockPos destination(){return approach;}
    private boolean stone(BlockPos p){
        if(!bot.world.isBlockLoaded(p)||!SurvivorPermissions.allowed(bot,p,true))return false;
        var state=bot.world.getBlockState(p);var block=state.getBlock();
        return state.isFullCube()&&(block==Blocks.STONE||block==Blocks.COBBLESTONE||block==Blocks.STONEBRICK||block==Blocks.OBSIDIAN||block==Blocks.BRICK_BLOCK);
    }
    private boolean contained(BlockPos p){
        if(p.distanceSq(bot.homePosition())>144||p.distanceSq(bot.homePosition())<25||!bot.world.isBlockLoaded(p)||!bot.world.isAirBlock(p)||!SurvivorPermissions.allowed(bot,p,true)||!stone(p.down()))return false;
        for(EnumFacing f:EnumFacing.HORIZONTALS)if(!stone(p.offset(f)))return false;
        // Keep flammable materials and nearby living entities away from a new source.
        for(BlockPos q:BlockPos.getAllInBoxMutable(p.add(-2,-1,-2),p.add(2,3,2))){
            if(!bot.world.isBlockLoaded(q)||bot.world.getBlockState(q).getMaterial().getCanBurn())return false;
        }
        return bot.world.getEntitiesWithinAABB(EntityLivingBase.class,new AxisAlignedBB(p).grow(2)).stream().allMatch(e->e==bot);
    }
    private int bucket(){
        for(int i=0;i<36;i++){ItemStack s=bot.inventory.getStackInSlot(i);if(!s.isEmpty()&&s.getItem()==Items.LAVA_BUCKET)return i;}
        return -1;
    }
    boolean autoPlan(int age){
        if(!autoEnabled||target!=null||age<nextAuto||bucket()<0||bot.constructionStock()<8)return false;
        nextAuto=age+160;
        // Four side pockets, not a continuous wall: leave the northern entry and escape lanes open.
        BlockPos[] cells={bot.homePosition().add(6,0,-2),bot.homePosition().add(-6,0,2),bot.homePosition().add(2,0,6),bot.homePosition().add(-2,0,6)};
        for(int attempt=0;attempt<cells.length;attempt++){
            BlockPos p=cells[Math.floorMod(segment++,cells.length)];
            if(!bot.world.isBlockLoaded(p)||!bot.world.isAirBlock(p)||!SurvivorPermissions.allowed(bot,p,true))continue;
            boolean safe=true;java.util.List<BlockPos> missing=new java.util.ArrayList<>();
            for(EnumFacing f:new EnumFacing[]{EnumFacing.DOWN,EnumFacing.NORTH,EnumFacing.SOUTH,EnumFacing.EAST,EnumFacing.WEST}){
                BlockPos q=p.offset(f);
                if(stone(q))continue;
                if(!bot.world.isBlockLoaded(q)||!bot.world.isAirBlock(q)||!SurvivorPermissions.allowed(bot,q,true)){safe=false;break;}
                missing.add(q);
            }
            if(!safe)continue;
            for(BlockPos q:BlockPos.getAllInBoxMutable(p.add(-2,-1,-2),p.add(2,3,2)))
                if(!bot.world.isBlockLoaded(q)||bot.world.getBlockState(q).getMaterial().getCanBurn()){safe=false;break;}
            if(!safe)continue;
            if(!bot.world.getEntitiesWithinAABB(EntityLivingBase.class,new AxisAlignedBB(p).grow(3),e->e!=bot).isEmpty())continue;
            if(missing.isEmpty()&&request(p,age))return true;
            for(BlockPos q:missing){
                if(!bot.world.isBlockLoaded(q.down())||!bot.world.getBlockState(q.down()).isFullCube())continue;
                for(String id:new String[]{"minecraft:obsidian","minecraft:cobblestone","minecraft:stone","minecraft:stonebrick"}){
                    for(int i=0;i<36;i++){
                        ItemStack stack=bot.inventory.getStackInSlot(i);
                        if(!stack.isEmpty()&&new net.minecraft.util.ResourceLocation(id).equals(stack.getItem().getRegistryName())&&bot.requestBuild(q,new net.minecraft.util.ResourceLocation(id))){status="constructing lava pocket";return true;}
                    }
                }
            }
        }
        return false;
    }
    boolean request(BlockPos p,int age){
        if(target!=null||bucket()<0||!contained(p))return false;
        BlockPos best=null;double distance=Double.MAX_VALUE;
        for(EnumFacing f:EnumFacing.HORIZONTALS)for(int dy=0;dy<=2;dy++){
            BlockPos q=p.offset(f,2).up(dy);
            if(SurvivorTerrain.stand(bot.world,q)&&bot.getDistanceSqToCenter(q)<distance){best=q;distance=bot.getDistanceSqToCenter(q);}
        }
        if(best==null)return false;
        target=p.toImmutable();approach=best;expires=age+600;status="queued";return true;
    }
    private void finish(String reason){target=null;approach=null;status=reason;}
    boolean tick(int age){
        if(target==null)return false;
        if(age>=expires||!contained(target)||bucket()<0){finish("cancelled: unsafe basin or no lava");return false;}
        if(bot.getDistanceSqToCenter(target)>16)return false;
        BlockPos floor=target.down();
        Vec3d point=new Vec3d(floor).add(.5,.999,.5);
        var hit=bot.world.rayTraceBlocks(bot.getPositionEyes(1),point,false,true,false);
        if(hit==null||!floor.equals(hit.getBlockPos())||hit.sideHit!=EnumFacing.UP)return false;
        bot.equipInventorySlot(bucket());bot.facePoint(point);bot.moveForward=0;bot.moveStrafing=0;
        var actual=bot.world.rayTraceBlocks(bot.getPositionEyes(1),bot.getPositionEyes(1).add(bot.getLookVec().scale(4.5)),false,true,false);
        if(actual==null||!floor.equals(actual.getBlockPos())||actual.sideHit!=EnumFacing.UP)return true;
        EnumActionResult result=bot.interactionManager.processRightClick(bot,bot.world,bot.getHeldItemMainhand(),EnumHand.MAIN_HAND);
        boolean placed=result==EnumActionResult.SUCCESS&&bot.world.getBlockState(target).getMaterial()==net.minecraft.block.material.Material.LAVA;
        finish(placed?"lava source placed":"placement rejected");return true;
    }
}
