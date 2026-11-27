package com.x4yi.automata.manhunt;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldSavedData;
/** Persistent, bounded knowledge. Stored with the world, not player chat or API services. */
public final class SurvivorMemory extends WorldSavedData {
    private static final String KEY="manhunt_ai_memory_v1";
    private NBTTagCompound agents=new NBTTagCompound();
    public SurvivorMemory() { super(KEY); }
    public SurvivorMemory(String name) { super(name); }
    static SurvivorMemory get(WorldServer world) {
        var storage=world.getPerWorldStorage();
        SurvivorMemory m=(SurvivorMemory)storage.getOrLoadData(SurvivorMemory.class,KEY);
        if(m==null){m=new SurvivorMemory();storage.setData(KEY,m);}
        return m;
    }
    private NBTTagCompound agent(UUID uuid, boolean create) {
        String key=uuid.toString();
        if(!agents.hasKey(key,10)) {
            if(!create || agents.getKeySet().size()>=128) return new NBTTagCompound();
            agents.setTag(key,new NBTTagCompound());
        }
        return agents.getCompoundTag(key);
    }
    void note(UUID id,String type,BlockPos pos,long tick) {
        if(!type.equals("wood")&&!type.equals("stone")&&!type.equals("danger")&&!type.equals("bench")) return;
        NBTTagCompound a=agent(id,true), entry=new NBTTagCompound();
        entry.setLong("pos",pos.toLong());entry.setLong("tick",tick);
        a.setTag(type,entry);markDirty();
    }
    BlockPos recall(UUID id,String type,long now,long ttl) {
        NBTTagCompound a=agent(id,false);
        if(!a.hasKey(type,10))return null;
        NBTTagCompound e=a.getCompoundTag(type);
        long elapsed=now-e.getLong("tick");
        return elapsed>=0 && elapsed<=ttl ? BlockPos.fromLong(e.getLong("pos")):null;
    }
    void count(UUID id,String kind) {
        if(!kind.equals("mined")&&!kind.equals("crafted")&&!kind.equals("encounters"))return;
        NBTTagCompound a=agent(id,true);a.setInteger(kind,Math.min(1000000,a.getInteger(kind)+1));markDirty();
    }
    @Override public void readFromNBT(NBTTagCompound nbt) {agents=nbt.getCompoundTag("agents");}
    @Override public NBTTagCompound writeToNBT(NBTTagCompound nbt) {nbt.setTag("agents",agents);return nbt;}
}
