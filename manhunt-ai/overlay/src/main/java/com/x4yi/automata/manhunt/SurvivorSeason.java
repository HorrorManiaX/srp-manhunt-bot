package com.x4yi.automata.manhunt;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import com.x4yi.automata.AutomataMod;

/** Actual registered SRP and OverLast recipes, with metadata-specific lure tracking.
 * Never starts or changes the score of the global event. */
final class SurvivorSeason {
    private final SurvivorPlayer bot;
    private int next;
    private String status="collecting";
    private static final ResourceLocation LURE=new ResourceLocation("srparasites:evolutionlure"),SPECIMEN=new ResourceLocation("overlast:final_specimen");
    SurvivorSeason(SurvivorPlayer bot){this.bot=bot;}
    String status(){return status;}
    boolean needsIron(){return (count(SPECIMEN,-1)>0||bot.arenaSpecimen())&&count(new ResourceLocation("minecraft:iron_block"),-1)<9&&count(new ResourceLocation("minecraft:iron_ingot"),-1)<15;}
    static boolean useful(ItemStack s){
        if(s.isEmpty()||s.getItem().getRegistryName()==null)return false;
        String id=s.getItem().getRegistryName().toString();
        return id.startsWith("srparasites:lurecomponent")||id.equals("srparasites:evolutionlure")||id.equals("srparasites:biomeheart")||id.equals("srparasites:colonyheart")||id.equals("srparasites:biomepurifier")||id.equals("overlast:final_specimen")||id.equals("minecraft:nether_star")||id.equals("minecraft:dragon_egg")||id.startsWith("openmodularturrets:")||id.startsWith("mcheli:")||SurvivorWeapons.isGun(s);
    }
    int count(ResourceLocation id,int meta){
        int total=0;
        for(int i=0;i<36;i++){
            ItemStack s=bot.inventory.getStackInSlot(i);
            if(!s.isEmpty()&&id.equals(s.getItem().getRegistryName())&&(meta<0||meta==s.getMetadata()))total+=s.getCount();
        }
        return total;
    }
    static int progress(SurvivorPlayer bot){
        int mask=0,parts=0,stars=0,egg=0;
        for(int i=0;i<36;i++){
            ItemStack s=bot.inventory.getStackInSlot(i);if(s.isEmpty()||s.getItem().getRegistryName()==null)continue;
            String id=s.getItem().getRegistryName().toString();
            if(id.equals("overlast:final_specimen"))return 1000;
            if(id.equals("srparasites:evolutionlure")&&s.getMetadata()>=0&&s.getMetadata()<6)mask|=1<<s.getMetadata();
            if(id.startsWith("srparasites:lurecomponent"))parts+=Math.min(12,s.getCount());
            if(id.equals("minecraft:nether_star"))stars+=s.getCount();
            if(id.equals("minecraft:dragon_egg"))egg+=s.getCount();
        }
        return Integer.bitCount(mask)*20+Math.min(12,parts)+Math.min(2,stars)*8+Math.min(1,egg)*12;
    }
    static void pool(java.util.List<SurvivorPlayer> team){
        // Elect the most advanced nearby agent, transfer only real materials at close range.
        if(team.size()<2)return;
        for(SurvivorPlayer from:team){
            if(!from.autonomousEnabled()||!from.calmForSupply())continue;
            SurvivorPlayer to=null;int best=progress(from);
            for(SurvivorPlayer other:team){
                if(other==from||!other.autonomousEnabled()||!other.calmForSupply()||other.world!=from.world||from.getDistanceSq(other)>9||!from.canEntityBeSeen(other))continue;
                int value=progress(other);
                if(value>best||value==best&&(to==null?other.getName().compareTo(from.getName())<0:other.getName().compareTo(to.getName())<0)){best=value;to=other;}
            }
            if(to==null||best>=1000)continue;
            for(int i=0;i<36;i++){
                ItemStack source=from.inventory.getStackInSlot(i);
                if(source.isEmpty()||source.getItem().getRegistryName()==null||i==from.inventory.currentItem)continue;
                String id=source.getItem().getRegistryName().toString();
                if(!id.startsWith("srparasites:lurecomponent")&&!id.equals("srparasites:evolutionlure")&&!id.equals("minecraft:nether_star")&&!id.equals("minecraft:dragon_egg"))continue;
                ItemStack offer=source.copy();offer.setCount(Math.min(2,source.getCount()));int n=offer.getCount();
                to.inventory.addItemStackToInventory(offer);
                int moved=n-offer.getCount();
                if(moved<=0)continue;
                source.shrink(moved);from.inventory.markDirty();to.inventory.markDirty();
                for(var human:from.getServer().getPlayerList().getPlayers())if(human.world==from.world&&human.getDistanceSq(from)<=32*32&&!com.x4yi.automata.compat.ManhuntCompat.isHostile(human))
                    human.sendMessage(new net.minecraft.util.text.TextComponentString("[AI/отряд] "+from.getName()+": "+to.getName()+", на держи, это для финальной битвы."));
                return;
            }
        }
    }
    private boolean recipe(String id){
        return SurvivorCrafting.craft(bot,new ResourceLocation(id),true);
    }
    boolean plan(int age){
        if(age<next)return false;next=age+100;
        if(!net.minecraftforge.fml.common.Loader.isModLoaded("srparasites"))return false;
        if(count(SPECIMEN,-1)>0||bot.arenaSpecimen()){
            int iron=count(new ResourceLocation("minecraft:iron_block"),-1);
            int beacon=count(new ResourceLocation("minecraft:beacon"),-1);
            status="final specimen ready; arena blocks="+iron+"/9; beacon="+beacon+"/1";
            if(!SurvivorCrafting.nearbyBench(bot))return false;
            if(iron<9&&count(new ResourceLocation("minecraft:iron_ingot"),-1)>=15&&recipe("minecraft:iron_block"))return true;
            if(beacon<1&&count(new ResourceLocation("minecraft:nether_star"),-1)>0&&recipe("minecraft:beacon"))return true;
            return false;
        }
        int mask=0;
        for(int i=0;i<6;i++)if(count(LURE,i)>0)mask|=1<<i;
        int stars=count(new ResourceLocation("minecraft:nether_star"),-1),egg=count(new ResourceLocation("minecraft:dragon_egg"),-1);
        status="lures="+Integer.bitCount(mask)+"/6, stars="+stars+"/2, egg="+egg+"/1";
        if(!SurvivorCrafting.nearbyBench(bot))return false;
        // Reserve six different lures for the final specimen, rather than spending them to reduce evolution.
        for(int i=0;i<6;i++){
            if((mask&(1<<i))!=0)continue;
            if(recipe("srparasites:lureblock"+(i==0?"":Integer.toString(i+1))))return true;
        }
        if(mask==63&&stars>=2&&egg>=1&&recipe("overlast:final_specimen")){status="final specimen ready";return true;}
        // Purification recipes consume real hearts/purifiers. Keep a small stock for later use.
        if(count(new ResourceLocation("overlast:blockpurifier"),-1)<4)
            if(recipe("overlast:blockpurifier")||recipe("overlast:blockpurifier2"))return true;
        return false;
    }
}
