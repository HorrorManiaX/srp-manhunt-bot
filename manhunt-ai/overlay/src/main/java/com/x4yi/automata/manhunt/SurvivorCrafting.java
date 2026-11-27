package com.x4yi.automata.manhunt;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
/** Bounded, inventory-only crafting. Only real registry recipes. */
final class SurvivorCrafting {
    private static List<ResourceLocation> basics;
    private static final java.util.Map<Item,List<ResourceLocation>> recipes=new java.util.HashMap<>();
    private static boolean indexed;
    static List<ResourceLocation> recipesFor(Item item){index();return recipes.getOrDefault(item,java.util.Collections.emptyList());}
    private static void index() {
        if(indexed)return;
        for(IRecipe r:ForgeRegistries.RECIPES.getValuesCollection()){
            ItemStack out=r.getRecipeOutput();
            if(!r.isDynamic() && !out.isEmpty() && r.getRegistryName()!=null)
                recipes.computeIfAbsent(out.getItem(),k->new ArrayList<>()).add(r.getRegistryName());
        }
        indexed=true;
    }
    static boolean craftOutput(SurvivorPlayer bot,Item output, boolean table) {
        index();
        int attempts=0;
        for(ResourceLocation id:recipes.getOrDefault(output,java.util.Collections.emptyList())) {
            // The output item is explicitly requested; GroovyScript may replace vanilla recipe IDs.
            if(++attempts>16)break;
            if(craft(bot,id,table))return true;
        }
        return false;
    }
    static boolean nearbyBench(SurvivorPlayer bot) {
        net.minecraft.util.math.BlockPos center=bot.getPosition();
        for(net.minecraft.util.math.BlockPos p:net.minecraft.util.math.BlockPos.getAllInBoxMutable(center.add(-3,-1,-3),center.add(3,2,3))) {
            if(!bot.world.isBlockLoaded(p) || bot.getDistanceSqToCenter(p)>16 || bot.world.getBlockState(p).getBlock()!=Blocks.CRAFTING_TABLE)continue;
            var hit=bot.world.rayTraceBlocks(bot.getPositionEyes(1F),new net.minecraft.util.math.Vec3d(p).add(.5,.5,.5),false,true,false);
            if(hit!=null&&p.equals(hit.getBlockPos())&&SurvivorPermissions.allowed(bot,p,true))return true;
        }
        return false;
    }
    static void autoBasic(SurvivorPlayer bot) {
        if (basics == null) {
            basics = new ArrayList<>();
            for (IRecipe r : ForgeRegistries.RECIPES.getValuesCollection()) {
                ItemStack out = r.getRecipeOutput();
                ResourceLocation key = r.getRegistryName();
                if (key != null && "minecraft".equals(key.getNamespace()) && !out.isEmpty() && (out.getItem() == Item.getItemFromBlock(Blocks.PLANKS) || out.getItem() == Items.STICK)) basics.add(key);
            }
        }
        int planks=0, sticks=0;
        for(int i=0;i<36;i++) {
            ItemStack s=bot.inventory.getStackInSlot(i);
            if(s.getItem()==Item.getItemFromBlock(Blocks.PLANKS)) planks+=s.getCount();
            if(s.getItem()==Items.STICK) sticks+=s.getCount();
        }
        int attempts=0;
        for(ResourceLocation id : basics) {
            IRecipe r=ForgeRegistries.RECIPES.getValue(id);
            if(r==null) continue;
            Item output=r.getRecipeOutput().getItem();
            if((output==Items.STICK && (sticks>=4 || planks<8)) || (output==Item.getItemFromBlock(Blocks.PLANKS) && planks>=16)) continue;
            if(++attempts>8 || craft(bot,id)) break;
        }
    }
    static boolean craft(SurvivorPlayer bot, ResourceLocation id) {
        return craft(bot,id,false);
    }
    static boolean craft(SurvivorPlayer bot,ResourceLocation id,boolean table) {
        IRecipe r = ForgeRegistries.RECIPES.getValue(id);
        if (r == null || r.isDynamic() || (SurvivorVehicles.aircraft(r.getRecipeOutput()) && !"srp_mcheli".equals(id.getNamespace()))) return false;
        List<Ingredient> ingredients = r.getIngredients();
        int size=table?3:2;
        int w = r instanceof IShapedRecipe shaped ? shaped.getRecipeWidth() : size;
        int h = r instanceof IShapedRecipe shaped ? shaped.getRecipeHeight() : size;
        if (w < 1 || h < 1 || w > size || h > size || ingredients.isEmpty() || ingredients.size() > size*size || ingredients.size() > w*h || !r.canFit(size,size)) return false;
        List<ItemStack> stock = new ArrayList<>();
        for (int i=0; i<36; i++) stock.add(bot.inventory.getStackInSlot(i).copy());
        InventoryCrafting grid = new InventoryCrafting(new Container() {
            @Override public boolean canInteractWith(EntityPlayer p) { return false; }
        }, size, size);
        int[] budget = { 512 };
        if (!assign(r, bot, ingredients, w, 0, stock, grid, budget)) return false;
        ItemStack output = r.getCraftingResult(grid);
        if (output.isEmpty() || !insert(stock, output.copy())) return false;
        for (ItemStack s : r.getRemainingItems(grid)) if (!s.isEmpty() && !insert(stock, s.copy())) return false;
        for (int i=0; i<36; i++) bot.inventory.setInventorySlotContents(i, stock.get(i));
        bot.inventory.markDirty();
        net.minecraftforge.fml.common.FMLCommonHandler.instance().firePlayerCraftingEvent(bot, output, grid);
        return true;
    }
    private static boolean assign(IRecipe r, SurvivorPlayer bot, List<Ingredient> in, int w, int i, List<ItemStack> stock, InventoryCrafting grid, int[] budget) {
        if (--budget[0] < 0) return false;
        if (i == in.size()) return r.matches(grid, bot.world);
        Ingredient ingredient = in.get(i);
        int slot = i/w*grid.getWidth() + i%w;
        if (ingredient == Ingredient.EMPTY) return assign(r, bot, in, w, i+1, stock, grid, budget);
        for (int j=0; j<stock.size(); j++) {
            ItemStack s=stock.get(j);
            if (s.isEmpty() || !ingredient.apply(s)) continue;
            ItemStack before=s.copy(); ItemStack one=s.copy(); one.setCount(1);
            grid.setInventorySlotContents(slot, one);
            s.shrink(1);
            if (assign(r, bot, in, w, i+1, stock, grid, budget)) return true;
            stock.set(j, before);
            grid.setInventorySlotContents(slot, ItemStack.EMPTY);
        }
        return false;
    }
    private static boolean insert(List<ItemStack> stock, ItemStack s) {
        for (int i=0; i<stock.size() && !s.isEmpty(); i++) {
            ItemStack old=stock.get(i);
            if (!old.isEmpty() && ItemStack.areItemsEqual(old,s) && ItemStack.areItemStackTagsEqual(old,s)) {
                int n=Math.min(s.getCount(), Math.max(0, Math.min(64,old.getMaxStackSize())-old.getCount()));
                old.grow(n); s.shrink(n);
            }
        }
        for (int i=0; i<stock.size() && !s.isEmpty(); i++) if(stock.get(i).isEmpty()) {
            int n=Math.min(s.getCount(), Math.min(64,s.getMaxStackSize()));
            ItemStack copy=s.copy(); copy.setCount(n); stock.set(i,copy); s.shrink(n);
        }
        return s.isEmpty();
    }
}
