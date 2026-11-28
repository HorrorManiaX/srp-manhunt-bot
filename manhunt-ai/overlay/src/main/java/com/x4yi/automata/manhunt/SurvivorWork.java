package com.x4yi.automata.manhunt;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import com.x4yi.automata.movement.CellClassifier;
/** Opt-in bounded local resource work. World mutations run exclusively on server thread. */
final class SurvivorWork {
    private record Claim(java.util.UUID owner,long expires) {}
    private static final java.util.Map<WorldServer,java.util.Map<BlockPos,Claim>> reservations=new java.util.WeakHashMap<>();
    private final SurvivorPlayer bot;
    private final BlockPos home;
    private final SurvivorProgression.Role role;
    private final SurvivorTechnology technology;
    private int nextTech,nextDefenseMaterials,nextEquipment;
    private boolean industry;
    private int defenseDeficit;
    private net.minecraft.block.Block requestedOre;
    private boolean enabled,shelterQueued;
    private int age,nextPlan,scanCursor,deadline;
    private BlockPos target,destination,bench;
    private IBlockState miningState;
    private float progress;
    private int stage=-1;
    private BlockPos scanOrigin, expedition;
    private int expeditionDeadline, expeditionIndex, failedScans, nextExpedition;
    private String searchStatus="idle";
    private BlockPos failedRouteGoal;
    private int failedRouteCount;
    void routeFailed(BlockPos p){
        if(p==null||destination==null||!p.equals(destination))return;
        if(!p.equals(failedRouteGoal)){failedRouteGoal=p;failedRouteCount=0;}
        if(++failedRouteCount<3)return;
        reject(p);reject(target);reject(expedition);reject(salvage);clear();failedRouteGoal=null;failedRouteCount=0;searchStatus="route rejected";
    }
    private BlockPos salvage;
    private boolean naturalChest(BlockPos p){
        if(!world().isBlockLoaded(p)||rejected(p)||!(world().getTileEntity(p) instanceof net.minecraft.tileentity.TileEntityChest c)||c.isLocked())return false;
        return c.writeToNBT(new net.minecraft.nbt.NBTTagCompound()).hasKey("LootTable",8)&&SurvivorPermissions.interact(bot,p);
    }
    private boolean salvageChest(){
        if(salvage==null)return false;
        if(age>=deadline||!naturalChest(salvage)){reject(salvage);clear();return false;}
        if(bot.getDistanceSqToCenter(salvage)>16||!visible(salvage))return false;
        var c=(net.minecraft.tileentity.TileEntityChest)world().getTileEntity(salvage);
        if(c==null||!c.isUsableByPlayer(bot)){reject(salvage);clear();return false;}
        c.fillWithLoot(bot);
        int movedTotal=0;
        for(int i=0;i<c.getSizeInventory();i++){
            ItemStack source=c.getStackInSlot(i);if(source.isEmpty())continue;
            ItemStack offer=source.copy();int before=offer.getCount();bot.inventory.addItemStackToInventory(offer);int moved=before-offer.getCount();
            if(moved>0){c.decrStackSize(i,moved);movedTotal+=moved;}
        }
        if(movedTotal>0){c.markDirty();bot.inventory.markDirty();memory().count(bot.getUniqueID(),"loot");}
        reject(salvage);clear();return true;
    }
    private final java.util.LinkedHashMap<BlockPos,Integer> failedTargets=new java.util.LinkedHashMap<>();
    private static final int SCAN_SIZE=SurvivorExplorationPolicy.SCAN_SIZE;
    private boolean inRange(BlockPos p){return SurvivorExplorationPolicy.within(p.distanceSq(home));}
    String searchStatus(){return searchStatus;}
    private void reject(BlockPos p){if(p==null)return;failedTargets.put(p.toImmutable(),age+2400);while(failedTargets.size()>64)failedTargets.remove(failedTargets.keySet().iterator().next());}
    private boolean rejected(BlockPos p){return failedTargets.getOrDefault(p,0)>age;}
    private SurvivorProgression.Task task=SurvivorProgression.Task.IDLE;
    SurvivorWork(SurvivorPlayer bot,BlockPos home) {this.bot=bot;this.home=home;role=SurvivorProgression.role(bot.getUniqueID().hashCode());technology=new SurvivorTechnology(bot);}
    String status(){return role.name()+"/"+(enabled?task.name():"DISABLED")+"/industry="+industry+(technology.active()?" goal="+technology.status():"");}
    boolean goal(net.minecraft.util.ResourceLocation id){return technology.setGoal(id);}
    void clearGoal(){technology.clear();}
    void industry(boolean value){industry=value;requestedOre=null;if(!value)clear();}
    SurvivorProgression.Role role(){return role;}
    boolean industryEnabled(){return industry;}
    boolean isEnabled(){return enabled;}
    String goalId(){return technology.goalId();}
    void enabled(boolean value){enabled=value;if(!value)clear();}
    BlockPos destination(){return enabled?destination:null;}
    private WorldServer world(){return (WorldServer)bot.world;}
    private SurvivorMemory memory(){return SurvivorMemory.get(world());}
    private int count(Item... items){
        int n=0;
        for(int i=0;i<36;i++){
            ItemStack stack=bot.inventory.getStackInSlot(i);
            for(Item item:items)if(!stack.isEmpty()&&stack.getItem()==item){n+=stack.getCount();break;}
        }
        return n;
    }
    private boolean has(Item... items){return count(items)>0;}
    private SurvivorProgression.Stock stock(){
        int food=0,blocks=0;
        for(int i=0;i<36;i++){
            ItemStack s=bot.inventory.getStackInSlot(i);
            if(s.isEmpty())continue;
            if(s.getItem() instanceof ItemFood)food+=s.getCount();
            if(s.getItem() instanceof ItemBlock b && b.getBlock()!=Blocks.CRAFTING_TABLE && b.getBlock().getDefaultState().isFullCube() && !b.getBlock().hasTileEntity(b.getBlock().getDefaultState()))blocks+=s.getCount();
        }
        boolean knownBench=bench!=null && bench.distanceSq(bot.getPosition())<40*40 && world().isBlockLoaded(bench)&&world().getBlockState(bench).getBlock()==Blocks.CRAFTING_TABLE;
        return new SurvivorProgression.Stock(food,count(Item.getItemFromBlock(Blocks.LOG),Item.getItemFromBlock(Blocks.LOG2)),
            count(Item.getItemFromBlock(Blocks.PLANKS)),count(Items.STICK),count(Item.getItemFromBlock(Blocks.COBBLESTONE)),
            knownBench||has(Item.getItemFromBlock(Blocks.CRAFTING_TABLE)),
            has(Items.WOODEN_PICKAXE,Items.STONE_PICKAXE,Items.IRON_PICKAXE,Items.DIAMOND_PICKAXE,Items.GOLDEN_PICKAXE),
            has(Items.STONE_PICKAXE,Items.IRON_PICKAXE,Items.DIAMOND_PICKAXE),has(Items.WOODEN_AXE,Items.STONE_AXE,Items.IRON_AXE,Items.DIAMOND_AXE),
            has(Items.WOODEN_SWORD,Items.STONE_SWORD,Items.IRON_SWORD,Items.DIAMOND_SWORD),blocks,shelterQueued);
    }
    void interrupt(){if(stage>=0)resetMining();}
    void clear(){resetMining();release();target=null;destination=null;expedition=null;salvage=null;scanOrigin=null;scanCursor=0;task=SurvivorProgression.Task.IDLE;}
    private boolean reserve(BlockPos p){
        var map=reservations.computeIfAbsent(world(),w->new java.util.HashMap<>());
        long now=world().getTotalWorldTime();
        Claim old=map.get(p);
        if(old!=null&&old.expires()>now&&!old.owner().equals(bot.getUniqueID()))return false;
        if(map.size()>=128){map.entrySet().removeIf(e->e.getValue().expires()<=now);if(map.size()>=128&&!map.containsKey(p))return false;}
        map.put(p.toImmutable(),new Claim(bot.getUniqueID(),now+300));return true;
    }
    private void release(){
        if(target==null)return;
        var map=reservations.get(world());
        if(map!=null){Claim old=map.get(target);if(old!=null&&old.owner().equals(bot.getUniqueID()))map.remove(target);}
    }
    private void resetMining(){
        if(target!=null&&stage>=0)world().sendBlockBreakProgress(bot.getEntityId(),target,-1);
        progress=0;stage=-1;miningState=null;
    }
    private boolean standable(BlockPos p){
        return inRange(p)&&SurvivorTerrain.stand(world(),p);
    }
    private BlockPos adjacent(BlockPos p){
        BlockPos best=null;double d=Double.MAX_VALUE;
        for(EnumFacing face:EnumFacing.HORIZONTALS)for(int dy=-1;dy<=1;dy++){
            BlockPos q=p.offset(face).up(dy);
            if(standable(q)&&bot.getDistanceSqToCenter(q)<d){best=q;d=bot.getDistanceSqToCenter(q);}
        }
        return best;
    }
    private boolean visible(BlockPos p){
        var hit=world().rayTraceBlocks(bot.getPositionEyes(1F),new Vec3d(p).add(.5,.5,.5),false,true,false);
        return hit!=null&&p.equals(hit.getBlockPos());
    }
    private boolean matches(BlockPos p,SurvivorProgression.Task kind){
        if(!world().isBlockLoaded(p)||!inRange(p))return false;
        var block=world().getBlockState(p).getBlock();
        if(requestedOre!=null)return block==requestedOre;
        return kind==SurvivorProgression.Task.WOOD?(block==Blocks.LOG||block==Blocks.LOG2||block==Blocks.PLANKS):
            kind==SurvivorProgression.Task.STONE?(block==Blocks.STONE||block==Blocks.COBBLESTONE):false;
    }
    private boolean selectResource(BlockPos p){
        if(rejected(p)||!matches(p,task)||!SurvivorPermissions.allowed(bot,p,false))return false;
        if(!visible(p)&&p.distanceSq(bot.getPosition())>12*12)return false;
        // Salvage city planks, not the immediate camp structure or a player claim.
        if(world().getBlockState(p).getBlock()==Blocks.PLANKS&&p.distanceSq(home)<4)return false;
        BlockPos stand=adjacent(p);
        if(stand==null||rejected(stand)||!reserve(p))return false;
        target=p.toImmutable();destination=stand;deadline=age+600;expedition=null;failedScans=0;searchStatus="mining target "+task;
        if(requestedOre==null)memory().note(bot.getUniqueID(),task==SurvivorProgression.Task.WOOD?"wood":"stone",target,world().getTotalWorldTime());
        return true;
    }
    private boolean findResource(){
        if(target!=null)return true;
        if(expedition!=null){
            if(bot.getDistanceSqToCenter(expedition)<9){expedition=null;destination=null;scanOrigin=null;scanCursor=0;memory().count(bot.getUniqueID(),"explored");}
            else if(age>=expeditionDeadline){reject(expedition);expedition=null;destination=null;scanOrigin=null;scanCursor=0;}
            else{destination=expedition;searchStatus="exploring "+task+" at "+expedition;return true;}
        }
        if(scanOrigin==null){scanOrigin=bot.getPosition();scanCursor=0;}
        if(standable(scanOrigin))destination=scanOrigin;
        if(scanCursor==0&&requestedOre==null){
            BlockPos remembered=memory().recall(bot.getUniqueID(),task==SurvivorProgression.Task.WOOD?"wood":"stone",world().getTotalWorldTime(),12000);
            if(remembered!=null&&remembered.distanceSq(bot.getPosition())<40*40&&selectResource(remembered))return true;
        }
        searchStatus="survey "+task+" "+scanCursor+"/"+SCAN_SIZE;
        long stop=System.nanoTime()+500_000L;
        for(int i=0;i<96&&scanCursor<SCAN_SIZE&&System.nanoTime()<stop;i++){
            int k=scanCursor++;
            int[] offset=SurvivorExplorationPolicy.scanOffset(k);
            BlockPos p=scanOrigin.add(offset[0],offset[1],offset[2]);
            if(selectResource(p))return true;
            if(world().isBlockLoaded(p)&&world().getBlockState(p).getBlock() instanceof net.minecraft.block.BlockChest&&visible(p)&&naturalChest(p)){
                BlockPos approach=adjacent(p);if(approach!=null){salvage=p.toImmutable();destination=approach;deadline=age+400;searchStatus="city loot";return true;}
            }
        }
        if(scanCursor<SCAN_SIZE)return true;
        failedScans++;
        if(age<nextExpedition)return true;
        nextExpedition=age+100;
        // Bounded, staged waypoints within the existing 48-block path planner.
        BlockPos here=bot.getPosition();
        for(int i=0;i<12;i++){
            int k=expeditionIndex++;
            int[] offset=SurvivorExplorationPolicy.waypoint(k);
            BlockPos column=here.add(offset[0],0,offset[1]);
            for(int dy:new int[]{0,1,-1,3,-3,5,-5}){
                BlockPos p=column.up(dy);
                if(rejected(p)||!standable(p)||!SurvivorPermissions.allowed(bot,p,true)||SurvivorTerrain.safeExits(world(),p)==0)continue;
                expedition=p;destination=p;expeditionDeadline=age+600;searchStatus="expedition "+task;return true;
            }
        }
        // No reachable loaded waypoint: retry from the current location, not one fixed home volume.
        scanOrigin=null;scanCursor=0;searchStatus="blocked "+task;return false;
    }
    private boolean craft(Item output,boolean table){
        if(SurvivorCrafting.craftOutput(bot,output,table)){
            memory().count(bot.getUniqueID(),"crafted");return true;
        }
        return false;
    }
    private boolean ensureBench(){
        if(SurvivorCrafting.nearbyBench(bot)){destination=null;return true;}
        if(bench!=null && bench.distanceSq(bot.getPosition())<40*40&&world().isBlockLoaded(bench)&&world().getBlockState(bench).getBlock()==Blocks.CRAFTING_TABLE){destination=adjacent(bench);return false;}
        BlockPos old=memory().recall(bot.getUniqueID(),"bench",world().getTotalWorldTime(),Long.MAX_VALUE);
        if(old!=null&&old.distanceSq(bot.getPosition())<40*40&&world().isBlockLoaded(old)&&world().getBlockState(old).getBlock()==Blocks.CRAFTING_TABLE){bench=old;destination=adjacent(bench);return false;}
        if(!has(Item.getItemFromBlock(Blocks.CRAFTING_TABLE))){craft(Item.getItemFromBlock(Blocks.CRAFTING_TABLE),false);return false;}
        for(EnumFacing f:EnumFacing.HORIZONTALS){
            BlockPos p=bot.getPosition().offset(f,2);
            if(world().isBlockLoaded(p)&&world().isAirBlock(p)&&world().isBlockLoaded(p.down())&&CellClassifier.isSolidSupport(world(),p.down())&&SurvivorPermissions.allowed(bot,p,true)){
                if(bot.requestFieldBench(p)){bench=p;memory().note(bot.getUniqueID(),"bench",p,world().getTotalWorldTime());}
                return false;
            }
        }
        return false;
    }
    void plan(boolean stressed){
        age++;
        if(!enabled||stressed)return;
        if(salvage!=null){if(age>=deadline||!naturalChest(salvage)){reject(salvage);clear();}else return;}
        if(target!=null && (age>=deadline||!matches(target,task))){if(age>=deadline)reject(target);clear();}
        if(target!=null)return;
        if(age<nextPlan)return;nextPlan=age+10;
        if(expedition!=null&&findResource())return;
        if(bot.seasonWork(age))return;
        if(age>=nextEquipment){
            nextEquipment=age+100;
            if(SurvivorEquipment.improve(bot)){memory().count(bot.getUniqueID(),"crafted");return;}
        }
        if(age>=nextDefenseMaterials){
            nextDefenseMaterials=age+200;
            defenseDeficit=SurvivorDefense.neededBlocks(bot.missingWallBlocks(),bot.constructionStock());
        }
        if(technology.active()&&age>=nextTech){nextTech=age+80;if(technology.step(SurvivorCrafting.nearbyBench(bot))){memory().count(bot.getUniqueID(),"crafted");return;}}
        if(defenseDeficit>0 && has(Items.STONE_PICKAXE,Items.IRON_PICKAXE,Items.DIAMOND_PICKAXE)&&count(Item.getItemFromBlock(Blocks.COBBLESTONE))<Math.min(32,defenseDeficit+4)){
            if(task!=SurvivorProgression.Task.STONE||requestedOre!=null){clear();requestedOre=null;task=SurvivorProgression.Task.STONE;}
            if(findResource())return;
            defenseDeficit=0;scanCursor=0;
        }
        if(industry&&has(Items.STONE_PICKAXE,Items.IRON_PICKAXE,Items.DIAMOND_PICKAXE)){
            if(industrialStep())return;
        }
        SurvivorProgression.Task next=SurvivorProgression.choose(stock(),role);
        // If food is low, loot perception has priority; still allow basic tool progression.
        if(next==SurvivorProgression.Task.FOOD){
            SurvivorProgression.Stock s=stock();
            next=SurvivorProgression.choose(new SurvivorProgression.Stock(2,s.logs(),s.planks(),s.sticks(),s.stone(),s.bench(),s.pickaxe(),s.stonePickaxe(),s.axe(),s.sword(),s.blocks(),s.sheltered()),role);
        }
        if(next!=task || requestedOre!=null){clear();requestedOre=null;task=next;}
        switch(task){
            case WOOD,STONE -> findResource();
            case PLANKS -> craft(Item.getItemFromBlock(Blocks.PLANKS),false);
            case STICKS -> craft(Items.STICK,false);
            case BENCH -> craft(Item.getItemFromBlock(Blocks.CRAFTING_TABLE),false);
            case PICKAXE -> {if(ensureBench())craft(Items.WOODEN_PICKAXE,true);}
            case STONE_PICKAXE -> {if(ensureBench())craft(Items.STONE_PICKAXE,true);}
            case AXE -> {if(ensureBench())craft(Items.STONE_AXE,true);}
            case SWORD -> {if(ensureBench()&&!craft(Items.STONE_SWORD,true))craft(Items.WOODEN_SWORD,true);}
            case SHELTER -> {bot.fortify();shelterQueued=true;}
            default -> {destination=null;}
        }
    }
    private boolean industrialStep(){
        boolean ironTool=has(Items.IRON_PICKAXE,Items.DIAMOND_PICKAXE);
        if(ironTool&&!bot.seasonNeedsIron()&&(!SurvivorEquipment.needsIron(bot)||count(Items.IRON_INGOT)>=12))return false;
        Item ore=Item.getItemFromBlock(Blocks.IRON_ORE),furnace=Item.getItemFromBlock(Blocks.FURNACE);
        if(!ironTool&&count(Items.IRON_INGOT)>=3){
            if(count(Items.STICK)<2){if(!craft(Items.STICK,false))return false;return true;}
            if(ensureBench())craft(Items.IRON_PICKAXE,true);
            return true;
        }
        if(bot.furnaceBusy())return true;
        if(!bot.hasFurnaceStation()){
            if(has(furnace))return bot.furnaceStation();
            if(count(Item.getItemFromBlock(Blocks.COBBLESTONE))>=8){
                if(ensureBench())craft(furnace,true);
                return true;
            }
            return false;
        }
        boolean fuel=false;
        for(int i=0;i<36;i++)if(SurvivorFurnace.usableFuel(bot.inventory.getStackInSlot(i))){fuel=true;break;}
        if(!fuel)return mineOre(Blocks.COAL_ORE);
        if(!has(ore))return mineOre(Blocks.IRON_ORE);
        return bot.furnaceBusy()||bot.smelt(ore.getRegistryName());
    }
    private boolean mineOre(net.minecraft.block.Block ore){
        if(requestedOre!=ore){clear();requestedOre=ore;}
        task=SurvivorProgression.Task.STONE;return findResource();
    }
    boolean act(){
        if(!enabled)return false;
        if(salvage!=null)return salvageChest();
        if(target==null)return false;
        if(!matches(target,task)||!SurvivorPermissions.allowed(bot,target,false)||!reserve(target)){reject(target);clear();return false;}
        if(bot.getDistanceSqToCenter(target)>16||!visible(target)){resetMining();return false;}
        if(!bot.onGround&&!bot.isInWater())return false;
        IBlockState state=world().getBlockState(target);
        if(state.getBlock().hasTileEntity(state)||state.getBlockHardness(world(),target)<0){clear();return false;}
        if(miningState!=null&&!miningState.equals(state))resetMining();
        miningState=state;
        int best=-1;float speed=1;
        for(int i=0;i<36;i++){
            ItemStack s=bot.inventory.getStackInSlot(i);
            if(!s.isEmpty()&&s.getDestroySpeed(state)>speed){speed=s.getDestroySpeed(state);best=i;}
        }
        if(best>=0)bot.equipInventorySlot(best);
        else if(task==SurvivorProgression.Task.STONE){clear();return false;}
        if(task==SurvivorProgression.Task.STONE&&!state.getBlock().canHarvestBlock(world(),target,bot)){reject(target);clear();return false;}
        bot.faceBlock(target);bot.moveForward=0;bot.moveStrafing=0;
        if(age%5==0)bot.swingArm(EnumHand.MAIN_HAND);
        float delta=state.getPlayerRelativeBlockHardness(bot,world(),target);
        if(delta<=0||!Float.isFinite(delta)){reject(target);clear();return false;}
        progress=Math.min(1,progress+delta);
        if(progress>=1){
            BlockPos p=target;resetMining();
            if(bot.interactionManager.tryHarvestBlock(p))memory().count(bot.getUniqueID(),"mined");else reject(p);
            clear();nextPlan=age+10;return true;
        }
        int nextStage=Math.min(9,(int)(progress*10));
        if(stage!=nextStage){stage=nextStage;world().sendBlockBreakProgress(bot.getEntityId(),target,stage);}
        return true;
    }
}
