package com.x4yi.automata.manhunt;
import java.util.List;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;

/** Local, voluntary exchange between opted-in autonomous agents. No human inventory access. */
final class SurvivorSquad {
    private SurvivorSquad(){}
    static boolean sameTeam(SurvivorPlayer a,SurvivorPlayer b){
        return a!=b&&a.isEntityAlive()&&b.isEntityAlive()&&a.world==b.world
            &&a.homePosition().distanceSq(b.homePosition())<=64&&a.getDistanceSq(b)<=9
            &&a.autonomousEnabled()&&b.autonomousEnabled()&&a.calmForSupply()&&b.calmForSupply()
            &&a.canEntityBeSeen(b);
    }
    private static SurvivorSupplyPolicy.Resource category(ItemStack s){
        if(s.isEmpty())return null;
        Item item=s.getItem();
        if(item==Items.BREAD||item==Items.COOKED_BEEF||item==Items.COOKED_PORKCHOP||item==Items.COOKED_CHICKEN||item==Items.COOKED_MUTTON||item==Items.COOKED_RABBIT||item==Items.BAKED_POTATO)return SurvivorSupplyPolicy.Resource.FOOD;
        if(item==Item.getItemFromBlock(Blocks.PLANKS))return SurvivorSupplyPolicy.Resource.PLANKS;
        if(item==Item.getItemFromBlock(Blocks.COBBLESTONE))return SurvivorSupplyPolicy.Resource.COBBLESTONE;
        if(item==Items.COAL)return SurvivorSupplyPolicy.Resource.COAL;
        if(item==Items.IRON_INGOT)return SurvivorSupplyPolicy.Resource.IRON;
        return null;
    }
    private static int count(SurvivorPlayer p,SurvivorSupplyPolicy.Resource r){
        int n=0;
        for(int i=0;i<36;i++){ItemStack s=p.inventory.getStackInSlot(i);if(category(s)==r)n+=s.getCount();}
        return n;
    }
    static void coordinate(List<SurvivorPlayer> bots,int offset){
        if(bots.size()<2)return;
        // Rotate the starting worker; at most one transfer per 100 server ticks.
        for(int j=0;j<bots.size();j++){
            SurvivorPlayer to=bots.get(Math.floorMod(offset+j,bots.size()));
            for(SurvivorPlayer from:bots){
                if(!sameTeam(from,to))continue;
                for(var r:SurvivorSupplyPolicy.Resource.values()){
                    int amount=SurvivorSupplyPolicy.transfer(r,from.role(),to.role(),count(from,r),count(to,r));
                    if(amount<=0)continue;
                    for(int i=0;i<36;i++){
                        if(i==from.inventory.currentItem)continue;
                        ItemStack s=from.inventory.getStackInSlot(i);
                        if(category(s)!=r)continue;
                        int n=Math.min(amount,s.getCount());
                        ItemStack copy=s.copy();copy.setCount(n);
                        to.inventory.addItemStackToInventory(copy);
                        int moved=n-copy.getCount();
                        if(moved<=0)continue;
                        s.shrink(moved);from.inventory.markDirty();to.inventory.markDirty();
                        String name=s.getDisplayName();
                        String msg=SurvivorPersonality.share(from.personality(),moved,name,to.getName());
                        announce(from,msg);
                        announce(to,SurvivorPersonality.acknowledge(to.personality(),from.getName()));
                        return;
                    }
                }
            }
        }
    }
    private static void announce(SurvivorPlayer bot,String text){
        for(EntityPlayerMP p:bot.getServer().getPlayerList().getPlayers())
            if(p.world==bot.world&&p.getDistanceSq(bot)<=16*16&&!com.x4yi.automata.compat.ManhuntCompat.isHostile(p))
                p.sendMessage(new TextComponentString("[AI/отряд] "+bot.getName()+": "+text));
    }
}
