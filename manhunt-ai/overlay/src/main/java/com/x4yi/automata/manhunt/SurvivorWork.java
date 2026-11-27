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
    private int nextTech;
    private boolean enabled,shelterQueued;
    private int age,nextPlan,scanCursor,deadline;
    private BlockPos target,destination,bench;
    private IBlockState miningState;
    private float progress;
    private int stage=-1;
    private SurvivorProgression.Task task=SurvivorProgression.Task.IDLE;
    SurvivorWork(SurvivorPlayer bot,BlockPos home) {this.bot=bot;this.home=home;role=SurvivorProgression.role(bot.getUniqueID().hashCode());technology=new SurvivorTechnology(bot);}
    String status(){return role.name()+"/"+(enabled?task.name():"DISABLED")+(technology.active()?" goal="+technology.status():"");}
    boolean goal(net.minecraft.util.ResourceLocation id){return technology.setGoal(id);}
    void clearGoal(){technology.clear();}
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
        boolean knownBench=bench!=null && world().isBlockLoaded(bench)&&world().getBlockState(bench).getBlock()==Blocks.CRAFTING_TABLE;
        return new SurvivorProgression.Stock(food,count(Item.getItemFromBlock(Blocks.LOG),Item.getItemFromBlock(Blocks.LOG2)),
            count(Item.getItemFromBlock(Blocks.PLANKS)),count(Items.STICK),count(Item.getItemFromBlock(Blocks.COBBLESTONE)),
            knownBench||has(Item.getItemFromBlock(Blocks.CRAFTING_TABLE)),
            has(Items.WOODEN_PICKAXE,Items.STONE_PICKAXE,Items.IRON_PICKAXE,Items.DIAMOND_PICKAXE,Items.GOLDEN_PICKAXE),
            has(Items.STONE_PICKAXE,Items.IRON_PICKAXE,Items.DIAMOND_PICKAXE),has(Items.WOODEN_AXE,Items.STONE_AXE,Items.IRON_AXE,Items.DIAMOND_AXE),
            has(Items.WOODEN_SWORD,Items.STONE_SWORD,Items.IRON_SWORD,Items.DIAMOND_SWORD),blocks,shelterQueued);
    }
    void interrupt(){if(stage>=0)resetMining();}
    void clear(){resetMining();release();target=null;destination=null;scanCursor=0;task=SurvivorProgression.Task.IDLE;}
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
        return p.distanceSq(home)<=144&&world().isBlockLoaded(p)&&world().isBlockLoaded(p.up())&&world().isBlockLoaded(p.down())
            && CellClassifier.isPassable(world(),p)&&CellClassifier.isPassable(world(),p.up())&&CellClassifier.isSolidSupport(world(),p.down())
            && !world().getBlockState(p).getMaterial().isLiquid();
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
        if(!world().isBlockLoaded(p)||p.distanceSq(home)>144)return false;
        var block=world().getBlockState(p).getBlock();
        return kind==SurvivorProgression.Task.WOOD?(block==Blocks.LOG||block==Blocks.LOG2):
            kind==SurvivorProgression.Task.STONE?(block==Blocks.STONE||block==Blocks.COBBLESTONE):false;
    }
    private boolean selectResource(BlockPos p){
        if(!matches(p,task)||!visible(p)||!SurvivorPermissions.allowed(bot,p,false))return false;
        BlockPos stand=adjacent(p);
        if(stand==null||!reserve(p))return false;
        target=p.toImmutable();destination=stand;deadline=age+240;
        memory().note(bot.getUniqueID(),task==SurvivorProgression.Task.WOOD?"wood":"stone",target,world().getTotalWorldTime());
        return true;
    }
    private void findResource(){
        if(target!=null)return;
        if(scanCursor==0){
            BlockPos remembered=memory().recall(bot.getUniqueID(),task==SurvivorProgression.Task.WOOD?"wood":"stone",world().getTotalWorldTime(),12000);
            if(remembered!=null&&selectResource(remembered))return;
        }
        // 25x25x5 home volume, sampled in small slices; no chunk loads.
        for(int i=0;i<48;i++){
            int k=scanCursor++%3125;
            BlockPos p=home.add(k%25-12,k/625-2,(k/25)%25-12);
            if(selectResource(p))return;
        }
    }
    private boolean craft(Item output,boolean table){
        if(SurvivorCrafting.craftOutput(bot,output,table)){
            memory().count(bot.getUniqueID(),"crafted");return true;
        }
        return false;
    }
    private boolean ensureBench(){
        if(SurvivorCrafting.nearbyBench(bot)){destination=null;return true;}
        if(bench!=null && world().isBlockLoaded(bench)&&world().getBlockState(bench).getBlock()==Blocks.CRAFTING_TABLE){destination=adjacent(bench);return false;}
        BlockPos old=memory().recall(bot.getUniqueID(),"bench",world().getTotalWorldTime(),Long.MAX_VALUE);
        if(old!=null&&old.distanceSq(home)<=144&&world().isBlockLoaded(old)&&world().getBlockState(old).getBlock()==Blocks.CRAFTING_TABLE){bench=old;destination=adjacent(bench);return false;}
        if(!has(Item.getItemFromBlock(Blocks.CRAFTING_TABLE))){craft(Item.getItemFromBlock(Blocks.CRAFTING_TABLE),false);return false;}
        for(EnumFacing f:EnumFacing.HORIZONTALS){
            BlockPos p=home.offset(f,2);
            if(world().isBlockLoaded(p)&&world().isAirBlock(p)&&world().isBlockLoaded(p.down())&&CellClassifier.isSolidSupport(world(),p.down())&&SurvivorPermissions.allowed(bot,p,true)){
                if(bot.requestBuild(p,new net.minecraft.util.ResourceLocation("minecraft:crafting_table"))){bench=p;memory().note(bot.getUniqueID(),"bench",p,world().getTotalWorldTime());}
                return false;
            }
        }
        return false;
    }
    void plan(boolean stressed){
        age++;
        if(!enabled||stressed)return;
        if(target!=null && (age>=deadline||!matches(target,task))){clear();}
        if(target!=null)return;
        if(age<nextPlan)return;nextPlan=age+10;
        if(technology.active()&&age>=nextTech){nextTech=age+80;if(technology.step(SurvivorCrafting.nearbyBench(bot))){memory().count(bot.getUniqueID(),"crafted");return;}}
        SurvivorProgression.Task next=SurvivorProgression.choose(stock(),role);
        // If food is low, loot perception has priority; still allow basic tool progression.
        if(next==SurvivorProgression.Task.FOOD){
            SurvivorProgression.Stock s=stock();
            next=SurvivorProgression.choose(new SurvivorProgression.Stock(2,s.logs(),s.planks(),s.sticks(),s.stone(),s.bench(),s.pickaxe(),s.stonePickaxe(),s.axe(),s.sword(),s.blocks(),s.sheltered()),role);
        }
        if(next!=task){clear();task=next;}
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
    boolean act(){
        if(!enabled||target==null)return false;
        if(!matches(target,task)||!SurvivorPermissions.allowed(bot,target,false)||!reserve(target)){clear();return false;}
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
        bot.faceBlock(target);bot.moveForward=0;bot.moveStrafing=0;
        if(age%5==0)bot.swingArm(EnumHand.MAIN_HAND);
        float delta=state.getPlayerRelativeBlockHardness(bot,world(),target);
        if(delta<=0||!Float.isFinite(delta)){clear();return false;}
        progress=Math.min(1,progress+delta);
        if(progress>=1){
            BlockPos p=target;resetMining();
            if(bot.interactionManager.tryHarvestBlock(p))memory().count(bot.getUniqueID(),"mined");
            clear();nextPlan=age+10;return true;
        }
        int nextStage=Math.min(9,(int)(progress*10));
        if(stage!=nextStage){stage=nextStage;world().sendBlockBreakProgress(bot.getEntityId(),target,stage);}
        return true;
    }
}
