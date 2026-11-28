package com.x4yi.automata.manhunt;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldSavedData;

/** Explicit park/resume snapshots; separate from authenticated player saves. */
public final class SurvivorRoster extends WorldSavedData {
    private static final String KEY="manhunt_ai_roster_v1";
    private NBTTagCompound parked=new NBTTagCompound(), recruited=new NBTTagCompound();
    public SurvivorRoster(){super(KEY);}
    public SurvivorRoster(String name){super(name);}
    static SurvivorRoster get(WorldServer world){
        var storage=world.getPerWorldStorage();
        SurvivorRoster roster=(SurvivorRoster)storage.getOrLoadData(SurvivorRoster.class,KEY);
        if(roster==null){roster=new SurvivorRoster();storage.setData(KEY,roster);}
        return roster;
    }
    boolean recruited(String key){return recruited.getBoolean(key);}
    void markRecruited(String key){recruited.setBoolean(key,true);markDirty();}
    boolean contains(String key){return parked.hasKey(key,10);}
    boolean park(String key,SurvivorPlayer bot){
        if((!contains(key)&&parked.getKeySet().size()>=32)||!bot.isEntityAlive())return false;
        NBTTagCompound n=new NBTTagCompound();
        n.setString("name",bot.getName());
        n.setString("uuid",bot.getUniqueID().toString());
        n.setLong("home",bot.homePosition().toLong());
        n.setLong("position",bot.getPosition().toLong());
        n.setTag("inventory",bot.inventory.writeToNBT(new NBTTagList()));
        NBTTagCompound player=new NBTTagCompound();
        bot.writeToNBT(player); // Vanilla player data plus ForgeCaps and ForgeData.
        n.setTag("player",player);
        n.setInteger("dimension",bot.world.provider.getDimension());
        n.setFloat("health",bot.getHealth());
        bot.getFoodStats().writeNBT(n);
        n.setInteger("hotbar",bot.inventory.currentItem);
        n.setBoolean("autonomous",bot.autonomousEnabled());
        n.setBoolean("industry",bot.industryEnabled());
        n.setString("goal",bot.technologyGoalId());
        parked.setTag(key,n);markDirty();return true;
    }
    NBTTagCompound snapshot(String key){return contains(key)?parked.getCompoundTag(key).copy():null;}
    void forget(String key){parked.removeTag(key);markDirty();}
    java.util.Set<String> names(){return new java.util.TreeSet<>(parked.getKeySet());}
    static java.util.UUID uuid(NBTTagCompound n){
        if(n.hasKey("uuid",8))try{return java.util.UUID.fromString(n.getString("uuid"));}catch(IllegalArgumentException ignored){}
        return java.util.UUID.nameUUIDFromBytes(("ManhuntAI:"+n.getString("name")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static BlockPos position(NBTTagCompound n){return BlockPos.fromLong(n.getLong("position"));}
    static BlockPos home(NBTTagCompound n){return BlockPos.fromLong(n.getLong("home"));}
    static void restore(SurvivorPlayer bot,NBTTagCompound n){
        if(n.hasKey("player",10)){
            NBTTagCompound player=n.getCompoundTag("player").copy();
            // Profile identity and world are controlled by the roster; relocation is applied by the caller.
            bot.readFromNBT(player);
            bot.getEntityData().setBoolean("ManhuntAIPlayer",true);
            bot.setGameType(net.minecraft.world.GameType.SURVIVAL);
        }else{
            bot.inventory.clear();
            bot.inventory.readFromNBT(n.getTagList("inventory",10));
        }
        bot.inventory.currentItem=Math.max(0,Math.min(8,n.getInteger("hotbar")));
        float health=n.getFloat("health");
        bot.setHealth(Float.isFinite(health)?Math.max(1,Math.min(bot.getMaxHealth(),health)):1);
        bot.getFoodStats().readNBT(n);
        bot.autonomous(n.getBoolean("autonomous"));
        bot.industry(n.getBoolean("industry"));
        String goal=n.getString("goal");
        if(!goal.isEmpty())bot.techGoal(new net.minecraft.util.ResourceLocation(goal));
        bot.inventory.markDirty();
    }
    @Override public void readFromNBT(NBTTagCompound n){parked=n.getCompoundTag("parked");recruited=n.getCompoundTag("recruited");}
    @Override public NBTTagCompound writeToNBT(NBTTagCompound n){n.setTag("parked",parked);n.setTag("recruited",recruited);return n;}
}
