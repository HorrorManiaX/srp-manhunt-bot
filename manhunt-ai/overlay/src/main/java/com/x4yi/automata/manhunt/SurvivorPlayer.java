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
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private CompletableFuture<PathResult> pending;
    private PathResult path = PathResult.EMPTY;
    private int step, age, nextPlan, nextAttack;
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
        this.connection = new NetHandlerPlayServer(server, new NetworkManager(EnumPacketDirection.SERVERBOUND), this) {
            @Override public void sendPacket(Packet<?> packet) { /* No remote client. */ }
        };
        getEntityData().setBoolean("ManhuntAIPlayer", true);
        setGameType(GameType.SURVIVAL);
        setPositionAndRotation(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        inventory.setInventorySlotContents(0, new ItemStack(Items.IRON_SWORD));
        inventory.setInventorySlotContents(1, new ItemStack(Items.BREAD, 8));
    }
    public String activity() { return activity; }
    public void cancelPlanning() { cancelled.set(true); }
    public boolean requestBuild(BlockPos pos) { return requestBuild(pos, null); }
    public boolean requestBuild(BlockPos pos, net.minecraft.util.ResourceLocation item) {
        if (!world.isBlockLoaded(pos) || pos.distanceSq(home) > 12*12 || !world.isAirBlock(pos) || getServer().isBlockProtected((WorldServer)world, pos, this)) return false;
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
        if (msg.contains("патрон") || msg.contains("оруж")) return gunSlot >= 0 ? "Есть заряженное оружие." : "Заряженного оружия сейчас нет.";
        if (msg.contains("паразит") || msg.contains("вагрант")) return target == null ? "Видимых противников сейчас нет." : "Вижу противника: " + target.getName() + ".";
        return "Сейчас " + russianActivity() + ". Я ИИ, пока понимаю вопросы о здоровье, оружии и противниках.";
    }
    private String russianActivity() {
        return switch (activity) { case "combat" -> "в бою"; case "retreat" -> "отступаю"; case "build" -> "укрепляю позицию"; default -> "патрулирую"; };
    }
    private int findFood() {
        for (int i=0; i<9; i++) if (inventory.getStackInSlot(i).getItem() instanceof ItemFood && !inventory.getStackInSlot(i).isEmpty()) return i;
        return -1;
    }
    private int findMelee() {
        for (int i=0; i<9; i++) if (inventory.getStackInSlot(i).getItem() instanceof net.minecraft.item.ItemSword) return i;
        for (int i=0; i<9; i++) if (inventory.getStackInSlot(i).isEmpty()) return i;
        return -1;
    }
    private void placeRequestedBlock() {
        if (buildGoal == null || age < nextBuild || getDistanceSqToCenter(buildGoal) > 16) return;
        nextBuild = age + 20;
        if (!world.isBlockLoaded(buildGoal) || !world.isAirBlock(buildGoal) || getServer().isBlockProtected((WorldServer)world, buildGoal, this)) { buildGoal = null; return; }
        int slot = -1;
        for (int i=0; i<9; i++) if (inventory.getStackInSlot(i).getItem() instanceof ItemBlock block && !inventory.getStackInSlot(i).isEmpty() && (buildItem != null ? buildItem.equals(block.getRegistryName()) : block.getBlock().getDefaultState().isFullCube() && !block.getBlock().hasTileEntity(block.getBlock().getDefaultState()))) { slot=i; break; }
        if (slot < 0) { buildGoal=null; fortification.clear(); return; }
        for (EnumFacing side : EnumFacing.values()) {
            BlockPos support = buildGoal.offset(side.getOpposite());
            if (!world.isBlockLoaded(support) || world.isAirBlock(support) || getServer().isBlockProtected((WorldServer)world, support, this)) continue;
            net.minecraft.util.math.Vec3d hit = new net.minecraft.util.math.Vec3d(support).add(.5 + side.getXOffset()*.5, .5 + side.getYOffset()*.5, .5 + side.getZOffset()*.5);
            net.minecraft.util.math.RayTraceResult trace = world.rayTraceBlocks(getPositionEyes(1F), hit.add(-side.getXOffset()*.01, -side.getYOffset()*.01, -side.getZOffset()*.01), false, true, false);
            if (trace == null || !support.equals(trace.getBlockPos()) || trace.sideHit != side) continue;
            inventory.currentItem=slot; lookAt(hit.x,hit.y,hit.z);
            EnumActionResult result=interactionManager.processRightClickBlock(this, world, getHeldItemMainhand(), EnumHand.MAIN_HAND, support, side, (float)(hit.x-support.getX()), (float)(hit.y-support.getY()), (float)(hit.z-support.getZ()));
            if(result==EnumActionResult.SUCCESS && !world.isAirBlock(buildGoal)) { swingArm(EnumHand.MAIN_HAND); buildGoal=null; }
            return;
        }
    }
    public void drive() {
        if (!isEntityAlive()) return;
        age++;
        planFortification();
        if (age % 20 == 1) {
            gunSlot = SurvivorWeapons.loadedGun(this);
            foodSlot = findFood();
            target = world.getEntitiesWithinAABB(EntityLivingBase.class, getEntityBoundingBox().grow(16),
                    e -> e != this && e.isEntityAlive() && ManhuntCompat.isHostile(e) && canEntityBeSeen(e))
                    .stream().min(java.util.Comparator.comparingDouble(e -> getDistanceSq(e) * SrpTacticalClassifier.classify(e).threatWeightMultiplier())).orElse(null);
        }
        if (target != null && (target.world != world || !target.isEntityAlive() || !ManhuntCompat.isHostile(target) || getDistanceSq(target) > 24 * 24)) target = null;
        boolean retreat = target != null && (getHealth() < 7 || (SrpTacticalClassifier.classify(target).fleeWhenSwelling() && getDistanceSq(target)<36));
        activity = retreat ? "retreat" : target != null ? "combat" : buildGoal != null ? "build" : "patrol";
        if (pending != null && pending.isDone()) {
            try { path = pending.getNow(PathResult.EMPTY); } catch (RuntimeException e) { path = PathResult.EMPTY; }
            pending = null; step = 0;
        }
        if (age >= nextPlan && pending == null) {
            nextPlan = age + 30 + rand.nextInt(25);
            BlockPos goal = retreat ? home : target != null ? target.getPosition() : buildGoal != null ? buildGoal : patrolGoal();
            if (goal != null && world.isBlockLoaded(goal) && getDistanceSqToCenter(goal) < 48 * 48) {
                pending = PathfindingExecutor.submitServerPath(world, getPosition(), goal, 0,
                        new PathfindingSettings(false, false, false, 2048, 2, 0, 10_000_000L), cancelled);
            }
        }
        moveForward = 0; moveStrafing = 0;
        setSprinting(false);
        if (target != null && !retreat && gunSlot >= 0 && canEntityBeSeen(target) && getDistanceSq(target) <= 24*24) {
            if (isHandActive()) stopActiveHand();
            inventory.currentItem = gunSlot;
            lookAt(target.posX, target.posY + target.getEyeHeight() * .75, target.posZ);
            if (age >= nextAttack) nextAttack = age + SurvivorWeapons.fire(this,target);
        } else if (target != null && !retreat && getDistanceSq(target) <= 8.0 && canEntityBeSeen(target)) {
            if (isHandActive()) stopActiveHand();
            int melee = findMelee();
            if (melee >= 0) inventory.currentItem = melee;
            lookAt(target.posX, target.posY + target.getEyeHeight(), target.posZ);
            if (melee >= 0 && age >= nextAttack && getCooledAttackStrength(0) >= .90F) {
                nextAttack = age + 12 + rand.nextInt(7);
                attackTargetEntityWithCurrentItem(target);
                swingArm(EnumHand.MAIN_HAND);
            }
        } else { followPath(); }
        if (target == null && getFoodStats().needFood() && foodSlot >= 0 && inventory.getStackInSlot(foodSlot).getItem() instanceof ItemFood && !inventory.getStackInSlot(foodSlot).isEmpty()) {
            inventory.currentItem = foodSlot; moveForward = 0;
            if (!isHandActive()) setActiveHand(EnumHand.MAIN_HAND);
        } else {
            if (isHandActive()) stopActiveHand();
            if (target == null && buildGoal != null) placeRequestedBlock();
            if (target == null && buildGoal == null && age % 200 == 37) SurvivorCrafting.autoBasic(this);
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
