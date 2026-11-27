package com.x4yi.automata.manhunt;
import com.mojang.authlib.GameProfile;
import com.x4yi.automata.compat.ManhuntCompat;
import com.x4yi.automata.movement.CellClassifier;
import com.x4yi.automata.navigation.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumActionResult;
import com.x4yi.automata.ai.jobs.SrpTacticalClassifier;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
/** Experimental server-side player body; no remote account or real-player login. */
public final class SurvivorPlayer extends EntityPlayerMP {
    private final BlockPos home;
    private final it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap routeFailures=new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();
    private final SurvivorWork work;
    private final SurvivorFurnace furnace;
    private final SurvivorSeason season;
    private final SurvivorTurret autoTurret;
    private final SurvivorVehicles vehicles;
    private final SurvivorArena arena;
    private final SurvivorLavaTrap lavaTrap;
    private final SurvivorDefenseStation defenseStation;
    private final SurvivorFood foodRecovery;
    private int reloadUntil, nextReload;
    private AtomicBoolean cancelled = new AtomicBoolean();
    private CompletableFuture<PathResult> pending;
    private PathResult path = PathResult.EMPTY;
    private int step, age, nextPlan, nextAttack;
    private int nextScan, nextLootScan, nextInventoryScan, alertUntil, nextEvade;
    private boolean stressed;
    private BlockPos lastThreat, plannedGoal, escapeGoal;
    private int lastThreatTick, stuckTicks, explosionUntil, nextHazardScan;
    private int nextStrafe,strafeSide=1,lastDamageTick=-1000;
    private double lastX, lastZ;
    private net.minecraft.entity.item.EntityItem loot;
    private SurvivorBrain.Action previousAction = SurvivorBrain.Action.PATROL;
    private int gunSlot = -1, bowSlot = -1, foodSlot = -1;
    private BlockPos buildGoal,buildApproach;
    private int nextBuildApproach,placedBlocks,skippedBlocks,nextDefenseScan,closeEnemies;
    private net.minecraft.util.ResourceLocation buildItem;
    private int nextBuild, buildDeadline;
    private final java.util.ArrayDeque<BlockPos> fortification = new java.util.ArrayDeque<>();
    private int nextChat,victories,nextVictoryChat;
    private EntityLivingBase target, squadTarget;
    private int squadUntil,nextCombatAssessment,withdrawUntil,nextOrderReport;
    private float lastEnemyHealth=20,lastEnemyArmor;
    private SurvivorCombatPolicy.Order combatOrder=SurvivorCombatPolicy.Order.WITHDRAW;
    EntityLivingBase visibleEnemy(){return target!=null&&target.isEntityAlive()&&target.world==world&&ManhuntCompat.isHostile(target)&&canEntityBeSeen(target)?target:null;}
    double combatStrength(){
        if(!isEntityAlive()||dangerousHere()||getHealth()<getMaxHealth()*.5f||getFoodStats().getFoodLevel()<6)return 0;
        return SurvivorCombatPolicy.strength(getHealth(),getTotalArmorValue(),hasLoadedGun(),bestMelee()>=0);
    }
    boolean fightingSame(EntityLivingBase e){return target==e&&canEntityBeSeen(e)&&combatOrder!=SurvivorCombatPolicy.Order.WITHDRAW;}
    void squadContact(EntityLivingBase enemy,BlockPos observed){
        if(!isEntityAlive()||enemy==null||enemy.world!=world||!enemy.isEntityAlive()||!ManhuntCompat.isHostile(enemy)||getDistanceSqToCenter(observed)>32*32)return;
        if(canEntityBeSeen(enemy)){
            squadTarget=enemy;squadUntil=age+100;nextCombatAssessment=0;
            if(target==null){alert(enemy,observed);}
        }else{alert(null,observed);}
    }
    String combatOrderName(){return combatOrder.name();}
    private void assessCombat(boolean visible){
        if(target==null){combatOrder=SurvivorCombatPolicy.Order.WITHDRAW;nextCombatAssessment=0;return;}
        if(age<nextCombatAssessment)return;
        nextCombatAssessment=age+(stressed?12:4);
        double own=combatStrength(),allied=SurvivorService.INSTANCE.supportStrength(this,target);
        if(visible){lastEnemyHealth=target.getHealth();lastEnemyArmor=target.getTotalArmorValue();}
        double enemy=Math.max(1,lastEnemyHealth)*(1+Math.min(20,lastEnemyArmor)*.035)*SurvivorParasiteTactics.risk(target);
        boolean vagrant=target instanceof net.minecraft.entity.player.EntityPlayer;
        if(vagrant)enemy*=1.15;
        var next=SurvivorCombatPolicy.decide(getHealth(),getMaxHealth(),own,allied,enemy,Math.max(1,closeEnemies),hasLoadedGun(),dangerousHere(),vagrant);
        if(next==SurvivorCombatPolicy.Order.WITHDRAW&&combatOrder!=next)withdrawUntil=age+40;
        if(age<withdrawUntil&&next==SurvivorCombatPolicy.Order.ENGAGE)next=hasLoadedGun()?SurvivorCombatPolicy.Order.COVER:SurvivorCombatPolicy.Order.WITHDRAW;
        if(next!=combatOrder&&visible&&age>=nextOrderReport){nextOrderReport=age+160;SurvivorService.INSTANCE.reportOrder(this,next);}
        combatOrder=next;
    }
    private SurvivorPlayer helpCaller;
    private net.minecraft.entity.player.EntityPlayerMP humanCaller;
    private int humanHelpUntil;
    private boolean helpCombat;
    boolean hasLoadedGun(){return gunSlot>=0||bowSlot>=0;}
    int helpPriority(){
        if(dangerousHere()||getHealth()<getMaxHealth()*.4f)return 100;
        if(target!=null)return 85;
        if(getFoodStats().getFoodLevel()<8||findFood()<0)return 65;
        if(buildGoal!=null)return 35;
        return 10;
    }
    SurvivorHelpPolicy.Need helpNeed(){
        if(target!=null&&(getHealth()<getMaxHealth()*.45f||closeEnemies>=3))return SurvivorHelpPolicy.Need.EMERGENCY;
        if(target!=null&&(closeEnemies>=2||target.getHealth()>getMaxHealth()*2||combatOrder!=SurvivorCombatPolicy.Order.ENGAGE))return SurvivorHelpPolicy.Need.COMBAT;
        if(findFood()<0||getFoodStats().getFoodLevel()<8)return SurvivorHelpPolicy.Need.FOOD;
        if(constructionStock()<4&&missingWallBlocks()>0)return SurvivorHelpPolicy.Need.MATERIALS;
        return null;
    }
    int continuationPriority(SurvivorPlayer caller,boolean combat){
        if(combat&&caller==helpCaller&&target!=null&&target==caller.target&&getHealth()>=getMaxHealth()*.65f&&!dangerousHere()&&combatOrder!=SurvivorCombatPolicy.Order.WITHDRAW)return 10;
        return helpPriority();
    }
    boolean canHelpSafely(boolean combat){return !stressed&&!dangerousHere()&&getHealth()>=getMaxHealth()*.65f&&getFoodStats().getFoodLevel()>=8&&(combat||target==null);}
    void assist(SurvivorPlayer caller,boolean combat){
        if(helpCaller!=caller||helpCombat!=combat){helpCaller=caller;helpCombat=combat;invalidateRoute();}
        if(combat&&caller.target!=null&&caller.target.isEntityAlive()&&caller.target.world==world&&canEntityBeSeen(caller.target))alert(caller.target,caller.target.getPosition());
    }
    void clearHelp(){if(helpCaller!=null){helpCaller=null;invalidateRoute();}}
    boolean acceptHumanHelp(net.minecraft.entity.player.EntityPlayerMP player){
        if(!autonomousEnabled()||!SurvivorContacts.friendly(player)||player.world!=world||getDistanceSq(player)>32*32||!canEntityBeSeen(player)||!canHelpSafely(true)||helpPriority()>=75)return false;
        humanCaller=player;humanHelpUntil=age+300;invalidateRoute();return true;
    }
    private BlockPos humanHelpDestination(){
        if(humanCaller==null)return null;
        if(age>=humanHelpUntil||!humanCaller.isEntityAlive()||humanCaller.world!=world||!SurvivorContacts.friendly(humanCaller)||getDistanceSq(humanCaller)>48*48){humanCaller=null;return null;}
        if(getDistanceSq(humanCaller)<9)return getPosition();
        BlockPos p=humanCaller.getPosition();return standable(p)?p:null;
    }
    private BlockPos helpDestination(){
        if(helpCaller==null||!helpCaller.isEntityAlive()||helpCaller.world!=world||getDistanceSq(helpCaller)>48*48)return null;
        if(getDistanceSq(helpCaller)<=6.25)return getPosition();
        BlockPos p=helpCaller.getPosition();
        return standable(p)?p:null;
    }
    private String activity = "patrol";
    public SurvivorPlayer(MinecraftServer server, WorldServer world, GameProfile profile, BlockPos pos) {
        super(server, world, profile, new PlayerInteractionManager(world));
        this.home = pos;
        this.work=new SurvivorWork(this,pos);
        this.furnace=new SurvivorFurnace(this);
        season=new SurvivorSeason(this);autoTurret=new SurvivorTurret(this);vehicles=new SurvivorVehicles(this);arena=new SurvivorArena(this);
        this.lavaTrap=new SurvivorLavaTrap(this);this.defenseStation=new SurvivorDefenseStation(this);
        this.foodRecovery=new SurvivorFood(this);
        // Forge mod packets bypass NetHandlerPlayServer.sendPacket and inspect channel().
        // An isolated channel without a Forge dispatcher makes those sends no-ops.
        this.connection = new NetHandlerPlayServer(server, new NetworkManager(EnumPacketDirection.SERVERBOUND) {
            private final io.netty.channel.Channel sink = new io.netty.channel.embedded.EmbeddedChannel();
            @Override public io.netty.channel.Channel channel() { return sink; }
            @Override public void sendPacket(Packet<?> packet) { /* No remote client; never queue. */ }
        }, this) {
            @Override public void sendPacket(Packet<?> packet) { /* No remote client. */ }
            // This handler belongs exclusively to the agent; never invoke PlayerList.playerLoggedOut.
            @Override public void disconnect(net.minecraft.util.text.ITextComponent reason) { /* No authenticated connection. */ }
            @Override public void onDisconnect(net.minecraft.util.text.ITextComponent reason) { /* No authenticated session. */ }
        };
        getEntityData().setBoolean("ManhuntAIPlayer", true);
        setGameType(GameType.SURVIVAL);
        setPositionAndRotation(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        nextScan=1+Math.floorMod(profile.getId().hashCode(),10); nextLootScan=1+Math.floorMod(profile.getId().hashCode(),40);
        inventory.setInventorySlotContents(0, new ItemStack(Items.IRON_SWORD));
        inventory.setInventorySlotContents(1, new ItemStack(Items.BREAD, 8));
    }
    void equipSiegeStarter(){
        inventory.clear();
        ItemStack sword=new ItemStack(Items.DIAMOND_SWORD);
        sword.addEnchantment(net.minecraft.init.Enchantments.SHARPNESS,3);
        sword.addEnchantment(net.minecraft.init.Enchantments.FIRE_ASPECT,2);
        inventory.setInventorySlotContents(0,sword);
        inventory.setInventorySlotContents(1,new ItemStack(Items.BREAD,32));
        inventory.setInventorySlotContents(2,new ItemStack(Items.DIAMOND_PICKAXE));
        inventory.setInventorySlotContents(3,new ItemStack(net.minecraft.init.Blocks.COBBLESTONE,64));
        inventory.setInventorySlotContents(4,new ItemStack(Items.IRON_AXE));
        inventory.setInventorySlotContents(5,new ItemStack(net.minecraft.init.Blocks.TORCH,16));
        inventory.setInventorySlotContents(6,new ItemStack(net.minecraft.init.Blocks.PLANKS,24));
        ItemStack bow=new ItemStack(Items.BOW);
        bow.addEnchantment(net.minecraft.init.Enchantments.POWER,4);
        bow.addEnchantment(net.minecraft.init.Enchantments.FLAME,1);
        bow.addEnchantment(net.minecraft.init.Enchantments.UNBREAKING,2);
        inventory.setInventorySlotContents(7,bow);
        inventory.setInventorySlotContents(8,new ItemStack(Items.ARROW,64));
        inventory.setInventorySlotContents(9,new ItemStack(Items.ARROW,64));
        inventory.setInventorySlotContents(10,new ItemStack(net.minecraft.init.Blocks.COBBLESTONE,64));
        inventory.setInventorySlotContents(11,new ItemStack(net.minecraft.init.Blocks.OBSIDIAN,24));
        inventory.setInventorySlotContents(12,new ItemStack(Items.GOLDEN_APPLE,4));
        inventory.setInventorySlotContents(13,new ItemStack(Items.LAVA_BUCKET));
        inventory.setInventorySlotContents(14,new ItemStack(Items.LAVA_BUCKET));
        inventory.setInventorySlotContents(15,new ItemStack(net.minecraft.init.Blocks.LADDER,12));
        inventory.setInventorySlotContents(16,new ItemStack(net.minecraft.init.Blocks.CRAFTING_TABLE));
        inventory.setInventorySlotContents(17,new ItemStack(net.minecraft.init.Blocks.FURNACE));
        for(var item:new net.minecraft.item.Item[]{Items.DIAMOND_HELMET,Items.DIAMOND_CHESTPLATE,Items.DIAMOND_LEGGINGS,Items.DIAMOND_BOOTS}){
            ItemStack armor=new ItemStack(item);
            armor.addEnchantment(net.minecraft.init.Enchantments.PROTECTION,3);
            armor.addEnchantment(net.minecraft.init.Enchantments.UNBREAKING,2);
            if(item==Items.DIAMOND_BOOTS)armor.addEnchantment(net.minecraft.init.Enchantments.FEATHER_FALLING,4);
            setItemStackToSlot(((net.minecraft.item.ItemArmor)item).armorType,armor);
        }
        setHeldItem(EnumHand.OFF_HAND,new ItemStack(Items.SHIELD));inventory.markDirty();
    }
    @Override public net.minecraft.util.text.ITextComponent getTabListDisplayName(){
        return new net.minecraft.util.text.TextComponentString(getName());
    }
    BlockPos homePosition(){return home;}
    boolean seasonNeedsIron(){return season.needsIron();}
    boolean seasonWork(int age){return season.plan(age)||arena.plan(age);}
    boolean arenaSpecimen(){return arena.hasSpecimen();}
    boolean prepareArena(BlockPos p){return arena.request(p);}
    boolean turretWork(int age){return autoTurret.plan(age);}
    boolean vehicleWork(int age){return vehicles.plan(age);}
    boolean vehicleGoal(net.minecraft.util.ResourceLocation id){return vehicles.request(id);}
    void facePoint(net.minecraft.util.math.Vec3d p){lookAt(p.x,p.y,p.z);}
    boolean requestLava(BlockPos p){return lavaTrap.request(p,age);}
    boolean registerDefense(BlockPos p,net.minecraft.util.ResourceLocation ammo,EnumFacing side){return defenseStation.register(p,ammo,side,age);}
    void clearDefense(){defenseStation.clear();invalidateRoute();}
    String infrastructureStatus(){return "lava="+lavaTrap.status()+"; turret="+defenseStation.status();}
    boolean autonomousEnabled(){return work.isEnabled();}
    SurvivorProgression.Role role(){return work.role();}
    SurvivorPersonality.Style personality(){return SurvivorPersonality.style(getUniqueID().hashCode()>>>8);}
    boolean calmForSupply(){return !stressed&&target==null&&age>=alertUntil&&!dangerousHere()&&!isHandActive()&&age>=reloadUntil;}
    String dialogueContext(){return activity()+"; характер: "+SurvivorPersonality.description(personality())+"; здоровье "+getHealth()+"/"+getMaxHealth();}
    String technologyGoalId(){return work.goalId();}
    void recordVictory(){
        victories++;
        if(age<nextVictoryChat)return;
        nextVictoryChat=age+400;
        SurvivorTelemetry.event("chat",this,"Противник уничтожен. Проверяю окрестности.");
        for(EntityPlayerMP p:getServer().getPlayerList().getPlayers())if(p.world==world&&p.getDistanceSq(this)<=256&&!ManhuntCompat.isHostile(p))p.sendMessage(new net.minecraft.util.text.TextComponentString(getName()+": противник уничтожен. Проверяю окрестности."));
    }
    public String activity() { return "order="+combatOrder+"/kills="+victories+"/"+activity+"/"+work.status()+"/furnace="+furnace.status()+"/build="+buildStatus()+"/season="+season.status()+"/OMT="+autoTurret.status()+"/MCH="+vehicles.status()+"/arena="+arena.status(); }
    boolean hasFurnaceStation(){return furnace.validStation();}
    boolean furnaceBusy(){return furnace.active();}
    void industry(boolean enabled){work.industry(enabled);invalidateRoute();}
    boolean industryEnabled(){return work.industryEnabled();}
    boolean smelt(net.minecraft.util.ResourceLocation id){return furnace.request(id);}
    boolean furnaceStation(){return buildGoal==null&&furnace.queueStation();}
    void autonomous(boolean enable){work.enabled(enable);invalidateRoute();}
    boolean techGoal(net.minecraft.util.ResourceLocation id){return work.goal(id);}
    void clearTechGoal(){work.clearGoal();}
    void faceBlock(BlockPos p){lookAt(p.getX()+.5,p.getY()+.5,p.getZ()+.5);}
    int equipInventorySlot(int i){if(i<0||i>=36)return -1;if(i>=9){int h=inventory.currentItem;ItemStack old=inventory.getStackInSlot(h);inventory.setInventorySlotContents(h,inventory.getStackInSlot(i));inventory.setInventorySlotContents(i,old);i=h;inventory.markDirty();}inventory.currentItem=i;return i;}
    public void cancelPlanning() { SurvivorWeapons.cancelReload(this);foodRecovery.interrupt();work.clear();invalidateRoute(); }
    private void invalidateRoute() {
        cancelled.set(true);
        cancelled = new AtomicBoolean();
        pending = null; path = PathResult.EMPTY; step = 0; plannedGoal = null; nextPlan = 0;
    }
    void setStressed(boolean stressed) { this.stressed=stressed; }
    void tookDamage(){lastDamageTick=age;nextHazardScan=age;nextScan=age;}
    void alert(EntityLivingBase source, BlockPos location) {
        if (source != null && (source == this || !ManhuntCompat.isHostile(source))) return;
        if (location == null || getDistanceSqToCenter(location) > 24*24) return;
        lastThreat=location; lastThreatTick=age;
        if(source!=null && age>=alertUntil){var memory=SurvivorMemory.get((WorldServer)world);memory.note(getUniqueID(),"danger",location,world.getTotalWorldTime());memory.count(getUniqueID(),"encounters");}
        alertUntil=age+100; nextScan=age;
        if (source != null && source.world==world && source.isEntityAlive() && canEntityBeSeen(source)){
            if(target!=source){lastEnemyHealth=source.getHealth();lastEnemyArmor=source.getTotalArmorValue();}
            target=source;nextCombatAssessment=0;
        }
    }
    void explosionAlert(BlockPos pos) {
        alert(null,pos); explosionUntil=age+30; nextEvade=age; escapeGoal=null;
    }
    private boolean standable(BlockPos p) { return SurvivorTerrain.stand(world,p); }
    private boolean dangerousHere() {
        BlockPos p=getPosition();
        return age<explosionUntil || isBurning() || isInLava() || (world.isBlockLoaded(p) && world.getBlockState(p).getBlock()==net.minecraft.init.Blocks.FIRE);
    }
    private BlockPos chooseEscape() {
        BlockPos here=getPosition(), threat=age<explosionUntil ? lastThreat : target!=null && canEntityBeSeen(target) ? target.getPosition() : lastThreat;
        BlockPos best=null; double bestScore=-Double.MAX_VALUE;
        for(int i=0;i<24;i++) {
            double a=2*Math.PI*(i%8)/8;
            int radius=i<8?4:i<16?8:12;
            BlockPos base=here.add(Math.round(Math.cos(a)*radius),0,Math.round(Math.sin(a)*radius));
            for(int dy=-1;dy<=1;dy++) {
                BlockPos p=base.up(dy);
                if(!standable(p)) continue;
                double score=(threat==null?0:p.distanceSq(threat))-.35*p.distanceSq(home);
                if(threat!=null){
                    var hit=world.rayTraceBlocks(new net.minecraft.util.math.Vec3d(threat).add(.5,1.4,.5),new net.minecraft.util.math.Vec3d(p).add(.5,1.4,.5),false,true,false);
                    if(hit!=null)score+=28;
                }
                if(world.isBlockLoaded(p.up(2))&&world.getBlockState(p.up(2)).isFullCube())score+=12;
                if(!world.getWorldBorder().contains(p))continue;
                if(score>bestScore) {best=p;bestScore=score;}
            }
        }
        return best;
    }
    private void scanThreats() {
        closeEnemies=0;
        java.util.List<EntityLivingBase> candidates=world.getEntitiesWithinAABB(EntityLivingBase.class,getEntityBoundingBox().grow(20),
            e -> e!=this && e.isEntityAlive() && ManhuntCompat.isHostile(e));
        candidates.sort(java.util.Comparator.comparingDouble(e -> getDistanceSq(e)));
        EntityLivingBase best=null; double score=Double.MAX_VALUE;
        for(int i=0;i<Math.min(8,candidates.size());i++) {
            EntityLivingBase e=candidates.get(i);
            if(!canEntityBeSeen(e)) continue;
            if(getDistanceSq(e)<=100)closeEnemies++;
            double value=getDistanceSq(e)*SrpTacticalClassifier.classify(e).threatWeightMultiplier();
            if(e==target) value*=.75;
            if(e instanceof net.minecraft.entity.player.EntityPlayer)value*=.6;
            if(e==squadTarget&&age<squadUntil)value*=.45;
            if(value<score) {best=e;score=value;}
        }
        if(best!=null) {boolean changed=target!=best;target=best;if(age>=explosionUntil){lastThreat=best.getPosition();lastThreatTick=age;}alertUntil=age+100;if(changed){lastEnemyHealth=best.getHealth();lastEnemyArmor=best.getTotalArmorValue();nextCombatAssessment=0;SurvivorService.INSTANCE.observedThreat(this,best);}}
    }
    private void scanHazards() {
        int checked=0;
        for(net.minecraft.entity.Entity e:world.getEntitiesWithinAABBExcludingEntity(this,getEntityBoundingBox().grow(8))) {
            if(++checked>64) break;
            if(e.isDead) continue;
            var id=net.minecraft.entity.EntityList.getKey(e);
            if(SrpTacticalClassifier.isSrpAreaHazardId(id) || SrpTacticalClassifier.isSrpExplosiveId(id)) {
                if(getDistanceSq(e)<36 && canEntityBeSeen(e)) {if(age>=explosionUntil||lastThreat==null||lastThreat.distanceSq(e.getPosition())>9)explosionAlert(e.getPosition());return;}
            } else if(SrpTacticalClassifier.isSrpProjectileId(id) || e instanceof net.minecraft.entity.projectile.EntityArrow) {
                double dx=posX-e.posX,dy=posY+.8-e.posY,dz=posZ-e.posZ;
                double speed=e.motionX*e.motionX+e.motionY*e.motionY+e.motionZ*e.motionZ;
                if(speed<.01) continue;
                double t=(dx*e.motionX+dy*e.motionY+dz*e.motionZ)/speed;
                if(t<0 || t>8) continue;
                double x=dx-e.motionX*t,y=dy-e.motionY*t,z=dz-e.motionZ*t;
                if(x*x+y*y+z*z<2.25 && canEntityBeSeen(e)) {if(age>=explosionUntil||lastThreat==null||lastThreat.distanceSq(e.getPosition())>9)explosionAlert(e.getPosition());return;}
            }
        }
    }
    private void scanLoot() {
        loot=null; double distance=144; int checked=0;
        for(net.minecraft.entity.item.EntityItem item:world.getEntitiesWithinAABB(net.minecraft.entity.item.EntityItem.class,getEntityBoundingBox().grow(12))) {
            if(++checked>48) break;
            if(item.isDead || item.getItem().isEmpty() || item.cannotPickup() || item.getOwner()!=null) continue;
            ItemStack stack=item.getItem();
            if(!(stack.getItem() instanceof ItemFood || stack.getItem() instanceof net.minecraft.item.ItemSword || stack.getItem() instanceof ItemBlock || stack.getItem()==Items.COAL || stack.getItem()==Items.IRON_INGOT || stack.getItem() instanceof net.minecraft.item.ItemTool || stack.getItem() instanceof net.minecraft.item.ItemArmor || SurvivorSeason.useful(stack))) continue;
            double d=getDistanceSq(item);
            if(d<distance && canEntityBeSeen(item)) {loot=item;distance=d;}
        }
    }
    public boolean requestBuild(BlockPos pos) { return requestBuild(pos, null); }
    public boolean requestBuild(BlockPos pos, net.minecraft.util.ResourceLocation item) {
        if (!world.isBlockLoaded(pos) || pos.distanceSq(home) > 12*12 || !world.isAirBlock(pos) || !SurvivorPermissions.allowed(this,pos,true)) return false;
        if(buildGoal!=null)return buildGoal.equals(pos)&&java.util.Objects.equals(buildItem,item);
        buildGoal = pos; buildItem = item; buildDeadline = age + 400; buildApproach=null;nextBuildApproach=0;invalidateRoute();return true;
    }
    public void fortify() { queueShelter(home,0,1,1); }
    void queueShelter(BlockPos center,int worker,int workers,int levels){
        fortification.clear();buildGoal=null;buildApproach=null;placedBlocks=0;skippedBlocks=0;invalidateRoute();
        for(var offset:SurvivorBlueprint.perimeter(worker,workers,levels))fortification.add(center.add(offset.x(),offset.y(),offset.z()));
    }
    String buildStatus(){return "placed="+placedBlocks+", skipped="+skippedBlocks+", queued="+(fortification.size()+(buildGoal==null?0:1));}
    private BlockPos findBuildApproach(){
        if(buildGoal==null)return null;
        if(age<nextBuildApproach&&buildApproach!=null&&standable(buildApproach))return buildApproach;
        nextBuildApproach=age+20;buildApproach=null;double best=Double.MAX_VALUE;
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int dy=-2;dy<=0;dy++){
            if(x==0&&z==0)continue;
            BlockPos p=buildGoal.add(x,dy,z);
            if(!standable(p)||p.distanceSq(home)>144)continue;
            double d=getDistanceSqToCenter(p)+.25*p.distanceSq(buildGoal);
            if(d<best){best=d;buildApproach=p;}
        }
        return buildApproach;
    }
    int constructionStock(){
        int total=0;
        for(int i=0;i<36;i++){
            ItemStack s=inventory.getStackInSlot(i);
            if(!s.isEmpty()&&s.getItem() instanceof ItemBlock b&&safeBuildingBlock(b))total+=s.getCount();
        }
        return total;
    }
    private static boolean safeBuildingBlock(ItemBlock b){
        var block=b.getBlock();
        return block==net.minecraft.init.Blocks.OBSIDIAN||block==net.minecraft.init.Blocks.COBBLESTONE||block==net.minecraft.init.Blocks.STONE||block==net.minecraft.init.Blocks.PLANKS||block==net.minecraft.init.Blocks.BRICK_BLOCK||block==net.minecraft.init.Blocks.STONEBRICK;
    }
    int missingWallBlocks(){
        int missing=0;
        for(var offset:SurvivorStrongholdPlan.structure()){
            BlockPos p=home.add(offset.x(),offset.y(),offset.z());
            if(world.isBlockLoaded(p)&&world.isAirBlock(p))missing++;
        }
        return missing;
    }
    private boolean carriedBlock(net.minecraft.block.Block block){
        for(int i=0;i<36;i++){ItemStack stack=inventory.getStackInSlot(i);if(!stack.isEmpty()&&stack.getItem()==net.minecraft.item.Item.getItemFromBlock(block))return true;}
        return false;
    }
    boolean needsGate(){BlockPos p=home.add(0,0,-2);return world.isBlockLoaded(p)&&world.isAirBlock(p);}
    boolean needsLadder(){
        for(int y=0;y<=3;y++){BlockPos p=home.add(1,y,1);if(world.isBlockLoaded(p)&&world.isAirBlock(p))return true;}
        return false;
    }
    private boolean queueLadder(){
        if(!carriedBlock(net.minecraft.init.Blocks.LADDER))return false;
        BlockPos hatch=home.add(1,2,1);
        if(!world.isBlockLoaded(hatch)||(!world.isAirBlock(hatch)&&world.getBlockState(hatch).getBlock()!=net.minecraft.init.Blocks.LADDER))return false;
        for(int y=0;y<=3;y++){
            BlockPos p=home.add(1,y,1),support=p.east();
            if(world.isBlockLoaded(p)&&world.isAirBlock(p)&&world.isBlockLoaded(support)&&world.getBlockState(support).isFullCube()&&requestBuild(p,new net.minecraft.util.ResourceLocation("minecraft:ladder")))return true;
        }
        return false;
    }
    private void planDefenseRepair(){
        if(!autonomousEnabled()||stressed||target!=null||age<alertUntil||age<nextDefenseScan||buildGoal!=null||!fortification.isEmpty())return;
        nextDefenseScan=age+100;
        if(queueLadder())return;
        if(missingWallBlocks()<8&&lavaTrap.autoPlan(age))return;
        if(carriedBlock(net.minecraft.init.Blocks.OAK_FENCE_GATE)){
            BlockPos gate=home.add(0,0,-2);
            if(world.isBlockLoaded(gate)&&world.isAirBlock(gate)&&world.isBlockLoaded(gate.down())&&CellClassifier.isSolidSupport(world,gate.down())&&requestBuild(gate,new net.minecraft.util.ResourceLocation("minecraft:fence_gate")))return;
        }
        if(carriedBlock(net.minecraft.init.Blocks.TORCH)){
            BlockPos p=home.add(-1,0,1);
            if(world.isBlockLoaded(p)&&world.isAirBlock(p)&&world.isBlockLoaded(p.down())&&CellClassifier.isSolidSupport(world,p.down())&&requestBuild(p,new net.minecraft.util.ResourceLocation("minecraft:torch")))return;
        }
        int available=constructionStock();
        if(available<4)return;
        // Keep basic crafting stock available while repairing the perimeter.
        available=Math.max(0,available-4);if(available==0)return;
        for(var offset:SurvivorStrongholdPlan.structure()){
            if(available<=0)break;
            if(offset.kind()==SurvivorStrongholdPlan.Kind.BAFFLE&&SurvivorTerrain.safeExits(world,home)<2)continue;
            BlockPos p=home.add(offset.x(),offset.y(),offset.z());
            if(!world.isBlockLoaded(p)||!world.isBlockLoaded(p.down())||!world.isAirBlock(p)||!SurvivorPermissions.allowed(this,p,true))continue;
            if(!hasBuildSupport(p)&&!fortification.contains(p.down())&&!queuedNeighbor(p))continue;
            fortification.add(p);available--;
        }
    }
    private boolean hasBuildSupport(BlockPos p){
        for(EnumFacing f:EnumFacing.values()){
            BlockPos q=p.offset(f);
            if(world.isBlockLoaded(q)&&world.getBlockState(q).isFullCube()&&SurvivorPermissions.allowed(this,q,true))return true;
        }
        return false;
    }
    private boolean queuedNeighbor(BlockPos p){
        for(EnumFacing f:EnumFacing.HORIZONTALS)if(fortification.contains(p.offset(f)))return true;
        return false;
    }
    private void planFortification() {
        if(buildGoal!=null && age>=buildDeadline){buildGoal=null;buildApproach=null;skippedBlocks++;invalidateRoute();}
        if(buildGoal==null && !fortification.isEmpty() && age%20==1) {
            BlockPos p=fortification.removeFirst();
            if(!hasBuildSupport(p)||!requestBuild(p))skippedBlocks++;
        }
    }
    public String reply(String message) {
        if (age < nextChat) return null;
        nextChat = age + 200;
        String msg = message.toLowerCase(java.util.Locale.ROOT);
        String contact=SurvivorContacts.reply(msg);if(contact!=null)return contact;
        if (msg.contains("привет") || msg.contains("здравств")) return "Привет! Я ИИ-выживший, сейчас " + russianActivity() + ".";
        if (msg.contains("здоров") || msg.contains("хп")) return "Здоровье: " + Math.round(getHealth()) + "/" + Math.round(getMaxHealth()) + ".";
        if (msg.contains("работ") || msg.contains("план") || msg.contains("роль")) return "Моя задача: " + work.status() + ".";
        if (msg.contains("патрон") || msg.contains("оруж")) return gunSlot >= 0 ? "Есть заряженное оружие." : "Заряженного оружия сейчас нет.";
        if (msg.contains("паразит") || msg.contains("вагрант")) return target == null ? "Видимых противников сейчас нет." : "Вижу противника: " + target.getName() + ".";
        return "Сейчас " + russianActivity() + ". Я ИИ, пока понимаю вопросы о здоровье, оружии и противниках.";
    }
    private String russianActivity() {
        return switch (activity) { case "combat" -> "в бою"; case "retreat" -> "отступаю"; case "build" -> "укрепляю позицию"; case "scavenge" -> "собираю ресурсы"; case "eat" -> "восстанавливаю силы"; default -> "патрулирую"; };
    }
    // Manhunt's exact Modular Warfare build implements ISpecialArmor on ItemMWArmor.
    // Only approved human equipment, never parasite armor.
    private double humanArmorScore(ItemStack stack){
        if(stack.isEmpty()||!(stack.getItem() instanceof net.minecraft.item.ItemArmor armor))return -1;
        var id=armor.getRegistryName();
        if(id==null||!java.util.Set.of("minecraft","modularwarfare","thermalfoundation").contains(id.getNamespace()))return -1;
        double protection=armor.damageReduceAmount;
        if(armor instanceof net.minecraftforge.common.ISpecialArmor special){
            try{protection=Math.max(0,special.getArmorDisplay(this,stack,armor.armorType.getIndex()));}
            catch(RuntimeException|LinkageError e){return -1;}
        }
        int ench=net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(net.minecraft.init.Enchantments.PROTECTION,stack);
        double durability=stack.isItemStackDamageable()?Math.max(0.0,1.0-(double)stack.getItemDamage()/Math.max(1,stack.getMaxDamage())):1.0;
        return (protection+ench*.75)*(durability<.15?.55:1.0)+durability*.2;
    }
    private void equipHumanArmor(){
        for(int i=0;i<36;i++){
            ItemStack s=inventory.getStackInSlot(i);
            if(s.isEmpty()||!(s.getItem() instanceof net.minecraft.item.ItemArmor armor))continue;
            double score=humanArmorScore(s);if(score<0)continue;
            var slot=armor.armorType;
            ItemStack old=getItemStackFromSlot(slot);
            if(!old.isEmpty()&&(!(old.getItem() instanceof net.minecraft.item.ItemArmor)||humanArmorScore(old)>=score))continue;
            inventory.setInventorySlotContents(i,old);
            setItemStackToSlot(slot,s);inventory.markDirty();
        }
        if(getHeldItemOffhand().isEmpty()||getHeldItemOffhand().getItem()==Items.SHIELD&&getHeldItemOffhand().getItemDamage()>getHeldItemOffhand().getMaxDamage()*.85)for(int i=0;i<36;i++){
            ItemStack shield=inventory.getStackInSlot(i);
            if(shield.getItem()!=Items.SHIELD||shield.isEmpty())continue;
            if(!getHeldItemOffhand().isEmpty()&&shield.getItemDamage()>=getHeldItemOffhand().getItemDamage())continue;
            inventory.setInventorySlotContents(i,getHeldItemOffhand());
            setHeldItem(EnumHand.OFF_HAND,shield);inventory.markDirty();break;
        }
    }
    boolean safeFood(ItemStack s){
        if(s.isEmpty()||!(s.getItem() instanceof ItemFood))return false;
        var item=s.getItem();
        return item!=Items.SPIDER_EYE&&item!=Items.POISONOUS_POTATO&&item!=Items.ROTTEN_FLESH&&!(item==Items.FISH&&s.getMetadata()==3);
    }
    int bestMelee(){return findMelee();}
    void faceEntityForFood(EntityLivingBase e){lookAt(e.posX,e.posY+e.getEyeHeight(),e.posZ);}
    private int findFood() {
        int best=-1;float score=-1;
        for(int i=0;i<36;i++){
            ItemStack s=inventory.getStackInSlot(i);
            if(!safeFood(s)||!(s.getItem() instanceof ItemFood food))continue;
            float v=food.getHealAmount(s)+2*food.getSaturationModifier(s);
            if(v>score){score=v;best=i;}
        }
        return best;
    }
    private int findMelee() {
        int best=-1;float score=-1;
        for(int i=0;i<36;i++){
            ItemStack s=inventory.getStackInSlot(i);
            if(s.isEmpty()||s.getItem().getRegistryName()==null)continue;
            String namespace=s.getItem().getRegistryName().getNamespace();
            if(!(s.getItem() instanceof net.minecraft.item.ItemSword)&&!"tetra".equals(namespace))continue;
            if(!java.util.Set.of("minecraft","tetra","thermalfoundation").contains(namespace))continue;
            double damage=1;
            for(var modifier:s.getAttributeModifiers(net.minecraft.inventory.EntityEquipmentSlot.MAINHAND).get(net.minecraft.entity.SharedMonsterAttributes.ATTACK_DAMAGE.getName()))
                if(modifier.getOperation()==0)damage+=modifier.getAmount();
            if(damage<=1||!Double.isFinite(damage))continue;
            float v=(float)damage+net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(net.minecraft.init.Enchantments.SHARPNESS,s)*.5f;
            if(v>score){best=i;score=v;}
        }
        return best;
    }
    private void placeRequestedBlock() {
        if (buildGoal == null || age < nextBuild || getDistanceSqToCenter(buildGoal) > 16) return;
        if(getEntityBoundingBox().intersects(new net.minecraft.util.math.AxisAlignedBB(buildGoal)))return;
        nextBuild = age + 20;
        if (!world.isBlockLoaded(buildGoal) || !world.isAirBlock(buildGoal) || !SurvivorPermissions.allowed(this,buildGoal,true)) { buildGoal = null; return; }
        int slot = -1;
        for (int i=0; i<36; i++) if (inventory.getStackInSlot(i).getItem() instanceof ItemBlock block && !inventory.getStackInSlot(i).isEmpty() && (buildItem != null ? buildItem.equals(block.getRegistryName()) : safeBuildingBlock(block))) { slot=i; break; }
        if (slot < 0) { skippedBlocks+=1+fortification.size();buildGoal=null;buildApproach=null;fortification.clear();invalidateRoute();return; }
        for (EnumFacing side : EnumFacing.values()) {
            if(buildItem!=null&&buildItem.toString().equals("minecraft:ladder")&&side!=EnumFacing.WEST)continue;
            if(buildItem!=null&&(buildItem.toString().equals("minecraft:fence_gate")||buildItem.toString().equals("minecraft:torch"))&&side!=EnumFacing.UP)continue;
            BlockPos support = buildGoal.offset(side.getOpposite());
            if (!world.isBlockLoaded(support) || world.isAirBlock(support) || !SurvivorPermissions.allowed(this,support,true)) continue;
            net.minecraft.util.math.Vec3d hit = new net.minecraft.util.math.Vec3d(support).add(.5 + side.getXOffset()*.5, .5 + side.getYOffset()*.5, .5 + side.getZOffset()*.5);
            net.minecraft.util.math.RayTraceResult trace = world.rayTraceBlocks(getPositionEyes(1F), hit.add(-side.getXOffset()*.01, -side.getYOffset()*.01, -side.getZOffset()*.01), false, true, false);
            if (trace == null || !support.equals(trace.getBlockPos()) || trace.sideHit != side) continue;
            equipInventorySlot(slot); moveForward=0;moveStrafing=0;lookAt(hit.x,hit.y,hit.z);
            EnumActionResult result=interactionManager.processRightClickBlock(this, world, getHeldItemMainhand(), EnumHand.MAIN_HAND, support, side, (float)(hit.x-support.getX()), (float)(hit.y-support.getY()), (float)(hit.z-support.getZ()));
            if(result==EnumActionResult.SUCCESS && !world.isAirBlock(buildGoal)) { swingArm(EnumHand.MAIN_HAND); furnace.placed(buildGoal);autoTurret.placed(buildGoal);vehicles.placed(buildGoal);placedBlocks++;buildGoal=null;buildApproach=null;invalidateRoute(); }
            return;
        }
    }
    public void drive() {
        if (!isEntityAlive()) return;
        age++;
        if(SurvivorWeapons.advanceReload(this,age)){
            if(target!=null&&getDistanceSq(target)<36){SurvivorWeapons.cancelReload(this);}
            else{moveForward=0;moveStrafing=0;onUpdateEntity();return;}
        }
        if(age%600==0)routeFailures.clear();
        planFortification();
        if(age%10==0&&!dangerousHere())closePassedGate();
        if(gunSlot>=0&&!SurvivorWeapons.isLoaded(inventory.getStackInSlot(gunSlot)))gunSlot=-1;
        if(bowSlot>=0&&inventory.getStackInSlot(bowSlot).getItem()!=Items.BOW)bowSlot=-1;
        if(age>=nextInventoryScan) {
            nextInventoryScan=age+(target!=null?4:10);
            if(target==null&&!stressed&&age%100<10)equipHumanArmor();
            gunSlot=SurvivorWeapons.loadedGun(this);bowSlot=findBow();foodSlot=findFood();
        }
        if(age>=nextScan) {
            scanThreats();
            nextScan=age+SurvivorBrain.scanInterval(target!=null || age<alertUntil,stressed);
        }
        if(age>=nextHazardScan) {nextHazardScan=age+(stressed?12:target!=null?3:8);scanHazards();}
        if(target==null && !stressed && age>=nextLootScan) {nextLootScan=age+40;scanLoot();}
        if(loot!=null && (loot.isDead || loot.cannotPickup() || getDistanceSq(loot)>144)) loot=null;
        if(target==null)closeEnemies=0;
        if (target != null && (target.world != world || !target.isEntityAlive() || !ManhuntCompat.isHostile(target) || getDistanceSq(target) > 24 * 24)) target = null;
        boolean visible=target!=null && canEntityBeSeen(target);
        if(visible && age>=explosionUntil) {lastThreat=target.getPosition();lastThreatTick=age;}
        if(target!=null && !visible && age-lastThreatTick>60){target=null;closeEnemies=0;}
        visible=target!=null&&visible;
        var profile=SrpTacticalClassifier.classify(target);
        var action=SurvivorBrain.choose(new SurvivorBrain.Situation(getHealth(),getMaxHealth(),getFoodStats().needFood(),foodSlot>=0,
            target!=null,visible,hasLoadedGun(),findMelee()>=0,target==null?Double.MAX_VALUE:getDistanceSq(target),
            profile.preferredMinRangeSq(),profile.fleeWhenSwelling(),dangerousHere(),buildGoal!=null,loot!=null));
        if(target==null&&action==SurvivorBrain.Action.PATROL)planDefenseRepair();
        assessCombat(visible);
        if(target!=null&&action!=SurvivorBrain.Action.EVADE){
            if(getPosition().distanceSq(home)>32*32&&!visible)action=SurvivorBrain.Action.RETREAT;
            if(combatOrder==SurvivorCombatPolicy.Order.WITHDRAW||combatOrder==SurvivorCombatPolicy.Order.COVER&&(!visible||getDistanceSq(target)<100||getDistanceSq(target)>576))action=SurvivorBrain.Action.RETREAT;
            else if(SurvivorDefense.overwhelmed(getHealth(),getMaxHealth(),closeEnemies,target.getHealth(),hasLoadedGun(),getHeldItemOffhand().getItem()==Items.SHIELD)&&combatOrder!=SurvivorCombatPolicy.Order.ENGAGE) action=SurvivorBrain.Action.RETREAT;
            else if(SurvivorDefense.recovery(getHealth(),getMaxHealth(),getFoodStats().getFoodLevel(),foodSlot>=0,visible,getDistanceSq(target))) action=SurvivorBrain.Action.EAT;
        }
        if(target!=null&&visible&&action!=SurvivorBrain.Action.EVADE&&SurvivorParasiteTactics.noMelee(target)){
            double minimum=SurvivorParasiteTactics.minRange(target);
            if(!hasLoadedGun()||getDistanceSq(target)<minimum*minimum)action=SurvivorBrain.Action.RETREAT;
        }
        if(action!=SurvivorBrain.Action.EVADE&&getHealth()<getMaxHealth()*.7f&&!isPotionActive(net.minecraft.init.MobEffects.ABSORPTION)&&!isPotionActive(net.minecraft.init.MobEffects.REGENERATION)&&(target==null||!visible||getDistanceSq(target)>196)){
            for(int i=0;i<36;i++){ItemStack apple=inventory.getStackInSlot(i);if(!apple.isEmpty()&&apple.getItem()==Items.GOLDEN_APPLE){foodSlot=i;action=SurvivorBrain.Action.EAT;break;}}
        }
        if(action==SurvivorBrain.Action.APPROACH&&target!=null&&getPosition().distanceSq(home)>32*32)action=SurvivorBrain.Action.RETREAT;
        if(action==SurvivorBrain.Action.RANGED&&gunSlot<0&&target!=null&&getDistanceSq(target)<9)action=findMelee()>=0&&target.getHealth()<=getMaxHealth()*2?SurvivorBrain.Action.MELEE:SurvivorBrain.Action.RETREAT;
        boolean retreat=action==SurvivorBrain.Action.RETREAT || action==SurvivorBrain.Action.EVADE;
        activity=retreat?"retreat":action==SurvivorBrain.Action.EAT?"eat":target!=null?"combat":action==SurvivorBrain.Action.BUILD?"build":action==SurvivorBrain.Action.SCAVENGE?"scavenge":action==SurvivorBrain.Action.EAT?"eat":"patrol";
        if(action!=previousAction) {invalidateRoute();escapeGoal=null;previousAction=action;work.interrupt();}
        if(helpCaller!=null&&(!helpCaller.isEntityAlive()||helpCaller.world!=world))clearHelp();
        BlockPos assistance=helpDestination();
        if(assistance==null)assistance=humanHelpDestination();
        if(assistance==null&&autonomousEnabled()&&target==null)assistance=SurvivorFinalBattle.rendezvous(this);
        if(action==SurvivorBrain.Action.PATROL&&assistance==null&&buildGoal==null&&!stressed&&autonomousEnabled()){
            boolean handled=vehicleWork(age);
            if(!handled&&vehicles.destination()==null&&buildGoal==null&&(role()==SurvivorProgression.Role.ENGINEER||role()==SurvivorProgression.Role.GUARD))turretWork(age);
        }
        BlockPos infrastructure=lavaTrap.destination()!=null?lavaTrap.destination():defenseStation.destination()!=null?defenseStation.destination():vehicles.destination()!=null?vehicles.destination():autoTurret.destination();
        if(action==SurvivorBrain.Action.PATROL&&assistance==null&&buildGoal==null&&!stressed)defenseStation.tick(age);
        if(action==SurvivorBrain.Action.PATROL && assistance==null && buildGoal==null&&!stressed&&furnace.active())furnace.tick(age);
        if(action==SurvivorBrain.Action.PATROL&&assistance==null&&infrastructure==null&&buildGoal==null&&!furnace.active())foodRecovery.plan(age,stressed);
        else foodRecovery.interrupt();
        if(action==SurvivorBrain.Action.PATROL && assistance==null && infrastructure==null && buildGoal==null && foodRecovery.destination()==null&&(!furnace.active()||furnace.destination()==null))work.plan(stressed);
        else work.interrupt();
        BlockPos jobGoal=assistance!=null?assistance:infrastructure!=null?infrastructure:furnace.active()&&furnace.destination()!=null?furnace.destination():foodRecovery.destination()!=null?foodRecovery.destination():work.destination();
        if(assistance!=null&&action==SurvivorBrain.Action.BUILD){action=SurvivorBrain.Action.PATROL;activity="assist";}
        if(assistance!=null&&action==SurvivorBrain.Action.PATROL)activity="assist";
        if(action==SurvivorBrain.Action.PATROL && jobGoal!=null && (plannedGoal==null||jobGoal.distanceSq(plannedGoal)>4) && pending==null)nextPlan=0;
        if((action==SurvivorBrain.Action.PATROL||action==SurvivorBrain.Action.RETREAT&&target!=null&&getDistanceSq(target)>144)&&gunSlot<0&&age>=nextReload&&!stressed&&!isHandActive()){
            nextReload=age+40;
            int reload=SurvivorWeapons.reload(this,age);
            if(reload>0)reloadUntil=age+reload;
        }
        if(retreat && (escapeGoal==null || age>=nextEvade || getDistanceSqToCenter(escapeGoal)<4)) {
            escapeGoal=chooseEscape();nextEvade=age+20;
            if(escapeGoal!=null && !escapeGoal.equals(plannedGoal)) invalidateRoute();
        }
        if(moveForward!=0 && Math.abs(posX-lastX)+Math.abs(posZ-lastZ)<.025) stuckTicks++; else stuckTicks=0;
        lastX=posX;lastZ=posZ;
        if(stuckTicks>16) {if(step<path.length())markRouteFailure(path.getStep(step));invalidateRoute();stuckTicks=0;}
        if (pending != null && pending.isDone()) {
            try { path = pending.getNow(PathResult.EMPTY); } catch (RuntimeException e) { path = PathResult.EMPTY; }
            pending = null; step = 0;
        }
        if (age >= nextPlan && pending == null && action!=SurvivorBrain.Action.RANGED && action!=SurvivorBrain.Action.MELEE && action!=SurvivorBrain.Action.EAT) {
            nextPlan=age+(stressed?60:retreat?15:30)+rand.nextInt(10);
            BlockPos goal=retreat?escapeGoal:target!=null?(visible?target.getPosition():lastThreat):action==SurvivorBrain.Action.BUILD?findBuildApproach():action==SurvivorBrain.Action.SCAVENGE?loot.getPosition():jobGoal!=null?jobGoal:patrolGoal();
            if(goal!=null) plannedGoal=goal;
            if (goal != null && world.isBlockLoaded(goal) && getDistanceSqToCenter(goal) < 48 * 48) {
                pending = PathfindingExecutor.submitServerPath(world, getPosition(), goal, 0,
                        new PathfindingSettings(false,getFoodStats().getFoodLevel()>8,false,stressed?2048:4096,2,0,stressed?10_000_000L:18_000_000L,true),cancelled,new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap(routeFailures));
            }
        }
        moveForward = 0; moveStrafing = 0;
        setSprinting(false);
        if (target != null && action==SurvivorBrain.Action.RANGED) {
            if(gunSlot>=0){
                cancelActiveUse();gunSlot=equipInventorySlot(gunSlot);
                lookAt(target.posX, target.posY + target.getEyeHeight() * .75, target.posZ);
                if(age>=nextAttack&&age>=reloadUntil)nextAttack=age+SurvivorWeapons.fire(this,target);
            }else driveBow(target);
            combatFootwork(target,true);
        } else if (target != null && action==SurvivorBrain.Action.MELEE) {
            if (isHandActive() && (getActiveHand()!=EnumHand.OFF_HAND || age>=nextAttack)) cancelActiveUse();
            int melee = findMelee();
            if (melee >= 0) equipInventorySlot(melee);
            lookAt(target.posX, target.posY + target.getEyeHeight(), target.posZ);
            if (melee >= 0 && age >= nextAttack && getCooledAttackStrength(0) >= .90F && canEntityBeSeen(target) && getDistanceSq(target)<=8 && ManhuntCompat.isHostile(target)) {
                nextAttack = age + 5 + rand.nextInt(3);
                attackTargetEntityWithCurrentItem(target);
                swingArm(EnumHand.MAIN_HAND);
            } else if(getHeldItemOffhand().getItem()==Items.SHIELD && !isHandActive() && (age<nextAttack||getCooledAttackStrength(0)<.9F)) setActiveHand(EnumHand.OFF_HAND);
            combatFootwork(target,false);
        } else { followPath(); if(retreat && moveForward==0)escapeStep(); if(retreat && moveForward>0 && getFoodStats().getFoodLevel()>6) setSprinting(true); }
        if (action==SurvivorBrain.Action.EAT && foodSlot >= 0 && inventory.getStackInSlot(foodSlot).getItem() instanceof ItemFood && !inventory.getStackInSlot(foodSlot).isEmpty()) {
            if(isHandActive()&&getActiveItemStack().getItem()==Items.BOW)cancelActiveUse();
            foodSlot=equipInventorySlot(foodSlot); moveForward = 0;
            if (!isHandActive()) setActiveHand(EnumHand.MAIN_HAND);
        } else {
            if(isHandActive()&&!(action==SurvivorBrain.Action.RANGED&&gunSlot<0&&getActiveItemStack().getItem()==Items.BOW)&&!(action==SurvivorBrain.Action.MELEE&&getActiveHand()==EnumHand.OFF_HAND&&(age<nextAttack||getCooledAttackStrength(0)<.9F)))cancelActiveUse();
            if (action==SurvivorBrain.Action.BUILD) placeRequestedBlock();
            if(action==SurvivorBrain.Action.SCAVENGE && loot!=null && getDistanceSq(loot)<1.44){loot.onCollideWithPlayer(this);if(loot.isDead)loot=null;}
            if(action==SurvivorBrain.Action.PATROL&&assistance==null&&buildGoal==null&&!stressed){if(lavaTrap.destination()!=null)lavaTrap.tick(age);else if(infrastructure==null&&(!furnace.active()||furnace.destination()==null)){if(foodRecovery.destination()!=null)foodRecovery.act(age);else work.act();}}
        }
        onUpdateEntity();
    }
    private int findBow(){
        boolean arrows=false;int slot=-1;
        for(int i=0;i<36;i++){
            ItemStack s=inventory.getStackInSlot(i);if(s.isEmpty())continue;
            if(s.getItem()==Items.ARROW)arrows=true;
            if(s.getItem()==Items.BOW&&s.getItemDamage()<s.getMaxDamage()-1)slot=i;
        }
        return arrows?slot:-1;
    }
    private void cancelActiveUse(){
        if(!isHandActive())return;
        if(getActiveItemStack().getItem()==Items.BOW)resetActiveHand();else stopActiveHand();
    }
    private boolean safeBowShot(EntityLivingBase enemy){
        if(enemy==null||!enemy.isEntityAlive()||!ManhuntCompat.isHostile(enemy)||!canEntityBeSeen(enemy))return false;
        var from=getPositionEyes(1);
        var to=new net.minecraft.util.math.Vec3d(enemy.posX,enemy.posY+enemy.getEyeHeight()*.7,enemy.posZ);
        for(EntityLivingBase other:world.getEntitiesWithinAABB(EntityLivingBase.class,new net.minecraft.util.math.AxisAlignedBB(from.x,from.y,from.z,to.x,to.y,to.z).grow(1))){
            if(other==this||other==enemy||ManhuntCompat.isHostile(other))continue;
            if(other.getEntityBoundingBox().grow(.35).calculateIntercept(from,to)!=null)return false;
        }
        return true;
    }
    private void driveBow(EntityLivingBase enemy){
        if(bowSlot<0||findBow()<0||!safeBowShot(enemy)){cancelActiveUse();return;}
        double distance=Math.sqrt(getDistanceSq(enemy));
        if(distance<3||distance>24){cancelActiveUse();return;}
        bowSlot=equipInventorySlot(bowSlot);
        double x=enemy.posX+enemy.motionX*Math.min(8,distance/3),z=enemy.posZ+enemy.motionZ*Math.min(8,distance/3);
        lookAt(x,enemy.posY+enemy.getEyeHeight()*.7+.0028*distance*distance,z);
        if(!isHandActive()&&age>=nextAttack)setActiveHand(EnumHand.MAIN_HAND);
        if(isHandActive()&&getActiveItemStack().getItem()==Items.BOW&&getItemInUseMaxCount()>=20){
            if(safeBowShot(enemy)){stopActiveHand();nextAttack=age+15;}else cancelActiveUse();
        }
    }
    private boolean safeFootstep(double dx,double dz){
        BlockPos p=new BlockPos(posX+dx,posY,posZ+dz);
        return SurvivorTerrain.safeStep(world,posX,posY,posZ,dx,dz);
    }
    private void combatFootwork(EntityLivingBase enemy,boolean ranged){
        if(enemy==null||!onGround)return;
        double d=getDistanceSq(enemy);
        if(age>=nextStrafe||age-lastDamageTick==1){strafeSide=rand.nextBoolean()?1:-1;nextStrafe=age+6+rand.nextInt(9);}
        double yaw=Math.toRadians(rotationYaw),fx=-Math.sin(yaw),fz=Math.cos(yaw),sx=Math.cos(yaw)*strafeSide,sz=Math.sin(yaw)*strafeSide;
        if((ranged&&d<64)||(!ranged&&age-lastDamageTick<5)){
            if(safeFootstep(-fx*.9,-fz*.9))moveForward=-.7f;
        }
        if(safeFootstep(sx*.9,sz*.9))moveStrafing=.7f*strafeSide;
        else if(safeFootstep(-sx*.9,-sz*.9))moveStrafing=-.7f*strafeSide;
    }
    private void escapeStep(){
        if(escapeGoal==null)return;
        BlockPos here=getPosition();
        BlockPos best=null;double score=here.distanceSq(escapeGoal);
        for(EnumFacing f:EnumFacing.HORIZONTALS)for(int dy=0;dy<=1;dy++){
            BlockPos p=here.offset(f).up(dy);
            if(!standable(p)||!world.getWorldBorder().contains(p))continue;
            double d=p.distanceSq(escapeGoal);
            if(d<score){score=d;best=p;}
        }
        if(best!=null){double dx=best.getX()+.5-posX,dz=best.getZ()+.5-posZ;float want=(float)Math.toDegrees(Math.atan2(dz,dx))-90;lookAt(best.getX()+.5,posY+getEyeHeight(),best.getZ()+.5);if(Math.abs(MathHelper.wrapDegrees(want-rotationYaw))<35){moveForward=.8f;if(onGround&&best.getY()>here.getY())jump();}}
    }
    private BlockPos patrolGoal() {
        if(hasLoadedGun()&&(role()==SurvivorProgression.Role.GUARD||role()==SurvivorProgression.Role.SCOUT)){
            BlockPos ladderTop=home.add(1,3,1);
            if(world.isBlockLoaded(ladderTop)&&world.getBlockState(ladderTop).getBlock()==net.minecraft.init.Blocks.LADDER){
                BlockPos watch=home.add(-1,3,0);
                if(standable(watch))return watch;
            }
        }
        for (int i = 0; i < 8; i++) {
            BlockPos p = home.add(rand.nextInt(25) - 12, rand.nextInt(5) - 2, rand.nextInt(25) - 12);
            if (world.isBlockLoaded(p) && world.isBlockLoaded(p.up()) && world.isBlockLoaded(p.down()) && CellClassifier.isPassable(world, p) && CellClassifier.isPassable(world, p.up())
                    && CellClassifier.isSolidSupport(world, p.down())) return p;
        }
        return home;
    }
    private void markRouteFailure(long packed){
        if(routeFailures.size()>=64)routeFailures.clear();
        routeFailures.put(packed,Math.min(40,routeFailures.get(packed)+8));
    }
    private BlockPos routeGate;
    private boolean openRouteDoor(BlockPos p){
        if(!world.isBlockLoaded(p)||!SurvivorPermissions.interact(this,p))return false;
        var state=world.getBlockState(p);var block=state.getBlock();
        if(!(block instanceof net.minecraft.block.BlockDoor||block instanceof net.minecraft.block.BlockFenceGate)||state.getMaterial()==net.minecraft.block.material.Material.IRON)return false;
        var hit=world.rayTraceBlocks(getPositionEyes(1),new net.minecraft.util.math.Vec3d(p).add(.5,.5,.5),false,true,false);
        if(hit==null||!p.equals(hit.getBlockPos())||getDistanceSqToCenter(p)>16)return false;
        if(block instanceof net.minecraft.block.BlockFenceGate&&Boolean.TRUE.equals(state.getValue(net.minecraft.block.BlockFenceGate.OPEN)))return true;
        boolean result=interactionManager.processRightClickBlock(this,world,getHeldItemMainhand(),EnumHand.MAIN_HAND,p,hit.sideHit,(float)(hit.hitVec.x-p.getX()),(float)(hit.hitVec.y-p.getY()),(float)(hit.hitVec.z-p.getZ()))==EnumActionResult.SUCCESS;
        if(result&&block instanceof net.minecraft.block.BlockFenceGate)routeGate=p.toImmutable();
        return result;
    }
    private boolean climbRoute(BlockPos p){
        BlockPos here=getPosition();
        if(!world.isBlockLoaded(here)||!world.isBlockLoaded(p)||world.getBlockState(p).getBlock()!=net.minecraft.init.Blocks.LADDER)return false;
        var state=world.getBlockState(here);
        if(state.getBlock()!=net.minecraft.init.Blocks.LADDER)state=world.getBlockState(p);
        var facing=state.getValue(net.minecraft.block.BlockLadder.FACING);
        if(Math.abs(posX-(p.getX()+.5))>.8||Math.abs(posZ-(p.getZ()+.5))>.8)return false;
        if(p.getY()>posY+.1){
            lookAt(posX-facing.getXOffset(),posY+getEyeHeight(),posZ-facing.getZOffset());
            moveForward=.65f;
        }else{moveForward=0;moveStrafing=0;}
        return true;
    }
    private void closePassedGate(){
        if(routeGate==null||!world.isBlockLoaded(routeGate))return;
        double d=getDistanceSqToCenter(routeGate);if(d>16){routeGate=null;return;}if(d<2.25)return;
        var state=world.getBlockState(routeGate);
        if(!(state.getBlock() instanceof net.minecraft.block.BlockFenceGate)||!Boolean.TRUE.equals(state.getValue(net.minecraft.block.BlockFenceGate.OPEN))){routeGate=null;return;}
        if(!world.getEntitiesWithinAABB(EntityLivingBase.class,new net.minecraft.util.math.AxisAlignedBB(routeGate).grow(.7)).isEmpty()||!SurvivorPermissions.interact(this,routeGate))return;
        var hit=world.rayTraceBlocks(getPositionEyes(1),new net.minecraft.util.math.Vec3d(routeGate).add(.5,.5,.5),false,true,false);
        if(hit==null||routeGate.equals(hit.getBlockPos()))interactionManager.processRightClickBlock(this,world,getHeldItemMainhand(),EnumHand.MAIN_HAND,routeGate,hit==null?EnumFacing.UP:hit.sideHit,.5f,.5f,.5f);
        routeGate=null;
    }
    private boolean shortGapJump(BlockPos p,double dx,double dz,double distance){
        if(!standable(p)||distance>2.6||getFoodStats().getFoodLevel()<=8)return false;
        if(onGround){
            BlockPos here=getPosition();
            int x=p.getX()-here.getX(),z=p.getZ()-here.getZ(),dy=p.getY()-here.getY();
            if(Math.abs(x)+Math.abs(z)>2||x!=0&&z!=0||dy>1||dy< -1)return false;
            if(!world.isBlockLoaded(here.up(2))||!CellClassifier.isPassable(world,here.up(2)))return false;
            if(Math.abs(x)+Math.abs(z)==2){
                BlockPos gap=here.add(Integer.signum(x),0,Integer.signum(z));
                for(int y=-1;y<=2;y++)if(SurvivorTerrain.hazardous(world,gap.up(y)))return false;
                if(!CellClassifier.isPassable(world,gap)||!CellClassifier.isPassable(world,gap.up())||!CellClassifier.isPassable(world,gap.up(2)))return false;
            }
            setSprinting(true);moveForward=1; jump();return true;
        }
        setSprinting(true);moveForward=distance<.45?0:.85f;return true;
    }
    private void followPath() {
        while(step<path.length()){
            long packed=path.getStep(step);
            BlockPos p=new BlockPos(PackedPos.unpackX(packed),PackedPos.unpackY(packed),PackedPos.unpackZ(packed));
            double dx=p.getX()+.5-posX,dz=p.getZ()+.5-posZ,dy=p.getY()-posY;
            double distance=Math.hypot(dx,dz);
            if(distance<.30&&Math.abs(dy)<.55){step++;continue;}
            byte action=path.getAction(step);
            if(action==PathNodeAction.CLIMB){if(!climbRoute(p)){markRouteFailure(packed);invalidateRoute();}return;}
            if(!world.isBlockLoaded(p)||action>PathNodeAction.FALL||SurvivorTerrain.hazardous(world,p)||SurvivorTerrain.hazardous(world,p.up())||SurvivorTerrain.hazardous(world,p.down())){
                markRouteFailure(packed);invalidateRoute();return;
            }
            float desired=(float)Math.toDegrees(Math.atan2(dz,dx))-90;
            lookAt(p.getX()+.5,posY+getEyeHeight(),p.getZ()+.5);
            if(Math.abs(MathHelper.wrapDegrees(desired-rotationYaw))>25)return;
            if(action==PathNodeAction.SPRINT_JUMP){if(!shortGapJump(p,dx,dz,distance)){markRouteFailure(packed);invalidateRoute();}return;}
            double probe=Math.min(.75,distance),ux=distance<.01?0:dx/distance,uz=distance<.01?0:dz/distance;
            BlockPos near=new BlockPos(posX+ux*probe,posY,posZ+uz*probe);
            if(action==PathNodeAction.WALK&&distance<1.5&&(world.getBlockState(p).getBlock()==net.minecraft.init.Blocks.LADDER||world.isBlockLoaded(getPosition())&&world.getBlockState(getPosition()).getBlock()==net.minecraft.init.Blocks.LADDER&&standable(p))){moveForward=.5f;return;}
            if(action==PathNodeAction.WALK&&!SurvivorTerrain.safeStep(world,posX,posY,posZ,ux*probe,uz*probe)){
                if(openRouteDoor(near)||openRouteDoor(near.up()))return;
                markRouteFailure(packed);invalidateRoute();return;
            }
            if(action==PathNodeAction.FALL&&(dy< -2.5||!standable(p))||action==PathNodeAction.JUMP&&(!standable(p)||dy>1.6)){
                markRouteFailure(packed);invalidateRoute();return;
            }
            if((action==PathNodeAction.JUMP||action==PathNodeAction.FALL)&&distance>2.2){markRouteFailure(packed);invalidateRoute();return;}
            moveForward=.75f;
            if(onGround&&(action==PathNodeAction.JUMP||dy>.4))jump();
            return;
        }
        if(!path.isComplete()&&plannedGoal!=null)nextPlan=Math.min(nextPlan,age+5);
    }

    private void lookAt(double x, double y, double z) {
        double dx = x - posX, dz = z - posZ, dy = y - posY - getEyeHeight();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        rotationYaw += MathHelper.clamp(MathHelper.wrapDegrees(yaw - rotationYaw), -25F, 25F);
        rotationPitch += MathHelper.clamp(MathHelper.wrapDegrees(pitch - rotationPitch), -20F, 20F);
        rotationYawHead = rotationYaw;
    }
}
