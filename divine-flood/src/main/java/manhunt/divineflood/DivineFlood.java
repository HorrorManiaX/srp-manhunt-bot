package manhunt.divineflood;

import java.io.File;
import java.util.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.*;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(modid=DivineFlood.ID, name="Divine Flood", version="1.1.0", acceptedMinecraftVersions="[1.12.2]", acceptableRemoteVersions="*", serverSideOnly=true)
public final class DivineFlood {
    public static final String ID="divineflood";
    public static final long THREE_DAYS=259200000L;
    private static final String SAVE="divineflood_event";
    private static final String CHUNK_TAG="DivineFloodCompleted";
    private BossInfoServer boss;
    private MinecraftServer server;
    private State state;
    private int ticks;
    private int overworldY, netherY, endY, writesPerTick, scanPerTick, budgetMicros;
    private final ArrayDeque<Job> queue=new ArrayDeque<>();
    private final Set<Job> queued=new HashSet<>();
    private final Map<Chunk,Long> done=Collections.synchronizedMap(new WeakHashMap<Chunk,Long>());
    private final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();

    @Mod.EventHandler public void preInit(FMLPreInitializationEvent e) {
        Configuration c=new Configuration(new File(e.getModConfigurationDirectory(),ID+".cfg"));
        c.load();
        overworldY=c.getInt("overworldWaterY","flood",120,1,254,"Inclusive water level in dimension 0.");
        netherY=c.getInt("netherLavaY","flood",120,1,254,"Inclusive lava level in dimension -1.");
        endY=c.getInt("endLavaY","flood",128,1,254,"Inclusive lava level in dimension 1.");
        writesPerTick=c.getInt("maxBlockWritesPerTick","performance",384,1,4096,"Global cap; no forced chunk loads.");
        scanPerTick=c.getInt("maxCellsScannedPerTick","performance",4096,64,65536,"Global scan cap.");
        budgetMicros=c.getInt("tickBudgetMicros","performance",3500,250,12000,"Soft wall-clock budget; one block update may overrun.");
        if(c.hasChanged()) c.save();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler public void starting(FMLServerStartingEvent e) {
        server=e.getServer();
        e.registerServerCommand(new Control());
        MapStorage storage=server.getWorld(0).getMapStorage();
        state=(State)storage.getOrLoadData(State.class,SAVE);
        if(state==null) {
            state=new State(SAVE);
            state.mode=1;
            state.deadline=System.currentTimeMillis()+THREE_DAYS;
            storage.setData(SAVE,state);
            persist();
        }
        ticks=0;
        boss=new BossInfoServer(new TextComponentString(""),BossInfo.Color.RED,BossInfo.Overlay.PROGRESS);
        sync();
        if(state.mode==2) enqueueLoaded();
    }
    @Mod.EventHandler public void stopped(FMLServerStoppedEvent e) {
        if(boss!=null) boss.setVisible(false);
        boss=null; queue.clear(); queued.clear(); done.clear(); state=null; server=null; ticks=0;
    }

    private void persist() {
        state.markDirty();
        server.getWorld(0).getMapStorage().saveAllData();
    }
    private void broadcast(String s) {
        server.getPlayerList().sendMessage(new TextComponentString(s));
    }
    private void sync() {
        if(boss==null || state==null) return;
        if(state.mode!=1 && state.mode!=2) {
            boss.setVisible(false);
            return;
        }
        if(state.mode==1) {
            long ms=Math.max(0L,state.deadline-System.currentTimeMillis());
            long sec=(ms+999)/1000;
            String time=String.format(Locale.ROOT,"%02dд %02d:%02d:%02d",sec/86400,(sec/3600)%24,(sec/60)%60,sec%60);
            boss.setName(new TextComponentString("§6§l☦ ВРЕМЯ ИСКУПЛЕНИЯ §r§e"+time));
            boss.setColor(BossInfo.Color.RED);
            boss.setPercent(Math.min(1f,ms/(float)THREE_DAYS));
        } else {
            boss.setName(new TextComponentString("§4§l☦ ГНЕВ БОЖИЙ §r§6— ВСЕМИРНЫЙ ПОТОП"));
            boss.setColor(BossInfo.Color.RED);
            boss.setPercent(1f);
        }
        boss.setVisible(true);
    }
    @SubscribeEvent public void join(PlayerEvent.PlayerLoggedInEvent e) {
        if(boss!=null && e.player instanceof EntityPlayerMP) {
            sync();
            boss.addPlayer((EntityPlayerMP)e.player);
        }
    }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if(boss!=null && e.player instanceof EntityPlayerMP)
            boss.removePlayer((EntityPlayerMP)e.player);
    }
    private void activate() {
        state.mode=2; persist(); enqueueLoaded(); sync();
        broadcast("§6§lГНЕВ БОЖИЙ §r§cВремя милосердия истекло. Начался Всемирный потоп.");
    }
    private int height(int dim) {
        return dim==0?overworldY:dim==-1?netherY:dim==1?endY:-1;
    }
    private void enqueueLoaded() {
        for(WorldServer w:DimensionManager.getWorlds()) {
            if(w!=null && height(w.provider.getDimension())>=0)
                for(Chunk ch:w.getChunkProvider().getLoadedChunks()) enqueue(w,ch);
        }
    }
    private void enqueue(World w,Chunk ch) {
        int d=w.provider.getDimension();
        if(state==null || state.mode!=2 || w.isRemote || height(d)<0 ||
           Objects.equals(done.get(ch),state.deadline)) return;
        Job job=new Job(d,ch.x,ch.z);
        if(queued.add(job)) queue.addLast(job);
    }
    @SubscribeEvent public void chunkLoad(ChunkEvent.Load e) {
        if(e.getWorld() instanceof WorldServer) enqueue(e.getWorld(),e.getChunk());
    }
    @SubscribeEvent public void chunkDataLoad(ChunkDataEvent.Load e) {
        if(!e.getWorld().isRemote && e.getData().hasKey(CHUNK_TAG))
            done.put(e.getChunk(),e.getData().getLong(CHUNK_TAG));
    }
    @SubscribeEvent public void chunkSave(ChunkDataEvent.Save e) {
        if(e.getWorld().isRemote) return;
        Long id=done.get(e.getChunk());
        if(id!=null) e.getData().setLong(CHUNK_TAG,id);
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent e) {
        if(e.phase!=TickEvent.Phase.END || state==null) return;
        if(++ticks>=20) {
            ticks=0;
            if(state.mode==1 && System.currentTimeMillis()>=state.deadline) activate();
            else if(state.mode==1) sync();
        }
        if(state.mode==2 && !queue.isEmpty()) floodTick();
    }
    private void floodTick() {
        long until=System.nanoTime()+budgetMicros*1000L;
        int scanned=0,writes=0;
        while(!queue.isEmpty() && scanned<scanPerTick && writes<writesPerTick && System.nanoTime()<until) {
            Job j=queue.removeFirst();
            WorldServer w=DimensionManager.getWorld(j.dim);
            Chunk ch=w==null?null:w.getChunkProvider().getLoadedChunk(j.x,j.z);
            if(ch==null || Objects.equals(done.get(ch),state.deadline)) {
                queued.remove(j); continue;
            }
            int top=height(j.dim), total=(top+1)*256;
            IBlockState fluid=(j.dim==0?Blocks.WATER:Blocks.LAVA).getDefaultState();
            int slice=0;
            while(j.cursor<total && slice<256 && scanned<scanPerTick && writes<writesPerTick && System.nanoTime()<until) {
                int i=j.cursor++;
                // Top-down: fill all existing air, including rooms/caves. Preserve solid blocks and tile entities.
                pos.setPos((j.x<<4)+(i&15),top-(i>>>8),(j.z<<4)+((i>>>4)&15));
                scanned++; slice++;
                if(ch.getBlockState(pos).getBlock()==Blocks.AIR) {
                    if(w.setBlockState(pos,fluid,2|16)) writes++;
                }
            }
            if(j.cursor>=total) {
                done.put(ch,state.deadline); ch.markDirty(); queued.remove(j);
            } else queue.addLast(j);
        }
    }

    public static final class State extends WorldSavedData {
        int mode; // 1 countdown, 2 flood, 3 pardoned/stopped
        long deadline;
        public State(String name) { super(name); }
        @Override public void readFromNBT(NBTTagCompound n) {
            mode=n.getInteger("mode"); deadline=n.getLong("deadline");
        }
        @Override public NBTTagCompound writeToNBT(NBTTagCompound n) {
            n.setInteger("mode",mode); n.setLong("deadline",deadline); return n;
        }
    }
    private static final class Job {
        final int dim,x,z; int cursor;
        Job(int d,int x,int z) { this.dim=d; this.x=x; this.z=z; }
        @Override public int hashCode() { return (dim*31+x)*31+z; }
        @Override public boolean equals(Object o) {
            if(!(o instanceof Job)) return false;
            Job j=(Job)o; return j.dim==dim && j.x==x && j.z==z;
        }
    }

    public class Control extends CommandBase {
        @Override public String getName(){return "divineflood";}
        @Override public String getUsage(ICommandSender s){return "/divineflood <status|pardon|stop|start [seconds]>";}
        @Override public int getRequiredPermissionLevel(){return 4;}
        @Override public List<String> getTabCompletions(MinecraftServer s,ICommandSender p,String[] a,BlockPos t) {
            return a.length==1?getListOfStringsMatchingLastWord(a,"status","pardon","stop","start"):Collections.emptyList();
        }
        @Override public void execute(MinecraftServer s,ICommandSender sender,String[] args) throws CommandException {
            if(state==null || args.length==0) throw new WrongUsageException(getUsage(sender));
            switch(args[0].toLowerCase(Locale.ROOT)) {
                case "status":
                    sender.sendMessage(new TextComponentString("mode="+state.mode+"; remainingSeconds="+Math.max(0L,(state.deadline-System.currentTimeMillis()+999)/1000)+"; queuedChunks="+queue.size())); break;
                case "pardon":
                    if(state.mode!=1) throw new CommandException("Покаяние возможно только до потопа.");
                    state.mode=3; persist(); sync();
                    broadcast("§6§lМИЛОСЕРДИЕ §r§aИскупление принято. Потоп отменён."); break;
                case "stop":
                    state.mode=3; queue.clear(); queued.clear(); persist(); sync();
                    sender.sendMessage(new TextComponentString("Потоп остановлен; уже размещённые жидкости остаются.")); break;
                case "start":
                    if(state.mode==1 || state.mode==2) throw new CommandException("Сначала остановите текущее событие.");
                    int seconds=args.length>1?parseInt(args[1],1,259200):259200;
                    state.mode=1; state.deadline=System.currentTimeMillis()+seconds*1000L;
                    persist(); sync(); broadcast("§6Господь дал миру время на искупление."); break;
                default: throw new WrongUsageException(getUsage(sender));
            }
        }
    }

}
