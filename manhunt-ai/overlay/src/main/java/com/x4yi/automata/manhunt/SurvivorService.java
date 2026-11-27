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
    @Override public String getName() { return "manhuntai"; }
    @Override public String getUsage(ICommandSender sender) { return "/manhuntai spawn <name> | list | remove <name> | clear | fortify <name> | supply <name> | craft <name> <recipe-id> | place <name> <x> <y> <z> [item-id]"; }
    @Override public int getRequiredPermissionLevel() { return 2; }
    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
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
            sender.sendMessage(new TextComponentString("AI survivors: " + bots.size() + "/" + MAX));
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
        if (!world.isBlockLoaded(pos) || !CellClassifier.isPassable(world, pos) || !CellClassifier.isPassable(world, pos.up()) || !CellClassifier.isSolidSupport(world, pos.down())) throw new CommandException("Choose a loaded, standable location");
        UUID uuid = UUID.nameUUIDFromBytes(("ManhuntAI:" + name).getBytes(StandardCharsets.UTF_8));
        SurvivorPlayer bot = new SurvivorPlayer(server, world, new GameProfile(uuid, name), pos);
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
        sender.sendMessage(new TextComponentString("Spawned " + name + " (AI, not counted as an authenticated user)"));
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
        bots.clear();
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
            if(reply!=null) event.getPlayer().sendMessage(new TextComponentString("[AI] " + bot.getName() + ": " + reply));
            break;
        }
    }
    @SubscribeEvent public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (SurvivorPlayer bot : new ArrayList<>(bots.values())) {
            MinecraftServer server = bot.getServer();
            if (server == null) continue;
            if (!bot.isEntityAlive() || server.getPlayerList().getPlayerByUsername(bot.getName()) != null) { remove(server, bot); continue; }
            try { bot.drive(); } catch (RuntimeException | LinkageError e) {
                AutomataMod.LOGGER.error("Manhunt AI disabled after error: " + bot.getName(), e);
                remove(server, bot);
            }
        }
    }
}
