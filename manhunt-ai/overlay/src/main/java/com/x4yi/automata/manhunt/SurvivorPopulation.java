package com.x4yi.automata.manhunt;
import java.io.File;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Loader;
import com.x4yi.automata.compat.ManhuntCompat;
import com.x4yi.automata.AutomataMod;

/** Server-side population director. Never logs in a remote account or alters the real player count. */
final class SurvivorPopulation {
    private static final String[] NAMES={"Alex","Boris","Mira","Viktor","Lena","Denis"};
    private final Set<String> suppressed=new HashSet<>();
    private final Map<String,Long> cooldown=new HashMap<>();
    private final Map<String,Integer> deaths=new HashMap<>();
    private Configuration config;
    private boolean enabled, siegeKit;
    private int target,failures;
    private final Random random=new Random();
    private long tick,nextJoin=SurvivorPopulationPolicy.joinDelay(random);
    private BlockPos camp;
    private boolean reinforcement;
    private int sample;
    private String status="initializing";
    String status(){return status+"; next_attempt_ticks="+Math.max(0,nextJoin-tick)+"; failures="+failures;}
    void init(){
        if(config!=null)return;
        config=new Configuration(new File(Loader.instance().getConfigDir(),"manhunt-ai.cfg"));
        config.load();
        enabled=config.getBoolean("enabled","population",true,"Automatically start clearly labelled AI survivors in loaded overworld areas.");
        target=config.getInt("target","population",4,0,6,"Maximum desired total active AI survivors.");
        siegeKit=config.getBoolean("siegeStarterKit","population",true,"One-time equipped survivor kit for already infected worlds; no repeated kit after death.");
        if(config.hasChanged())config.save();
    }
    boolean enabled(){init();return enabled;}
    int target(){init();return target;}
    void configure(boolean value,int count){
        init();enabled=value;target=SurvivorPopulationPolicy.capacity(count);
        config.get("population","enabled",true).set(enabled);
        config.get("population","target",4).set(target);config.save();
        if(value){suppressed.clear();nextJoin=tick+SurvivorPopulationPolicy.joinDelay(random);failures=0;}
    }
    private static String canonical(String n){return (n.regionMatches(true,0,"AI_",0,3)?n:"AI_"+n).toLowerCase(Locale.ROOT);}
    void reinforce(){init();enabled=true;if(target==0)target=4;reinforcement=true;failures=0;nextJoin=tick+20;suppressed.clear();config.get("population","enabled",true).set(true);config.get("population","target",4).set(target);config.save();}
    void suppress(String name){suppressed.add(canonical(name));}
    void lost(String name,boolean death){
        String k=canonical(name);
        if(death){int n=deaths.merge(k,1,Integer::sum);cooldown.put(k,tick+SurvivorPopulationPolicy.deathDelay(n));}
        else cooldown.put(k,tick+6000);
    }
    void reset(){config=null;tick=0;nextJoin=SurvivorPopulationPolicy.joinDelay(random);failures=0;camp=null;reinforcement=false;sample=0;suppressed.clear();cooldown.clear();deaths.clear();}
    void tick(MinecraftServer server,SurvivorService service,boolean stressed){
        tick+=20;if(tick<nextJoin)return;init();
        if(!SurvivorPopulationPolicy.mayJoin(enabled,stressed,service.activeCount(),target,tick,nextJoin)){status=!enabled?"disabled":stressed?"server load":service.activeCount()>=target?"full":"waiting";return;}
        WorldServer world=server.getWorld(0);
        if(world==null){status="overworld unavailable";nextJoin=tick+200;return;}
        status="no eligible candidate";
        // Exactly one candidate attempt per pass. Restore only loaded snapshots.
        for(String name:NAMES){
            String full=name,k=canonical(full);
            if(suppressed.contains(k)||cooldown.getOrDefault(k,0L)>tick||service.activeName(full)||(server.getPlayerList().getPlayerByUsername(full)!=null||server.getPlayerList().getPlayerByUsername("AI_"+full)!=null))continue;
            try{
                SurvivorRoster roster=SurvivorRoster.get(world);
                if(roster.contains(k)){
                    if(service.autoResume(server,world,k)){success();return;}
                    continue;
                }
                if(service.parkedElsewhere(server,k))continue;
                BlockPos site=pickSite(world,service);
                if(site==null){status="no safe loaded site for "+full;break;}
                if(service.autoSpawn(server,world,full,site,siegeKit)){if(camp==null)camp=site;success();return;}status="spawn denied for "+full;
            }catch(Exception | LinkageError ex){
                cooldown.put(k,tick+6000);
                AutomataMod.LOGGER.warn("Manhunt AI population candidate failed: "+full,ex);
            }
            break;
        }
        nextJoin=tick+SurvivorPopulationPolicy.retryDelay(++failures);
    }
    void failure(Throwable ex){
        nextJoin=tick+6000;
        status="director error";if(++failures==1)AutomataMod.LOGGER.warn("Manhunt AI population director temporarily paused",ex);
    }
    private void success(){failures=0;status="joined";nextJoin=tick+(reinforcement?200+random.nextInt(401):1200+random.nextInt(1201));}
    private BlockPos pickSite(WorldServer world,SurvivorService service){
        BlockPos origin=camp!=null?camp:service.overworldCamp(world);
        if(origin!=null)camp=origin;
        if(origin==null||reinforcement){BlockPos bro=service.broAnchor(world);if(bro!=null)origin=bro;}
        if(origin==null)origin=world.getSpawnPoint();
        // After unsuccessful searches, expand to loaded areas around actual online humans.
        if(failures>=2 && (sample/16)%2==0){
            var humans=service.safeHumanAnchors(world);
            if(!humans.isEmpty())origin=humans.get(Math.floorMod(sample/24,humans.size()));
        }
        BlockPos best=null;int bestScore=-1;
        for(int i=0;i<16;i++){
            int k=sample++ & 255;
            int radius=camp!=null&&failures<2?6+(k/16)%12:8+(k/16)*6;
            double angle=(k%16)*Math.PI/8;
            BlockPos column=origin.add(Math.round(Math.cos(angle)*radius),0,Math.round(Math.sin(angle)*radius));
            if(!world.isBlockLoaded(column))continue;
            int top=world.getHeight(column).getY();
            // A city rooftop is not necessarily the safest place. Try loaded floors below it too.
            for(int y:new int[]{origin.getY(),origin.getY()+1,origin.getY()-1,top,top-4,top-8,top-12,origin.getY()+4,origin.getY()-4}){
                BlockPos p=new BlockPos(column.getX(),y,column.getZ());
                if(y<2||y>253||!SurvivorService.standable(world,p)||service.occupied(world,p)||service.homeTooClose(world,p))continue;
                var floor=world.getBlockState(p.down());
                if(floor.getMaterial()==net.minecraft.block.material.Material.LEAVES||floor.getMaterial().isLiquid()||floor.getBlock()==net.minecraft.init.Blocks.MAGMA||floor.getBlock()==net.minecraft.init.Blocks.CACTUS)continue;
                int walkable=0;
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)if(SurvivorService.standable(world,p.add(x,0,z)))walkable++;
                if(walkable<(failures>=2?9:14))continue;
                int exits=SurvivorTerrain.safeExits(world,p);
                if(exits<(failures>=2?1:2))continue;
                int elevation=SurvivorTerrain.elevation(world,p);
                int cover=0;
                for(int h=2;h<=5;h++)if(world.isBlockLoaded(p.up(h))&&world.getBlockState(p.up(h)).isFullCube()){cover=12;break;}
                int score=walkable+cover+elevation*5+exits*3;
                if(score<=bestScore)continue;
                if(!world.getEntitiesWithinAABB(EntityLivingBase.class,new AxisAlignedBB(p).grow(failures>=4?12:failures>=2?16:24),e->e.isEntityAlive()&&ManhuntCompat.isHostile(e)).isEmpty())continue;
                if(!world.getEntitiesWithinAABB(net.minecraft.entity.player.EntityPlayer.class,new AxisAlignedBB(p).grow(4),e->!(e instanceof SurvivorPlayer)).isEmpty())continue;
                best=p;bestScore=score;
            }
        }
        return best;
    }
}
