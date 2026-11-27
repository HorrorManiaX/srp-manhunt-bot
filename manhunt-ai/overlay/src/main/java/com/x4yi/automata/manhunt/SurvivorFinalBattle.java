package com.x4yi.automata.manhunt;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.x4yi.automata.AutomataMod;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

/** Read-only integration with the actual OverLast battle manager. No admin/debug commands. */
final class SurvivorFinalBattle {
    private static Method active,state,participantRadius,scoringRadius,validate;
    private static Field origin,dimension,wave,score,threshold,valid;
    private static boolean checked,available;
    private static void init(){
        if(checked)return;checked=true;
        if(!net.minecraftforge.fml.common.Loader.isModLoaded("overlast"))return;
        try{
            Class<?> manager=Class.forName("com.overlast.finalbattle.FinalBattleManager");
            Class<?> info=Class.forName("com.overlast.finalbattle.FinalBattleState");
            Class<?> config=Class.forName("com.overlast.finalbattle.FinalBattleConfig");
            active=manager.getMethod("isActive");state=manager.getMethod("getActiveState");
            participantRadius=config.getMethod("getParticipantRadius");scoringRadius=config.getMethod("getScoringRadius");
            origin=info.getField("origin");dimension=info.getField("dimension");wave=info.getField("currentWaveIndex");
            score=info.getField("currentScore");threshold=info.getField("threshold");valid=info.getField("structureValid");
            validate=Class.forName("com.overlast.finalbattle.FinalBattleStructureValidator").getMethod("validate",World.class,BlockPos.class);
            available=true;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){AutomataMod.LOGGER.warn("AI OverLast integration unavailable",e);}
    }
    record Battle(BlockPos origin,int dimension,int radius,int scoreRadius,int wave,int score,int threshold,boolean intact){}
    static Battle read(){
        init();if(!available)return null;
        try{
            if(!Boolean.TRUE.equals(active.invoke(null)))return null;
            Object b=state.invoke(null);if(b==null)return null;
            return new Battle((BlockPos)origin.get(b),dimension.getInt(b),((Number)participantRadius.invoke(null)).intValue(),((Number)scoringRadius.invoke(null)).intValue(),wave.getInt(b),score.getInt(b),threshold.getInt(b),valid.getBoolean(b));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){available=false;AutomataMod.LOGGER.warn("AI OverLast monitor disabled",e);return null;}
    }
    static String validate(World world,BlockPos pos){
        init();if(!available)return "OverLast API unavailable";
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)if(!world.isBlockLoaded(pos.add(x,0,z)))return "arena chunks unavailable";
        try{Object result=validate.invoke(null,world,pos);return String.valueOf(result.getClass().getField("reason").get(result));}
        catch(ReflectiveOperationException|RuntimeException|LinkageError e){return "validation failed: "+e.getClass().getSimpleName();}
    }
    private static Battle cached;
    static void refresh(){cached=read();}
    static Battle current(){return cached;}
    static BlockPos rendezvous(SurvivorPlayer bot){
        Battle b=cached;
        if(b==null||b.dimension()!=bot.world.provider.getDimension()||!b.intact()||bot.getHealth()<bot.getMaxHealth()*.65f||!bot.hasLoadedGun()||bot.getFoodStats().getFoodLevel()<12)return null;
        if(bot.getDistanceSqToCenter(b.origin())>48*48)return null;
        if(bot.getDistanceSqToCenter(b.origin())<Math.max(4,Math.min(b.radius(),b.scoreRadius())-4)*Math.max(4,Math.min(b.radius(),b.scoreRadius())-4))return null;
        BlockPos best=null;double d=Double.MAX_VALUE;
        for(var facing:net.minecraft.util.EnumFacing.HORIZONTALS)for(int r=3;r<=6;r+=3){
            BlockPos p=b.origin().offset(facing,r);
            if(SurvivorTerrain.stand(bot.world,p)&&bot.getDistanceSqToCenter(p)<d){best=p;d=bot.getDistanceSqToCenter(p);}
        }
        return best;
    }
}
