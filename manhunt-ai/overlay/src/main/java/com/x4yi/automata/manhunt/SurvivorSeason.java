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
    private boolean recipe(String id){
        return SurvivorCrafting.craft(bot,new ResourceLocation(id),true);
    }
    boolean plan(int age){
        if(age<next)return false;next=age+100;
        if(!net.minecraftforge.fml.common.Loader.isModLoaded("srparasites"))return false;
        if(count(SPECIMEN,-1)>0){status="final specimen ready";return false;}
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
