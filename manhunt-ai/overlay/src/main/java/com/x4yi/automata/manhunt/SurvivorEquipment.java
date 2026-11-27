package com.x4yi.automata.manhunt;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
/** Real workbench recipes only; prioritize protection and weapon readiness. */
final class SurvivorEquipment {
    private SurvivorEquipment(){}
    private static java.util.List<Item> gunRecipes;
    private static int cursor;
    private static boolean has(SurvivorPlayer bot,Item item){
        for(int i=0;i<36;i++)if(bot.inventory.getStackInSlot(i).getItem()==item&&!bot.inventory.getStackInSlot(i).isEmpty())return true;
        return false;
    }
    static boolean improve(SurvivorPlayer bot){
        if(!SurvivorCrafting.nearbyBench(bot))return false;
        if(bot.getHeldItemOffhand().isEmpty()&&!has(bot,Items.SHIELD)&&SurvivorCrafting.craftOutput(bot,Items.SHIELD,true))return true;
        Item[] iron={Items.IRON_CHESTPLATE,Items.IRON_LEGGINGS,Items.IRON_HELMET,Items.IRON_BOOTS};
        Item[] leather={Items.LEATHER_CHESTPLATE,Items.LEATHER_LEGGINGS,Items.LEATHER_HELMET,Items.LEATHER_BOOTS};
        for(Item[] tier:new Item[][]{iron,leather})for(Item item:tier){
            ItemArmor armor=(ItemArmor)item;
            ItemStack worn=bot.getItemStackFromSlot(armor.armorType);
            if(has(bot,item)||(!worn.isEmpty()&&(!(worn.getItem() instanceof ItemArmor old)||old.damageReduceAmount>=armor.damageReduceAmount)&&worn.getItemDamage()<worn.getMaxDamage()*.85))continue;
            if(SurvivorCrafting.craftOutput(bot,item,true))return true;
        }
        if(!has(bot,Items.IRON_SWORD)&&!has(bot,Items.DIAMOND_SWORD)&&SurvivorCrafting.craftOutput(bot,Items.IRON_SWORD,true))return true;
        if(!has(bot,Items.BOW)&&SurvivorCrafting.craftOutput(bot,Items.BOW,true))return true;
        int arrows=0;for(int i=0;i<36;i++){ItemStack s=bot.inventory.getStackInSlot(i);if(s.getItem()==Items.ARROW)arrows+=s.getCount();}
        if(arrows<24&&SurvivorCrafting.craftOutput(bot,Items.ARROW,true))return true;
        if(bot.role()==SurvivorProgression.Role.ENGINEER||bot.role()==SurvivorProgression.Role.GUARD){
            if(gunRecipes==null){
                gunRecipes=new java.util.ArrayList<>();
                for(var r:net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.getValuesCollection()){
                    ItemStack output=r.getRecipeOutput();
                    if(SurvivorWeapons.isGun(output)&&!r.isDynamic()&&!gunRecipes.contains(output.getItem()))gunRecipes.add(output.getItem());
                }
            }
            boolean hasGun=false;
            for(int i=0;i<36;i++)if(SurvivorWeapons.isGun(bot.inventory.getStackInSlot(i))){hasGun=true;break;}
            if(!hasGun&&!gunRecipes.isEmpty())for(int i=0;i<Math.min(3,gunRecipes.size());i++){
                Item item=gunRecipes.get(Math.floorMod(cursor++,gunRecipes.size()));
                for(var id:SurvivorCrafting.recipesFor(item))if(SurvivorCrafting.craft(bot,id,true))return true;
            }
        }
        return false;
    }
}
