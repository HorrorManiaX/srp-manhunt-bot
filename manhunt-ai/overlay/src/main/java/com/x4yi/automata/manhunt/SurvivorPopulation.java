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
    private boolean enabled;
    private int target,failures;
    private long tick,nextJoin=200;
    private BlockPos camp;
    private int sample;
    void init(){
        if(config!=null)return;
        config=new Configuration(new File(Loader.instance().getConfigDir(),"manhunt-ai.cfg"));
        config.load();
        enabled=config.getBoolean("enabled","population",true,"Automatically start clearly labelled AI survivors in loaded overworld areas.");
        target=config.getInt("target","population",4,0,6,"Maximum desired total active AI survivors.");
        if(config.hasChanged())config.save();
    }
    boolean enabled(){init();return enabled;}
    int target(){init();return target;}
    void configure(boolean value,int count){
        init();enabled=value;target=SurvivorPopulationPolicy.capacity(count);
        config.get("population","enabled",true).set(enabled);
        config.get("population","target",4).set(target);config.save();
        if(value){suppressed.clear();nextJoin=tick+40;failures=0;}
    }
    void suppress(String name){suppressed.add(name.toLowerCase(Locale.ROOT));}
    void lost(String name,boolean death){
        String k=name.toLowerCase(Locale.ROOT);
        if(death){int n=deaths.merge(k,1,Integer::sum);cooldown.put(k,tick+SurvivorPopulationPolicy.deathDelay(n));}
        else cooldown.put(k,tick+6000);
    }
    void reset(){config=null;tick=0;nextJoin=200;failures=0;camp=null;sample=0;suppressed.clear();cooldown.clear();deaths.clear();}
    void tick(MinecraftServer server,SurvivorService service,boolean stressed){
        init();tick++;
        if(!SurvivorPopulationPolicy.mayJoin(enabled,stressed,service.activeCount(),target,tick,nextJoin))return;
        WorldServer world=server.getWorld(0);
        if(world==null){nextJoin=tick+200;return;}
        // Exactly one candidate attempt per pass. Restore only loaded snapshots.
        for(String name:NAMES){
            String full="AI_"+name,k=full.toLowerCase(Locale.ROOT);
            if(suppressed.contains(k)||cooldown.getOrDefault(k,0L)>tick||service.activeName(full)||server.getPlayerList().getPlayerByUsername(full)!=null)continue;
            try{
                SurvivorRoster roster=SurvivorRoster.get(world);
                if(roster.contains(k)){
                    if(service.autoResume(server,world,k)){success();return;}
                    continue;
                }
                if(service.parkedElsewhere(server,k))continue;
                BlockPos site=pickSite(world,service);
                if(site==null)break;
                if(service.autoSpawn(server,world,full,site)){camp=site;success();return;}
            }catch(Exception | LinkageError ex){
                cooldown.put(k,tick+6000);
                AutomataMod.LOGGER.warn("Manhunt AI population candidate failed: "+full,ex);
            }
            break;
        }
        nextJoin=tick+SurvivorPopulationPolicy.retryDelay(++failures);
    }
    private void success(){failures=0;nextJoin=tick+240;}
    private BlockPos pickSite(WorldServer world,SurvivorService service){
        BlockPos origin=camp!=null?camp:service.overworldCamp(world);
        if(origin==null)origin=world.getSpawnPoint();
        // Stay within loaded chunks; try a small sample each pass.
        for(int i=0;i<24;i++){
            int k=sample++ & 255;
            int r=camp!=null?4+(k/16)%5:24+(k/16)*2;
            double a=(k%16)*Math.PI/8;
            BlockPos column=origin.add(Math.round(Math.cos(a)*r),0,Math.round(Math.sin(a)*r));
            if(!world.isBlockLoaded(column))continue;
            int y=world.getHeight(column).getY();
            BlockPos p=new BlockPos(column.getX(),y,column.getZ());
            if(y<2||y>253||!SurvivorService.standable(world,p)||!world.getWorldBorder().contains(p)||service.occupied(world,p))continue;
            if(!world.getEntitiesWithinAABB(EntityLivingBase.class,new AxisAlignedBB(p).grow(12),e->e.isEntityAlive()&&ManhuntCompat.isHostile(e)).isEmpty())continue;
            if(!world.getEntitiesWithinAABB(net.minecraft.entity.player.EntityPlayer.class,new AxisAlignedBB(p).grow(3)).isEmpty())continue;
            return p;
        }
        return null;
    }
}
