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
    private final SurvivorWork work;
    private final SurvivorFurnace furnace;
    private int reloadUntil, nextReload;
    private AtomicBoolean cancelled = new AtomicBoolean();
    private CompletableFuture<PathResult> pending;
    private PathResult path = PathResult.EMPTY;
    private int step, age, nextPlan, nextAttack;
    private int nextScan, nextLootScan, nextInventoryScan, alertUntil, nextEvade;
    private boolean stressed;
    private BlockPos lastThreat, plannedGoal, escapeGoal;
    private int lastThreatTick, stuckTicks, explosionUntil, nextHazardScan;
    private double lastX, lastZ;
    private net.minecraft.entity.item.EntityItem loot;
    private SurvivorBrain.Action previousAction = SurvivorBrain.Action.PATROL;
    private int gunSlot = -1, foodSlot = -1;
    private BlockPos buildGoal;
    private net.minecraft.util.ResourceLocation buildItem;
    private int nextBuild, buildDeadline;
    private final java.util.ArrayDeque<BlockPos> fortification = new java.util.ArrayDeque<>();
    private int nextChat;
    private EntityLivingBase target;
    private String activity = "patrol";
    public SurvivorPlayer(MinecraftServer server, WorldServer world, GameProfile profile, BlockPos pos) {
        super(server, world, profile, new PlayerInteractionManager(world));
        this.home = pos;
        this.work=new SurvivorWork(this,pos);
        this.furnace=new SurvivorFurnace(this);
        this.connection = new NetHandlerPlayServer(server, new NetworkManager(EnumPacketDirection.SERVERBOUND), this) {
            @Override public void sendPacket(Packet<?> packet) { /* No remote client. */ }
        };
        getEntityData().setBoolean("ManhuntAIPlayer", true);
        setGameType(GameType.SURVIVAL);
        setPositionAndRotation(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        nextScan=1+Math.floorMod(profile.getId().hashCode(),10); nextLootScan=1+Math.floorMod(profile.getId().hashCode(),40);
        inventory.setInventorySlotContents(0, new ItemStack(Items.IRON_SWORD));
        inventory.setInventorySlotContents(1, new ItemStack(Items.BREAD, 8));
    }
    BlockPos homePosition(){return home;}
    boolean autonomousEnabled(){return work.isEnabled();}
    String technologyGoalId(){return work.goalId();}
    public String activity() { return activity+"/"+work.status()+"/furnace="+furnace.status(); }
    boolean smelt(net.minecraft.util.ResourceLocation id){return furnace.request(id);}
    boolean furnaceStation(){return buildGoal==null&&furnace.queueStation();}
    void autonomous(boolean enable){work.enabled(enable);invalidateRoute();}
    boolean techGoal(net.minecraft.util.ResourceLocation id){return work.goal(id);}
    void clearTechGoal(){work.clearGoal();}
    void faceBlock(BlockPos p){lookAt(p.getX()+.5,p.getY()+.5,p.getZ()+.5);}
    int equipInventorySlot(int i){if(i<0||i>=36)return -1;if(i>=9){int h=inventory.currentItem;ItemStack old=inventory.getStackInSlot(h);inventory.setInventorySlotContents(h,inventory.getStackInSlot(i));inventory.setInventorySlotContents(i,old);i=h;inventory.markDirty();}inventory.currentItem=i;return i;}
    public void cancelPlanning() { work.clear();invalidateRoute(); }
    private void invalidateRoute() {
        cancelled.set(true);
        cancelled = new AtomicBoolean();
        pending = null; path = PathResult.EMPTY; step = 0; plannedGoal = null; nextPlan = 0;
    }
    void setStressed(boolean stressed) { this.stressed=stressed; }
    void alert(EntityLivingBase source, BlockPos location) {
        if (source != null && (source == this || !ManhuntCompat.isHostile(source))) return;
        if (location == null || getDistanceSqToCenter(location) > 24*24) return;
        lastThreat=location; lastThreatTick=age;
        if(source!=null && age>=alertUntil){var memory=SurvivorMemory.get((WorldServer)world);memory.note(getUniqueID(),"danger",location,world.getTotalWorldTime());memory.count(getUniqueID(),"encounters");}
        alertUntil=age+100; nextScan=age;
        if (source != null && source.world==world && source.isEntityAlive() && canEntityBeSeen(source)) target=source;
    }
    void explosionAlert(BlockPos pos) {
        alert(null,pos); explosionUntil=age+30; nextEvade=age; escapeGoal=null;
    }
    private boolean standable(BlockPos p) {
        return world.isBlockLoaded(p) && world.isBlockLoaded(p.up()) && world.isBlockLoaded(p.down())
            && CellClassifier.isPassable(world,p) && CellClassifier.isPassable(world,p.up())
            && CellClassifier.isSolidSupport(world,p.down()) && !world.getBlockState(p).getMaterial().isLiquid();
    }
    private boolean dangerousHere() {
        BlockPos p=getPosition();
        return age<explosionUntil || isBurning() || isInLava() || (world.isBlockLoaded(p) && world.getBlockState(p).getBlock()==net.minecraft.init.Blocks.FIRE);
    }
    private BlockPos chooseEscape() {
        BlockPos here=getPosition(), threat=age<explosionUntil ? lastThreat : target!=null && canEntityBeSeen(target) ? target.getPosition() : lastThreat;
        BlockPos best=null; double bestScore=-Double.MAX_VALUE;
        for(int i=0;i<16;i++) {
            double a=2*Math.PI*(i%8)/8;
            int radius=i<8?4:8;
            BlockPos base=here.add(Math.round(Math.cos(a)*radius),0,Math.round(Math.sin(a)*radius));
            for(int dy=-1;dy<=1;dy++) {
                BlockPos p=base.up(dy);
                if(!standable(p)) continue;
                double score=(threat==null?0:p.distanceSq(threat))-.12*p.distanceSq(home);
                if(score>bestScore) {best=p;bestScore=score;}
            }
        }
        return best;
    }
    private void scanThreats() {
        java.util.List<EntityLivingBase> candidates=world.getEntitiesWithinAABB(EntityLivingBase.class,getEntityBoundingBox().grow(20),
            e -> e!=this && e.isEntityAlive() && ManhuntCompat.isHostile(e));
        candidates.sort(java.util.Comparator.comparingDouble(e -> getDistanceSq(e)));
        EntityLivingBase best=null; double score=Double.MAX_VALUE;
        for(int i=0;i<Math.min(8,candidates.size());i++) {
            EntityLivingBase e=candidates.get(i);
            if(!canEntityBeSeen(e)) continue;
            double value=getDistanceSq(e)*SrpTacticalClassifier.classify(e).threatWeightMultiplier();
            if(e==target) value*=.75;
            if(value<score) {best=e;score=value;}
        }
        if(best!=null) {target=best;if(age>=explosionUntil){lastThreat=best.getPosition();lastThreatTick=age;}alertUntil=age+100;}
    }
    private void scanHazards() {
        int checked=0;
        for(net.minecraft.entity.Entity e:world.getEntitiesWithinAABBExcludingEntity(this,getEntityBoundingBox().grow(8))) {
            if(++checked>64) break;
            if(e.isDead) continue;
            var id=net.minecraft.entity.EntityList.getKey(e);
            if(SrpTacticalClassifier.isSrpAreaHazardId(id) || SrpTacticalClassifier.isSrpExplosiveId(id)) {
                if(getDistanceSq(e)<36 && canEntityBeSeen(e)) {explosionAlert(e.getPosition());return;}
            } else if(SrpTacticalClassifier.isSrpProjectileId(id) || e instanceof net.minecraft.entity.projectile.EntityArrow) {
                double dx=posX-e.posX,dy=posY+.8-e.posY,dz=posZ-e.posZ;
                double speed=e.motionX*e.motionX+e.motionY*e.motionY+e.motionZ*e.motionZ;
                if(speed<.01) continue;
                double t=(dx*e.motionX+dy*e.motionY+dz*e.motionZ)/speed;
                if(t<0 || t>8) continue;
                double x=dx-e.motionX*t,y=dy-e.motionY*t,z=dz-e.motionZ*t;
                if(x*x+y*y+z*z<2.25 && canEntityBeSeen(e)) {explosionAlert(e.getPosition());return;}
            }
        }
    }
    private void scanLoot() {
        loot=null; double distance=144; int checked=0;
        for(net.minecraft.entity.item.EntityItem item:world.getEntitiesWithinAABB(net.minecraft.entity.item.EntityItem.class,getEntityBoundingBox().grow(12))) {
            if(++checked>48) break;
            if(item.isDead || item.getItem().isEmpty() || item.cannotPickup() || item.getOwner()!=null) continue;
            ItemStack stack=item.getItem();
            if(!(stack.getItem() instanceof ItemFood || stack.getItem() instanceof net.minecraft.item.ItemSword || stack.getItem() instanceof ItemBlock)) continue;
            double d=getDistanceSq(item);
            if(d<distance && canEntityBeSeen(item)) {loot=item;distance=d;}
        }
    }
    public boolean requestBuild(BlockPos pos) { return requestBuild(pos, null); }
    public boolean requestBuild(BlockPos pos, net.minecraft.util.ResourceLocation item) {
        if (!world.isBlockLoaded(pos) || pos.distanceSq(home) > 12*12 || !world.isAirBlock(pos) || !SurvivorPermissions.allowed(this,pos,true)) return false;
        buildGoal = pos; buildItem = item; buildDeadline = age + 400; nextPlan = 0; return true;
    }
    public void fortify() {
        fortification.clear(); buildGoal=null;
        for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) if((Math.abs(x)==2 || Math.abs(z)==2) && !(x==0 && z==-2)) fortification.add(home.add(x,0,z));
    }
    private void planFortification() {
        if(buildGoal!=null && age>=buildDeadline) buildGoal=null;
        if(buildGoal==null && !fortification.isEmpty() && age%20==1) {
            BlockPos p=fortification.removeFirst();
            if(world.isBlockLoaded(p.down()) && CellClassifier.isSolidSupport(world,p.down())) requestBuild(p);
        }
    }
    public String reply(String message) {
        if (age < nextChat) return null;
        nextChat = age + 200;
        String msg = message.toLowerCase(java.util.Locale.ROOT);
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
    private void equipVanillaArmor(){
        for(int i=0;i<36;i++){
            ItemStack s=inventory.getStackInSlot(i);
            if(s.isEmpty()||!(s.getItem() instanceof net.minecraft.item.ItemArmor armor)||s.getItem().getRegistryName()==null||!"minecraft".equals(s.getItem().getRegistryName().getNamespace()))continue;
            var slot=armor.armorType;
            ItemStack old=getItemStackFromSlot(slot);
            if(!old.isEmpty() && (!(old.getItem() instanceof net.minecraft.item.ItemArmor worn)||worn.damageReduceAmount>=armor.damageReduceAmount))continue;
            inventory.setInventorySlotContents(i,old);
            setItemStackToSlot(slot,s);inventory.markDirty();
        }
        if(getHeldItemOffhand().isEmpty())for(int i=0;i<36;i++){
            ItemStack shield=inventory.getStackInSlot(i);
            if(shield.getItem()!=Items.SHIELD||shield.isEmpty())continue;
            inventory.setInventorySlotContents(i,ItemStack.EMPTY);
            setHeldItem(EnumHand.OFF_HAND,shield);inventory.markDirty();break;
        }
    }
    private int findFood() {
        for (int i=0; i<36; i++) if (inventory.getStackInSlot(i).getItem() instanceof ItemFood && !inventory.getStackInSlot(i).isEmpty()) return i;
        return -1;
    }
    private int findMelee() {
        for (int i=0; i<36; i++) if (inventory.getStackInSlot(i).getItem() instanceof net.minecraft.item.ItemSword) return i;
        return -1;
    }
    private void placeRequestedBlock() {
        if (buildGoal == null || age < nextBuild || getDistanceSqToCenter(buildGoal) > 16) return;
        nextBuild = age + 20;
        if (!world.isBlockLoaded(buildGoal) || !world.isAirBlock(buildGoal) || !SurvivorPermissions.allowed(this,buildGoal,true)) { buildGoal = null; return; }
        int slot = -1;
        for (int i=0; i<36; i++) if (inventory.getStackInSlot(i).getItem() instanceof ItemBlock block && !inventory.getStackInSlot(i).isEmpty() && (buildItem != null ? buildItem.equals(block.getRegistryName()) : block.getBlock().getDefaultState().isFullCube() && !block.getBlock().hasTileEntity(block.getBlock().getDefaultState()))) { slot=i; break; }
        if (slot < 0) { buildGoal=null; fortification.clear(); return; }
        for (EnumFacing side : EnumFacing.values()) {
            BlockPos support = buildGoal.offset(side.getOpposite());
            if (!world.isBlockLoaded(support) || world.isAirBlock(support) || !SurvivorPermissions.allowed(this,support,true)) continue;
            net.minecraft.util.math.Vec3d hit = new net.minecraft.util.math.Vec3d(support).add(.5 + side.getXOffset()*.5, .5 + side.getYOffset()*.5, .5 + side.getZOffset()*.5);
            net.minecraft.util.math.RayTraceResult trace = world.rayTraceBlocks(getPositionEyes(1F), hit.add(-side.getXOffset()*.01, -side.getYOffset()*.01, -side.getZOffset()*.01), false, true, false);
            if (trace == null || !support.equals(trace.getBlockPos()) || trace.sideHit != side) continue;
            equipInventorySlot(slot); lookAt(hit.x,hit.y,hit.z);
            EnumActionResult result=interactionManager.processRightClickBlock(this, world, getHeldItemMainhand(), EnumHand.MAIN_HAND, support, side, (float)(hit.x-support.getX()), (float)(hit.y-support.getY()), (float)(hit.z-support.getZ()));
            if(result==EnumActionResult.SUCCESS && !world.isAirBlock(buildGoal)) { swingArm(EnumHand.MAIN_HAND); furnace.placed(buildGoal); buildGoal=null; }
            return;
        }
    }
    public void drive() {
        if (!isEntityAlive()) return;
        age++;
        planFortification();
        if(age>=nextInventoryScan) {
            nextInventoryScan=age+10;
            if(target==null&&!stressed&&age%100<10)equipVanillaArmor();
            gunSlot=SurvivorWeapons.loadedGun(this);foodSlot=findFood();
        }
        if(age>=nextScan) {
            scanThreats();
            nextScan=age+SurvivorBrain.scanInterval(target!=null || age<alertUntil,stressed);
        }
        if(age>=nextHazardScan && age>=explosionUntil) {nextHazardScan=age+(stressed?12:target!=null?3:8);scanHazards();}
        if(target==null && !stressed && age>=nextLootScan) {nextLootScan=age+40;scanLoot();}
        if(loot!=null && (loot.isDead || loot.cannotPickup() || getDistanceSq(loot)>144)) loot=null;
        if (target != null && (target.world != world || !target.isEntityAlive() || !ManhuntCompat.isHostile(target) || getDistanceSq(target) > 24 * 24)) target = null;
        boolean visible=target!=null && canEntityBeSeen(target);
        if(visible && age>=explosionUntil) {lastThreat=target.getPosition();lastThreatTick=age;}
        if(target!=null && !visible && age-lastThreatTick>60) target=null;
        var profile=SrpTacticalClassifier.classify(target);
        var action=SurvivorBrain.choose(new SurvivorBrain.Situation(getHealth(),getMaxHealth(),getFoodStats().needFood(),foodSlot>=0,
            target!=null,visible,gunSlot>=0,findMelee()>=0,target==null?Double.MAX_VALUE:getDistanceSq(target),
            profile.preferredMinRangeSq(),profile.fleeWhenSwelling(),dangerousHere(),buildGoal!=null,loot!=null));
        boolean retreat=action==SurvivorBrain.Action.RETREAT || action==SurvivorBrain.Action.EVADE;
        activity=retreat?"retreat":target!=null?"combat":action==SurvivorBrain.Action.BUILD?"build":action==SurvivorBrain.Action.SCAVENGE?"scavenge":action==SurvivorBrain.Action.EAT?"eat":"patrol";
        if(action!=previousAction) {invalidateRoute();escapeGoal=null;previousAction=action;work.interrupt();}
        if(action==SurvivorBrain.Action.PATROL && buildGoal==null&&!stressed&&furnace.active())furnace.tick(age);
        if(action==SurvivorBrain.Action.PATROL && buildGoal==null && (!furnace.active()||furnace.destination()==null))work.plan(stressed);
        else work.interrupt();
        BlockPos jobGoal=furnace.active()&&furnace.destination()!=null?furnace.destination():work.destination();
        if(action==SurvivorBrain.Action.PATROL && jobGoal!=null && !jobGoal.equals(plannedGoal) && pending==null)nextPlan=0;
        if(action==SurvivorBrain.Action.PATROL && gunSlot<0 && age>=nextReload && !stressed){
            nextReload=age+100;
            int reload=SurvivorWeapons.reload(this);
            if(reload>0)reloadUntil=age+reload;
        }
        if(retreat && (escapeGoal==null || age>=nextEvade || getDistanceSqToCenter(escapeGoal)<4)) {
            escapeGoal=chooseEscape();nextEvade=age+20;
            if(escapeGoal!=null && !escapeGoal.equals(plannedGoal)) invalidateRoute();
        }
        if(moveForward!=0 && Math.abs(posX-lastX)+Math.abs(posZ-lastZ)<.025) stuckTicks++; else stuckTicks=0;
        lastX=posX;lastZ=posZ;
        if(stuckTicks>16) {invalidateRoute();stuckTicks=0;}
        if (pending != null && pending.isDone()) {
            try { path = pending.getNow(PathResult.EMPTY); } catch (RuntimeException e) { path = PathResult.EMPTY; }
            pending = null; step = 0;
        }
        if (age >= nextPlan && pending == null && action!=SurvivorBrain.Action.RANGED && action!=SurvivorBrain.Action.MELEE && action!=SurvivorBrain.Action.EAT) {
            nextPlan=age+(stressed?60:retreat?15:30)+rand.nextInt(10);
            BlockPos goal=retreat?escapeGoal:target!=null?(visible?target.getPosition():lastThreat):action==SurvivorBrain.Action.BUILD?buildGoal:action==SurvivorBrain.Action.SCAVENGE?loot.getPosition():jobGoal!=null?jobGoal:patrolGoal();
            if(goal!=null) plannedGoal=goal;
            if (goal != null && world.isBlockLoaded(goal) && getDistanceSqToCenter(goal) < 48 * 48) {
                pending = PathfindingExecutor.submitServerPath(world, getPosition(), goal, 0,
                        new PathfindingSettings(false, false, false, 2048, 2, 0, 10_000_000L), cancelled);
            }
        }
        moveForward = 0; moveStrafing = 0;
        setSprinting(false);
        if (target != null && action==SurvivorBrain.Action.RANGED) {
            if (isHandActive()) stopActiveHand();
            gunSlot=equipInventorySlot(gunSlot);
            lookAt(target.posX, target.posY + target.getEyeHeight() * .75, target.posZ);
            if (age >= nextAttack && age>=reloadUntil) nextAttack = age + SurvivorWeapons.fire(this,target);
        } else if (target != null && action==SurvivorBrain.Action.MELEE) {
            if (isHandActive() && (getActiveHand()!=EnumHand.OFF_HAND || age>=nextAttack)) stopActiveHand();
            int melee = findMelee();
            if (melee >= 0) equipInventorySlot(melee);
            lookAt(target.posX, target.posY + target.getEyeHeight(), target.posZ);
            if (melee >= 0 && age >= nextAttack && getCooledAttackStrength(0) >= .90F) {
                nextAttack = age + 12 + rand.nextInt(7);
                attackTargetEntityWithCurrentItem(target);
                swingArm(EnumHand.MAIN_HAND);
            } else if(getHeldItemOffhand().getItem()==Items.SHIELD && !isHandActive() && age<nextAttack) setActiveHand(EnumHand.OFF_HAND);
        } else { followPath(); if(retreat && moveForward>0 && getFoodStats().getFoodLevel()>6) setSprinting(true); }
        if (action==SurvivorBrain.Action.EAT && foodSlot >= 0 && inventory.getStackInSlot(foodSlot).getItem() instanceof ItemFood && !inventory.getStackInSlot(foodSlot).isEmpty()) {
            foodSlot=equipInventorySlot(foodSlot); moveForward = 0;
            if (!isHandActive()) setActiveHand(EnumHand.MAIN_HAND);
        } else {
            if (isHandActive() && !(action==SurvivorBrain.Action.MELEE && getActiveHand()==EnumHand.OFF_HAND && age<nextAttack)) stopActiveHand();
            if (action==SurvivorBrain.Action.BUILD) placeRequestedBlock();
            if(action==SurvivorBrain.Action.SCAVENGE && loot!=null && getDistanceSq(loot)<1.44){loot.onCollideWithPlayer(this);if(loot.isDead)loot=null;}
            if(action==SurvivorBrain.Action.PATROL && buildGoal==null && !stressed && (!furnace.active()||furnace.destination()==null))work.act();
        }
        onUpdateEntity();
    }
    private BlockPos patrolGoal() {
        for (int i = 0; i < 8; i++) {
            BlockPos p = home.add(rand.nextInt(25) - 12, rand.nextInt(5) - 2, rand.nextInt(25) - 12);
            if (world.isBlockLoaded(p) && world.isBlockLoaded(p.up()) && world.isBlockLoaded(p.down()) && CellClassifier.isPassable(world, p) && CellClassifier.isPassable(world, p.up())
                    && CellClassifier.isSolidSupport(world, p.down())) return p;
        }
        return home;
    }
    private void followPath() {
        while (step < path.length()) {
            long packed = path.getStep(step);
            BlockPos p = new BlockPos(PackedPos.unpackX(packed), PackedPos.unpackY(packed), PackedPos.unpackZ(packed));
            double dx = p.getX() + .5 - posX, dz = p.getZ() + .5 - posZ;
            if (dx * dx + dz * dz < .20 && Math.abs(p.getY() - posY) < .65) { step++; continue; }
            byte action = path.getAction(step);
            if (!world.isBlockLoaded(p) || action > PathNodeAction.FALL) { path = PathResult.EMPTY; return; }
            lookAt(p.getX() + .5, posY + getEyeHeight(), p.getZ() + .5);
            moveForward = .75F;
            if (onGround && (action == PathNodeAction.JUMP || p.getY() > posY + .4)) jump();
            return;
        }
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
