package com.x4yi.automata.manhunt;
import com.mojang.authlib.GameProfile;
import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.movement.CellClassifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
/** Keeps AI sessions separate from the authenticated PlayerList and player count. */
public final class SurvivorService extends CommandBase {
    public static final SurvivorService INSTANCE = new SurvivorService();
    private static final int MAX = 6;
    private final Map<String, SurvivorPlayer> bots = new LinkedHashMap<>();
    private SurvivorService() {}
    private final SurvivorDialogue dialogue=new SurvivorDialogue();
    private void deliverDialogue(){
        for(int i=0;i<2;i++){
            SurvivorDialogue.Answer answer=dialogue.poll();if(answer==null)return;
            SurvivorPlayer bot=bots.get(answer.botKey());
            if(bot==null||!bot.isEntityAlive()||bot.getEntityId()!=answer.entityId()||answer.text().isEmpty())continue;
            EntityPlayerMP player=bot.getServer().getPlayerList().getPlayerByUUID(answer.senderId());
            if(player!=null&&player.world==bot.world)player.sendMessage(new TextComponentString("[AI] "+bot.getName()+": "+answer.text()));
        }
    }
    private double meanAiMs;
    private long lastTickNanos;
    private double meanTickMs=50;
    private int ticks;
    private boolean stressed;
    private final Map<String,Long> squadMessages=new LinkedHashMap<>();
    private void squadAlert(SurvivorPlayer victim,net.minecraft.entity.EntityLivingBase threat){
        long now=victim.world.getTotalWorldTime();
        String key=victim.world.provider.getDimension()+":"+victim.getUniqueID();
        Long last=squadMessages.get(key);
        if(last!=null&&now-last<200)return;
        if(squadMessages.size()>=64)squadMessages.clear();
        squadMessages.put(key,now);
        for(EntityPlayerMP human:victim.getServer().getPlayerList().getPlayers()){
            if(human.world!=victim.world||human.getDistanceSq(victim)>32*32||com.x4yi.automata.compat.ManhuntCompat.isHostile(human))continue;
            human.sendMessage(new TextComponentString("[AI/отряд] "+victim.getName()+": замечен "+threat.getName()+", держитесь настороже."));
        }
    }
    @SubscribeEvent(receiveCanceled=false)
    public void onAttacked(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        if(event.getEntityLiving().world.isRemote || !(event.getEntityLiving() instanceof SurvivorPlayer victim)) return;
        net.minecraft.entity.Entity attacker=event.getSource().getTrueSource();
        if(attacker instanceof net.minecraft.entity.EntityLivingBase living && com.x4yi.automata.compat.ManhuntCompat.isHostile(living)) {
            victim.alert(living,living.getPosition());
            squadAlert(victim,living);
            for(SurvivorPlayer ally:bots.values()) if(ally!=victim && ally.world==victim.world && ally.getDistanceSq(victim)<=144 && ally.canEntityBeSeen(victim)) ally.alert(null,victim.getPosition());
        } else victim.alert(null,victim.getPosition());
    }
    @SubscribeEvent(receiveCanceled=false)
    public void onExplosion(net.minecraftforge.event.world.ExplosionEvent.Start event) {
        if(event.getWorld().isRemote) return;
        net.minecraft.util.math.Vec3d p=event.getExplosion().getPosition();
        BlockPos pos=new BlockPos(p.x,p.y,p.z);
        for(SurvivorPlayer bot:bots.values()) if(bot.world==event.getWorld() && bot.getDistanceSqToCenter(pos)<=144) bot.explosionAlert(pos);
    }
    @Override public String getName() { return "manhuntai"; }
    @Override public String getUsage(ICommandSender sender) { return "/manhuntai spawn <name> | list | park <name> | resume <name> | parked | remove <name> | clear | furnace <name> | smelt <name> <item-id> | goal <name> <item-id|clear> | industry <name> <on|off> | auto <name> <on|off> | fortify <name> | supply <name> | craft <name> <recipe-id> | place <name> <x> <y> <z> [item-id]"; }
    @Override public int getRequiredPermissionLevel() { return 2; }
    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if(args.length==1 && args[0].equals("parked")){
            if(!(sender.getEntityWorld() instanceof WorldServer world))throw new CommandException("Server world required");
            sender.sendMessage(new TextComponentString("Parked in this dimension: "+SurvivorRoster.get(world).names()));return;
        }
        if(args.length==2 && args[0].equals("park")){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null)throw new CommandException("AI survivor not found");
            if(!park(server,bot))throw new CommandException("Snapshot unavailable or roster full");
            sender.sendMessage(new TextComponentString("AI parked: "+args[1]));return;
        }
        if(args.length==2 && args[0].equals("resume")){
            if(!(sender.getEntityWorld() instanceof WorldServer world))throw new CommandException("Server world required");
            String k=key(args[1]);
            SurvivorRoster roster=SurvivorRoster.get(world);
            var snapshot=roster.snapshot(k);
            if(snapshot==null)throw new CommandException("No snapshot in this dimension");
            String name=snapshot.getString("name");
            if(!name.matches("AI_[A-Za-z0-9_]{1,13}")||!key(name).equals(k)||bots.size()>=MAX||bots.containsKey(k)||server.getPlayerList().getPlayerByUsername(name)!=null)throw new CommandException("AI identity or capacity conflict");
            BlockPos pos=SurvivorRoster.position(snapshot),home=SurvivorRoster.home(snapshot);
            if(!standable(world,pos))throw new CommandException("Saved position unavailable; load its chunk and clear the position first");
            UUID uuid=UUID.nameUUIDFromBytes(("ManhuntAI:"+name).getBytes(StandardCharsets.UTF_8));
            SurvivorPlayer bot=new SurvivorPlayer(server,world,new GameProfile(uuid,name),home);
            bot.setPositionAndRotation(pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
            SurvivorRoster.restore(bot,snapshot);
            // Consume snapshot before activating; put it back only if activation fails.
            roster.forget(k);
            try{activate(server,world,bot);}
            catch(CommandException ex){roster.park(k,bot);throw ex;}
            sender.sendMessage(new TextComponentString("Resumed "+name+" (AI)"));return;
        }
        if((args.length==2&&args[0].equals("furnace"))||(args.length==3&&args[0].equals("smelt"))){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null)throw new CommandException("AI survivor not found");
            if(sender.getEntityWorld()!=bot.world)throw new CommandException("Same dimension required");
            if(args[0].equals("furnace")){
                if(!bot.furnaceStation())throw new CommandException("Needs carried furnace and a permitted placement");
            }else if(!bot.smelt(new ResourceLocation(args[2])))throw new CommandException("Needs smeltable carried item and no pending furnace job");
            sender.sendMessage(new TextComponentString("Production task queued"));return;
        }
        if(args.length==3 && args[0].equals("goal")){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null)throw new CommandException("AI survivor not found");
            if(args[2].equals("clear")){bot.clearTechGoal();return;}
            if(!bot.techGoal(new ResourceLocation(args[2])))throw new CommandException("Unknown or disallowed technology goal");
            sender.sendMessage(new TextComponentString("Goal queued; enable autonomous work with /manhuntai auto "+args[1]+" on"));return;
        }
        if(args.length==3 && args[0].equals("industry")){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null)throw new CommandException("AI survivor not found");
            if(!args[2].equals("on")&&!args[2].equals("off"))throw new CommandException("Expected on/off");
            bot.industry(args[2].equals("on"));
            sender.sendMessage(new TextComponentString("Industrial progression "+args[2]+"; requires autonomous work enabled"));return;
        }
        if(args.length==3 && args[0].equals("auto")){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null)throw new CommandException("AI survivor not found");
            if(!args[2].equals("on")&&!args[2].equals("off"))throw new CommandException("Expected on/off");
            bot.autonomous(args[2].equals("on"));
            sender.sendMessage(new TextComponentString(bot.getName()+": autonomous work "+args[2]+" (local protected area only)"));return;
        }
        if (args.length >= 2 && (args[0].equals("craft") || args[0].equals("place") || args[0].equals("supply") || args[0].equals("fortify"))) {
            SurvivorPlayer bot = bots.get(key(args[1]));
            if (bot == null) throw new CommandException("AI survivor not found");
            if (sender.getEntityWorld() != bot.world) throw new CommandException("Same dimension required");
            if (args[0].equals("fortify") && args.length==2) {
                bot.fortify(); sender.sendMessage(new TextComponentString("5x5 perimeter queued; uses actual carried full blocks")); return;
            }
            if (args[0].equals("craft") && args.length==3) {
                if (!SurvivorCrafting.craft(bot,new ResourceLocation(args[2]))) throw new CommandException("Recipe not supported, insufficient ingredients or space");
                sender.sendMessage(new TextComponentString("Crafted: " + args[2])); return;
            }
            if (args[0].equals("place") && (args.length==5 || args.length==6)) {
                BlockPos p = parseBlockPos(sender,args,2,false);
                if(!bot.requestBuild(p,args.length==6 ? new ResourceLocation(args[5]) : null)) throw new CommandException("Invalid or protected build target");
                sender.sendMessage(new TextComponentString("Placement queued, requires real materials and line of sight")); return;
            }
            if (args[0].equals("supply") && args.length==2 && sender instanceof EntityPlayerMP player && player.getDistanceSq(bot)<=16) {
                ItemStack held=player.getHeldItemMainhand();
                if (held.isEmpty()) throw new CommandException("Hold item to transfer");
                int before=held.getCount(); ItemStack moved=held.copy();
                bot.inventory.addItemStackToInventory(moved);
                int transferred=before-moved.getCount();
                if(transferred<=0) throw new CommandException("AI inventory full");
                held.shrink(transferred); player.inventory.markDirty(); bot.inventory.markDirty();
                sender.sendMessage(new TextComponentString("Transferred " + transferred)); return;
            }
            throw new CommandException(getUsage(sender));
        }
        if (args.length == 1 && args[0].equals("list")) {
            sender.sendMessage(new TextComponentString("AI survivors: " + bots.size() + "/" + MAX
                + " | AI avg " + String.format(java.util.Locale.ROOT,"%.2f",meanAiMs) + " ms | tick interval " + String.format(java.util.Locale.ROOT,"%.1f",meanTickMs) + " ms | degraded=" + stressed));
            for (SurvivorPlayer bot : bots.values()) sender.sendMessage(new TextComponentString(bot.getName() + " " + bot.activity() + " HP=" + bot.getHealth()));
            return;
        }
        if (args.length == 1 && args[0].equals("clear")) { clear(server); return; }
        if (args.length == 2 && args[0].equals("remove")) {
            SurvivorPlayer bot = bots.get(key(args[1]));
            if (bot == null) throw new CommandException("AI survivor not found");
            remove(server, bot); return;
        }
        if (args.length != 2 || !args[0].equals("spawn") || !args[1].matches("[A-Za-z0-9_]{1,13}")) throw new CommandException(getUsage(sender));
        if (bots.size() >= MAX) throw new CommandException("AI survivor limit reached");
        String name = "AI_" + args[1];
        if (args[1].regionMatches(true, 0, "AI_", 0, 3)) throw new CommandException("Use a name without the AI_ prefix");
        if (bots.containsKey(key(name)) || server.getPlayerList().getPlayerByUsername(name) != null) throw new CommandException("Name already in use");
        if (!(sender.getEntityWorld() instanceof WorldServer world)) throw new CommandException("Server world required");
        BlockPos pos = sender.getPosition();
        for(WorldServer dimension:server.worlds)if(dimension!=null&&SurvivorRoster.get(dimension).contains(key(name)))throw new CommandException("Parked AI already uses this name; resume it instead");
        if (!standable(world,pos)) throw new CommandException("Choose a loaded, standable location");
        UUID uuid = UUID.nameUUIDFromBytes(("ManhuntAI:" + name).getBytes(StandardCharsets.UTF_8));
        SurvivorPlayer bot = new SurvivorPlayer(server, world, new GameProfile(uuid, name), pos);
        activate(server,world,bot);
        sender.sendMessage(new TextComponentString("Spawned " + name + " (AI, not counted as an authenticated user)"));
    }
    private static boolean standable(WorldServer world,BlockPos pos){return world.isBlockLoaded(pos)&&world.isBlockLoaded(pos.up())&&world.isBlockLoaded(pos.down())&&world.getWorldBorder().contains(pos)&&CellClassifier.isPassable(world,pos)&&CellClassifier.isPassable(world,pos.up())&&CellClassifier.isSolidSupport(world,pos.down());}
    private void activate(MinecraftServer server,WorldServer world,SurvivorPlayer bot)throws CommandException{
        String name=bot.getName();
        broadcast(server, new SPacketPlayerListItem(SPacketPlayerListItem.Action.ADD_PLAYER, bot));
        try {
            if (!world.spawnEntity(bot)) throw new CommandException("AI spawn rejected");
            bots.put(key(name), bot);
        } catch (RuntimeException | CommandException e) {
            bot.cancelPlanning();
            world.removeEntityDangerously(bot);
            broadcast(server, new SPacketPlayerListItem(SPacketPlayerListItem.Action.REMOVE_PLAYER, bot));
            throw new CommandException("AI spawn failed: " + e.getMessage());
        }
    }
    private boolean park(MinecraftServer server,SurvivorPlayer bot){
        if(com.x4yi.automata.compat.ManhuntCompat.isParasite(bot))return false;
        if(!SurvivorRoster.get((WorldServer)bot.world).park(key(bot.getName()),bot))return false;
        remove(server,bot);return true;
    }
    public void shutdown(MinecraftServer server){
        if(server!=null)for(SurvivorPlayer bot:new ArrayList<>(bots.values())){
            try{if(!park(server,bot))AutomataMod.LOGGER.warn("Could not park AI "+bot.getName());}
            catch(RuntimeException ex){AutomataMod.LOGGER.error("AI snapshot failure",ex);}
        }
        clear(server);
        if(server!=null)for(WorldServer world:server.worlds)if(world!=null)world.getPerWorldStorage().saveAllData();
    }
    private static String key(String input) {
        return (input.regionMatches(true, 0, "AI_", 0, 3) ? input : "AI_" + input).toLowerCase(java.util.Locale.ROOT);
    }
    private static void broadcast(MinecraftServer server, SPacketPlayerListItem packet) {
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) if (player.connection != null) player.connection.sendPacket(packet);
    }
    private void remove(MinecraftServer server, SurvivorPlayer bot) {
        bots.remove(key(bot.getName()));
        bot.cancelPlanning();
        bot.world.removeEntityDangerously(bot);
        broadcast(server, new SPacketPlayerListItem(SPacketPlayerListItem.Action.REMOVE_PLAYER, bot));
    }
    public void clear(MinecraftServer server) {
        for (SurvivorPlayer bot : new ArrayList<>(bots.values())) {
            MinecraftServer owner = server != null ? server : bot.getServer();
            if (owner != null) remove(owner, bot);
            else bot.cancelPlanning();
        }
        bots.clear(); dialogue.clear(); squadMessages.clear(); lastTickNanos=0; meanAiMs=0; meanTickMs=50; ticks=0; stressed=false;
    }
    @SubscribeEvent public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player && player.connection != null) {
            for (SurvivorPlayer bot : bots.values()) player.connection.sendPacket(new SPacketPlayerListItem(SPacketPlayerListItem.Action.ADD_PLAYER, bot));
        }
    }
    @SubscribeEvent(receiveCanceled=false) public void onChat(ServerChatEvent event) {
        String msg=event.getMessage();
        for(SurvivorPlayer bot : bots.values()) {
            if (!bot.isEntityAlive() || bot.world != event.getPlayer().world) continue;
            String prefix="@"+bot.getName();
            if(!msg.regionMatches(true,0,prefix,0,prefix.length()) || (msg.length()>prefix.length() && !Character.isWhitespace(msg.charAt(prefix.length())))) continue;
            String reply=bot.reply(msg.substring(prefix.length()));
            if(reply!=null && dialogue.offer(key(bot.getName()),bot.getEntityId(),event.getPlayer().getUniqueID(),bot.activity()+"; здоровье "+bot.getHealth()+"/"+bot.getMaxHealth(),msg.substring(prefix.length()),reply))break;
            if(reply!=null) {
                for(EntityPlayerMP player:bot.getServer().getPlayerList().getPlayers())if(player.world==bot.world && player.getDistanceSq(bot)<=32*32)
                    player.sendMessage(new TextComponentString("[AI] "+bot.getName()+": "+reply));
                if(event.getPlayer().getDistanceSq(bot)>32*32)event.getPlayer().sendMessage(new TextComponentString("[AI] "+bot.getName()+": "+reply));
            }
            break;
        }
    }
    @SubscribeEvent public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long start=System.nanoTime();
        deliverDialogue();
        if(lastTickNanos!=0) meanTickMs=meanTickMs*.95 + Math.min(1000,(start-lastTickNanos)/1_000_000.0)*.05;
        lastTickNanos=start;
        if(++ticks%20==0) {
            if(meanAiMs>4 || meanTickMs>65) stressed=true;
            else if(meanAiMs<2 && meanTickMs<55) stressed=false;
        }
        for (SurvivorPlayer bot : new ArrayList<>(bots.values())) {
            MinecraftServer server = bot.getServer();
            if (server == null) continue;
            if (!bot.isEntityAlive() || com.x4yi.automata.compat.ManhuntCompat.isParasite(bot) || server.getPlayerList().getPlayerByUsername(bot.getName()) != null) { remove(server, bot); continue; }
            try { bot.setStressed(stressed || (System.nanoTime()-start)>4_000_000L); bot.drive(); } catch (RuntimeException | LinkageError e) {
                AutomataMod.LOGGER.error("Manhunt AI disabled after error: " + bot.getName(), e);
                remove(server, bot);
            }
        }
        meanAiMs=meanAiMs*.95+(System.nanoTime()-start)/1_000_000.0*.05;
    }
}
