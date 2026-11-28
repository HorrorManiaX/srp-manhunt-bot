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
        if(!java.util.Set.of("mined","crafted","encounters","explored","placed","kills","deaths","loot","active_seconds").contains(kind))return;
        NBTTagCompound a=agent(id,true);a.setInteger(kind,Math.min(1000000,a.getInteger(kind)+1));markDirty();
    }
    String progress(UUID id){
        NBTTagCompound a=agent(id,false);
        return "mined="+a.getInteger("mined")+" crafted="+a.getInteger("crafted")+" explored="+a.getInteger("explored")+" placed="+a.getInteger("placed")+" kills="+a.getInteger("kills")+" deaths="+a.getInteger("deaths")+" loot_pickups="+a.getInteger("loot")+" tracked_minutes="+(a.getInteger("active_seconds")/60);
    }
    // Faction-scoped social memory. No raw chat or coordinates are persisted.
    void rememberConversation(UUID bot,UUID other,SurvivorChatPolicy.Side side,SurvivorChatPolicy.Topic topic){
        NBTTagCompound a=agent(bot,true), social=a.getCompoundTag("social");
        String k=other.toString();
        if(!social.hasKey(k,10)&&social.getKeySet().size()>=32)return;
        NBTTagCompound entry=social.getCompoundTag(k);
        if(!side.name().equals(entry.getString("side")))entry=new NBTTagCompound();
        entry.setString("side",side.name());entry.setString("topic",topic.name());
        entry.setInteger("turns",Math.min(10000,Math.max(0,entry.getInteger("turns"))+1));
        social.setTag(k,entry);a.setTag("social",social);markDirty();
    }
    String recallConversation(UUID bot,UUID other,SurvivorChatPolicy.Side side){
        NBTTagCompound entry=agent(bot,false).getCompoundTag("social").getCompoundTag(other.toString());
        if(!side.name().equals(entry.getString("side")))return "";
        int turns=entry.getInteger("turns");if(turns<=0)return "";
        String topic=entry.getString("topic");
        try{SurvivorChatPolicy.Topic.valueOf(topic);}catch(IllegalArgumentException ex){return "";}
        return "Ранее уже общались; число реплик: "+turns+"; последняя тема: "+topic+".";
    }
    @Override public void readFromNBT(NBTTagCompound nbt) {agents=nbt.getCompoundTag("agents");}
    @Override public NBTTagCompound writeToNBT(NBTTagCompound nbt) {nbt.setTag("agents",agents);return nbt;}
}
