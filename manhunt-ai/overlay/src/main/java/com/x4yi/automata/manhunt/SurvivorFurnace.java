package com.x4yi.automata.manhunt;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import com.x4yi.automata.movement.CellClassifier;

/** Vanilla furnace production using a station built by this agent during its current session.
 *  Never accelerates furnace ticks or fabricates output. */
final class SurvivorFurnace {
    private final SurvivorPlayer bot;
    private BlockPos station, destination;
    private TileEntityFurnace owned;
    private Item goal;
    private ItemStack expected=ItemStack.EMPTY;
    private boolean running;
    private int nextCheck;
    private String status="idle";
    SurvivorFurnace(SurvivorPlayer bot){this.bot=bot;}
    String status(){return status;}
    boolean active(){return goal!=null||running;}
    BlockPos destination(){return destination;}
    boolean request(ResourceLocation id){
        Item item=ForgeRegistries.ITEMS.getValue(id);
        if(item==null||item==Items.AIR||active())return false;
        boolean valid=false;
        for(int i=0;i<36;i++){
            ItemStack s=bot.inventory.getStackInSlot(i);
            if(!s.isEmpty()&&s.getItem()==item&&!FurnaceRecipes.instance().getSmeltingResult(s).isEmpty()){valid=true;break;}
        }
        if(!valid)return false;
        goal=item;status="queued";return true;
    }
    void placed(BlockPos pos){
        if(station!=null&&owned!=null&&!owned.isInvalid())return;
        if(!bot.world.isBlockLoaded(pos))return;
        if(bot.world.getBlockState(pos).getBlock()==Blocks.FURNACE&&bot.world.getTileEntity(pos) instanceof TileEntityFurnace furnace){
            station=pos.toImmutable();owned=furnace;
        }
    }
    private boolean validStation(){
        return station!=null&&bot.world.isBlockLoaded(station)&&owned!=null&&!owned.isInvalid()&&bot.world.getTileEntity(station)==owned
            &&SurvivorPermissions.allowed(bot,station,true);
    }
    private boolean emptyStation(){return owned.getStackInSlot(0).isEmpty()&&owned.getStackInSlot(1).isEmpty()&&owned.getStackInSlot(2).isEmpty();}
    private void stop(String reason){goal=null;running=false;expected=ItemStack.EMPTY;destination=null;status=reason;}
    private BlockPos adjacent(BlockPos p){
        BlockPos best=null;double d=Double.MAX_VALUE;
        for(EnumFacing face:EnumFacing.HORIZONTALS){
            BlockPos q=p.offset(face);
            if(!bot.world.isBlockLoaded(q)||!bot.world.isBlockLoaded(q.up())||!bot.world.isBlockLoaded(q.down())
                ||!CellClassifier.isPassable(bot.world,q)||!CellClassifier.isPassable(bot.world,q.up())||!CellClassifier.isSolidSupport(bot.world,q.down()))continue;
            double v=bot.getDistanceSqToCenter(q);if(v<d){d=v;best=q;}
        }
        return best;
    }
    boolean queueStation(){
        if(validStation())return true;
        Item item=Item.getItemFromBlock(Blocks.FURNACE);
        boolean found=false;
        for(int i=0;i<36;i++)if(bot.inventory.getStackInSlot(i).getItem()==item&&!bot.inventory.getStackInSlot(i).isEmpty()){found=true;break;}
        if(!found)return false;
        for(EnumFacing face:EnumFacing.HORIZONTALS){
            BlockPos pos=bot.homePosition().offset(face,3);
            if(!bot.world.isBlockLoaded(pos)||!bot.world.isBlockLoaded(pos.down())||!bot.world.isAirBlock(pos)||!CellClassifier.isSolidSupport(bot.world,pos.down()))continue;
            if(bot.requestBuild(pos,new ResourceLocation("minecraft:furnace")))return true;
        }
        return false;
    }
    void tick(int age){
        if(!active()||age<nextCheck)return;
        nextCheck=age+20;
        if(!validStation()){
            if(running){stop("station unavailable; contents left in world");return;}
            status="requires own furnace";queueStation();return;
        }
        destination=adjacent(station);
        if(destination==null){status="no access";return;}
        if(bot.getDistanceSqToCenter(station)>16)return;
        var hit=bot.world.rayTraceBlocks(bot.getPositionEyes(1F),new net.minecraft.util.math.Vec3d(station).add(.5,.5,.5),false,true,false);
        if(hit==null||!station.equals(hit.getBlockPos()))return;
        if(running){
            ItemStack out=owned.getStackInSlot(2);
            if(!out.isEmpty()){
                if(!owned.getStackInSlot(0).isEmpty()||!ItemStack.areItemsEqual(out,expected)||!ItemStack.areItemStackTagsEqual(out,expected)||out.getCount()!=expected.getCount()){stop("station contents changed; left untouched");return;}
                ItemStack transfer=out.copy();int before=transfer.getCount();
                bot.inventory.addItemStackToInventory(transfer);
                int moved=before-transfer.getCount();
                if(moved>0){owned.decrStackSize(2,moved);owned.markDirty();bot.inventory.markDirty();expected.shrink(moved);}
                if(expected.isEmpty()){stop("smelt complete");}
            }else if(owned.getStackInSlot(0).isEmpty()){stop("input missing");}
            return;
        }
        if(!emptyStation()){status="station not empty";return;}
        int input=-1,fuel=-1;
        for(int i=0;i<36;i++){
            ItemStack s=bot.inventory.getStackInSlot(i);
            if(s.isEmpty())continue;
            if(input<0&&s.getItem()==goal&&!FurnaceRecipes.instance().getSmeltingResult(s).isEmpty())input=i;
            if(fuel<0&&s.getItem()==Items.COAL&&TileEntityFurnace.isItemFuel(s))fuel=i;
        }
        if(input<0||fuel<0||input==fuel){status="needs input and coal";return;}
        ItemStack source=bot.inventory.getStackInSlot(input),coal=bot.inventory.getStackInSlot(fuel);
        expected=FurnaceRecipes.instance().getSmeltingResult(source).copy();
        ItemStack one=source.splitStack(1),oneFuel=coal.splitStack(1);
        owned.setInventorySlotContents(0,one);owned.setInventorySlotContents(1,oneFuel);
        owned.markDirty();bot.inventory.markDirty();running=true;status="smelting";
    }
}
