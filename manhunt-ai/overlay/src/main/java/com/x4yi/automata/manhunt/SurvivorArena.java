package com.x4yi.automata.manhunt;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

/** Prepare a separate, operator-designated arena without launching the global event. */
final class SurvivorArena {
    private final SurvivorPlayer bot;
    private BlockPos origin;
    private int next,siteCursor;
    private String status="not assigned";
    SurvivorArena(SurvivorPlayer bot){this.bot=bot;}
    String status(){return status;}
    boolean hasSpecimen(){return origin!=null&&bot.world.isBlockLoaded(origin.east())&&new ResourceLocation("overlast:final_specimen").equals(bot.world.getBlockState(origin.east()).getBlock().getRegistryName());}
    boolean request(BlockPos p){
        if(p.distanceSq(bot.getPosition())>100||p.distanceSq(bot.homePosition())>128*128||!bot.world.isBlockLoaded(p)||!bot.world.isAirBlock(p))return false;
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++){
            BlockPos q=p.add(x,0,z);
            if(!bot.world.isBlockLoaded(q)||!bot.world.canSeeSky(q.up(6)))return false;
        }
        origin=p.toImmutable();status="site assigned";return true;
    }
    private boolean carrying(String id){
        ResourceLocation key=new ResourceLocation(id);
        for(int i=0;i<36;i++){ItemStack s=bot.inventory.getStackInSlot(i);if(!s.isEmpty()&&key.equals(s.getItem().getRegistryName()))return true;}
        return false;
    }
    private boolean mineral(BlockPos p){
        var b=bot.world.getBlockState(p).getBlock();
        return b==Blocks.IRON_BLOCK||b==Blocks.GOLD_BLOCK||b==Blocks.DIAMOND_BLOCK||b==Blocks.EMERALD_BLOCK;
    }
    boolean plan(int age){
        if(age<next||SurvivorFinalBattle.current()!=null)return false;
        next=age+100;
        if(origin==null){
            if(!carrying("overlast:final_specimen"))return false;
            status="surveying arena site";
            BlockPos here=bot.getPosition();
            for(int i=0;i<8;i++){
                int k=Math.floorMod(siteCursor++,81);
                BlockPos ground=here.add(k%9-4,0,k/9-4);
                BlockPos p=ground.up();
                if(!SurvivorTerrain.stand(bot.world,ground))continue;
                boolean flat=true;
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                    BlockPos q=ground.add(dx,0,dz);
                    if(!SurvivorTerrain.stand(bot.world,q)||!SurvivorPermissions.allowed(bot,q,true))flat=false;
                }
                if(flat&&request(p))break;
            }
            if(origin==null)return false;
        }
        if(!bot.world.isBlockLoaded(origin)||!bot.world.isAirBlock(origin)){status="beacon site occupied";return false;}
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){
            BlockPos p=origin.add(x,-1,z);
            if(!bot.world.isBlockLoaded(p)||!SurvivorPermissions.allowed(bot,p,true)){status="foundation unavailable";return false;}
            if(mineral(p))continue;
            if(!bot.world.isAirBlock(p)){status="clear foundation manually; no demolition";return false;}
            if(!carrying("minecraft:iron_block")){status="requires iron blocks";return false;}
            if(bot.requestArenaBlock(p,new ResourceLocation("minecraft:iron_block")))return true;
            return false;
        }
        boolean found=false;
        ResourceLocation specimen=new ResourceLocation("overlast:final_specimen");
        for(int x=-3;x<=3&&!found;x++)for(int z=-3;z<=3&&!found;z++)for(int y=-3;y<=3;y++){
            BlockPos p=origin.add(x,y,z);
            if(bot.world.isBlockLoaded(p)&&specimen.equals(bot.world.getBlockState(p).getBlock().getRegistryName())){found=true;break;}
        }
        if(!found){
            if(!carrying("overlast:final_specimen")){status="requires final specimen";return false;}
            BlockPos p=origin.east();
            if(bot.world.isBlockLoaded(p)&&bot.world.isAirBlock(p)&&bot.requestArenaBlock(p,specimen))return true;
            status="specimen site unavailable";return false;
        }
        status=carrying("minecraft:beacon")?"foundation and specimen ready; beacon carried; operator must validate and start at "+origin:"foundation and specimen ready; requires beacon";
        return false;
    }
}
