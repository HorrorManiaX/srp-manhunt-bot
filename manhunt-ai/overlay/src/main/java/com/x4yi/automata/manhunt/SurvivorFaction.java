package com.x4yi.automata.manhunt;
import com.x4yi.automata.AutomataMod;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.Loader;
import java.lang.reflect.Method;
/** Use the same server handler as Cotesia's initial choice GUI. Do not change a real player. */
final class SurvivorFaction {
    private SurvivorFaction(){}
    static boolean human(SurvivorPlayer bot){return human(bot,true);}
    static boolean human(SurvivorPlayer bot,boolean allowChoice){
        if(!Loader.isModLoaded("srpcotesia"))return true;
        Object cap=null;Method decided=null,parasite=null;
        try{
            Class<?> provider=Class.forName("com.srpcotesia.capability.CapabilityParasitePlayer");
            cap=provider.getMethod("get",EntityPlayer.class).invoke(null,bot);
            if(cap==null)return false;
            decided=cap.getClass().getMethod("hasMadeDecision");
            parasite=cap.getClass().getMethod("isParasite");
            if(Boolean.TRUE.equals(decided.invoke(cap)))return Boolean.FALSE.equals(parasite.invoke(cap));
            if(!allowChoice||Boolean.TRUE.equals(parasite.invoke(cap)))return false;
            Class<?> choice=Class.forName("com.srpcotesia.init.EnumChoice");
            Object human=choice.getField("HUMAN").get(null);
            Class<?> packet=Class.forName("com.srpcotesia.network.AcceptChoicePacket");
            Object request=packet.getConstructor(choice).newInstance(human);
            packet.getMethod("handle",EntityPlayerMP.class).invoke(request,bot);
            return Boolean.TRUE.equals(decided.invoke(cap))&&Boolean.FALSE.equals(parasite.invoke(cap));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){
            AutomataMod.LOGGER.warn("AI Cotesia choice handler failed; checking server-side decision",e);
            try{return cap!=null&&decided!=null&&parasite!=null&&Boolean.TRUE.equals(decided.invoke(cap))&&Boolean.FALSE.equals(parasite.invoke(cap));}
            catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){return false;}
        }
    }
}
