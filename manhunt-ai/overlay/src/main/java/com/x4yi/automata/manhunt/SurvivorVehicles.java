package com.x4yi.automata.manhunt;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import com.x4yi.automata.AutomataMod;

/** Real MCHeli drafting-table recipes; no arbitrary vehicle spawning. */
final class SurvivorVehicles {
    private final SurvivorPlayer bot;
    private final SurvivorTechnology recipes;
    private static final ResourceLocation TABLE=new ResourceLocation("mcheli:drafting_table");
    private ResourceLocation goal;
    private BlockPos table,approach;
    private int next;
    private boolean disabled;
    private String status="idle";
    SurvivorVehicles(SurvivorPlayer bot){this.bot=bot;recipes=new SurvivorTechnology(bot);}
    String status(){return status;}
    BlockPos destination(){return approach;}
    static boolean aircraft(ItemStack s){
        if(s.isEmpty())return false;
        for(Class<?> c=s.getItem().getClass();c!=null;c=c.getSuperclass())if(c.getName().equals("com.norwood.mcheli.aircraft.MCH_ItemAircraft"))return true;
        return false;
    }
    boolean request(ResourceLocation id){
        IRecipe r=ForgeRegistries.RECIPES.getValue(id);
        if(r==null||!aircraft(r.getRecipeOutput())||r.isDynamic())return false;
        goal=id;status="queued "+id;return true;
    }
    void placed(BlockPos p){
        if(bot.world.isBlockLoaded(p)&&TABLE.equals(bot.world.getBlockState(p).getBlock().getRegistryName()))table=p.toImmutable();
    }
    boolean plan(int age){
        if(disabled||goal==null||!net.minecraftforge.fml.common.Loader.isModLoaded("mcheli")){approach=null;return false;}
        if(age<next)return false;
        next=age+100;
        if(table==null||!bot.world.isBlockLoaded(table)||!TABLE.equals(bot.world.getBlockState(table).getBlock().getRegistryName())){
            approach=null;boolean carried=false;
            for(int i=0;i<36;i++){ItemStack s=bot.inventory.getStackInSlot(i);if(!s.isEmpty()&&TABLE.equals(s.getItem().getRegistryName())){carried=true;break;}}
            if(!carried){status="requires drafting table";return recipes.produceOnce(TABLE,-1);}
            for(var f:net.minecraft.util.EnumFacing.HORIZONTALS){
                BlockPos p=bot.homePosition().offset(f,3);
                if(bot.world.isBlockLoaded(p)&&bot.world.isAirBlock(p)&&bot.world.isBlockLoaded(p.down())&&bot.world.getBlockState(p.down()).isFullCube()&&bot.requestBuild(p,TABLE))return true;
            }
            return false;
        }
        if(!SurvivorPermissions.interact(bot,table)){approach=null;return false;}
        if(bot.getDistanceSqToCenter(table)>16){approach=null;double best=Double.MAX_VALUE;for(var f:net.minecraft.util.EnumFacing.HORIZONTALS){BlockPos p=table.offset(f);if(SurvivorTerrain.stand(bot.world,p)&&bot.getDistanceSqToCenter(p)<best){approach=p;best=bot.getDistanceSqToCenter(p);}}return false;}
        approach=null;
        var hit=bot.world.rayTraceBlocks(bot.getPositionEyes(1),new Vec3d(table).add(.5,.5,.5),false,true,false);
        if(hit==null||!table.equals(hit.getBlockPos()))return false;
        IRecipe recipe=ForgeRegistries.RECIPES.getValue(goal);
        if(recipe==null||!aircraft(recipe.getRecipeOutput())){goal=null;return false;}
        Container container=null;
        try{
            Class<?> api=Class.forName("com.norwood.mcheli.helper.MCH_Recipes");
            if(!Boolean.TRUE.equals(api.getMethod("canCraft",EntityPlayer.class,IRecipe.class).invoke(null,bot,recipe))){status="missing vehicle materials";return false;}
            Class<?> cls=Class.forName("com.norwood.mcheli.block.MCH_DraftingTableGuiContainer");
            container=(Container)cls.getConstructor(EntityPlayer.class,int.class,int.class,int.class).newInstance(bot,table.getX(),table.getY(),table.getZ());
            if(!container.canInteractWith(bot))return false;
            cls.getMethod("createRecipeItem",IRecipe.class).invoke(container,recipe);
            int output=cls.getField("outputSlotIndex").getInt(container);
            if(!container.getSlot(output).getHasStack())return false;
            ItemStack result=container.transferStackInSlot(bot,output);
            if(!result.isEmpty()){status="vehicle crafted";goal=null;return true;}
            status="output could not be moved; normal container close";
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){disabled=true;AutomataMod.LOGGER.warn("AI MCHeli crafting disabled",e);}
        finally{if(container!=null)container.onContainerClosed(bot);}
        return false;
    }
}
