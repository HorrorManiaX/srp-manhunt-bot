package com.x4yi.automata.manhunt;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
/** One real craft per plan; no machine recipes, no fake items, no unbounded search. */
final class SurvivorTechnology {
    private static final Set<String> ALLOWED=Set.of("minecraft","thermalfoundation","thermalexpansion","thermaldynamics","openmodularturrets","modularwarfare");
    private static final int MAX_DEPTH=5,MAX_NODES=32;
    private final SurvivorPlayer bot;
    private Item goal;
    private String missing="";
    SurvivorTechnology(SurvivorPlayer bot){this.bot=bot;}
    boolean setGoal(ResourceLocation id){
        Item item=ForgeRegistries.ITEMS.getValue(id);
        if(item==null||item==net.minecraft.init.Items.AIR||!ALLOWED.contains(id.getNamespace()))return false;
        goal=item;missing="";return true;
    }
    void clear(){goal=null;missing="";}
    String status(){return goal==null?"":goal.getRegistryName()+(missing.isEmpty()?"":" needs "+missing);}
    private int count(Item item){
        int n=0;for(int i=0;i<36;i++){ItemStack s=bot.inventory.getStackInSlot(i);if(!s.isEmpty()&&s.getItem()==item)n+=s.getCount();}return n;
    }
    boolean active(){return goal!=null;}
    boolean step(boolean table){
        if(goal==null)return false;
        if(count(goal)>0){clear();return false;}
        missing="";
        boolean progress=produce(goal,table,0,new int[]{MAX_NODES},new HashSet<>());
        if(count(goal)>0)clear();
        return progress;
    }
    private boolean produce(Item item,boolean table,int depth,int[] budget,Set<Item> chain){
        ResourceLocation itemId=item.getRegistryName();
        if(depth>MAX_DEPTH||budget[0]--<=0||itemId==null||!ALLOWED.contains(itemId.getNamespace())||!chain.add(item))return false;
        try{
            int examined=0;
            for(ResourceLocation recipeId:SurvivorCrafting.recipesFor(item)){
                if(++examined>12||budget[0]--<=0)break;
                if(!ALLOWED.contains(recipeId.getNamespace()))continue;
                IRecipe recipe=ForgeRegistries.RECIPES.getValue(recipeId);
                int size=table?3:2;
                if(recipe==null||recipe.isDynamic()||!recipe.canFit(size,size)||recipe.getIngredients().size()>size*size)continue;
                if(SurvivorCrafting.craft(bot,recipeId,table))return true;
                int[] free=new int[36];
                for(int i=0;i<36;i++)free[i]=bot.inventory.getStackInSlot(i).getCount();
                for(Ingredient ingredient:recipe.getIngredients()){
                    if(ingredient==Ingredient.EMPTY)continue;
                    int slot=-1;
                    for(int i=0;i<36;i++)if(free[i]>0&&ingredient.apply(bot.inventory.getStackInSlot(i))){slot=i;break;}
                    if(slot>=0){free[slot]--;continue;}
                    int variants=0;
                    for(ItemStack candidate:ingredient.getMatchingStacks()){
                        if(++variants>6||budget[0]<=0)break;
                        if(candidate.isEmpty()||candidate.getItem()==item)continue;
                        missing=String.valueOf(candidate.getItem().getRegistryName());
                        if(produce(candidate.getItem(),table,depth+1,budget,chain))return true;
                    }
                }
            }
            if(missing.isEmpty())missing=itemId.toString();
            return false;
        }finally{chain.remove(item);}
    }
}
