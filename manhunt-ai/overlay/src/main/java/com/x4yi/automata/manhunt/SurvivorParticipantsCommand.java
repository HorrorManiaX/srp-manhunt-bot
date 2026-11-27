package com.x4yi.automata.manhunt;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.CommandException;
import net.minecraft.server.MinecraftServer;

/** Public, read-only roster. Administrative AI commands stay permission level 2. */
public final class SurvivorParticipantsCommand extends CommandBase {
    @Override public String getName(){return "participants";}
    @Override public String getUsage(ICommandSender sender){return "/participants";}
    @Override public int getRequiredPermissionLevel(){return 0;}
    @Override public boolean checkPermission(MinecraftServer server,ICommandSender sender){return true;}
    @Override public java.util.List<String> getAliases(){return java.util.Collections.singletonList("aicheck");}
    @Override public void execute(MinecraftServer server,ICommandSender sender,String[] args)throws CommandException{
        if(args.length!=0)throw new CommandException(getUsage(sender));
        SurvivorService.INSTANCE.showParticipants(server,sender);
    }
}
