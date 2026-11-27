package com.x4yi.automata.compat;
import java.lang.reflect.Method;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.entity.EntityAutomaton;
public final class ManhuntCompat {
    private ManhuntCompat() {}
    private static final class Cotesia {
        static final Method GET, IS_PARASITE, DECIDED;
        static {
            Method get = null, parasite = null, decided = null;
            try {
                get = Class.forName("com.srpcotesia.capability.CapabilityParasitePlayer").getMethod("get", EntityPlayer.class);
                parasite = Class.forName("com.srpcotesia.capability.ParasitePlayer").getMethod("isParasite");
                decided = Class.forName("com.srpcotesia.capability.ParasitePlayer").getMethod("hasMadeDecision");
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                AutomataMod.LOGGER.warn("Manhunt AI: Cotesia API unavailable; player targeting disabled", e);
            }
            GET = get; IS_PARASITE = parasite; DECIDED=decided;
        }
    }
    public static boolean isParasite(EntityPlayer player) {
        if (player.isCreative() || player.isSpectator() || Cotesia.GET == null || Cotesia.IS_PARASITE == null) return false;
        try {
            Object cap = Cotesia.GET.invoke(null, player);
            return cap != null && Boolean.TRUE.equals(Cotesia.IS_PARASITE.invoke(cap));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) { return false; }
    }
    public static boolean confirmedHuman(EntityPlayer player){
        if(player.isCreative()||player.isSpectator())return false;
        if(!net.minecraftforge.fml.common.Loader.isModLoaded("srpcotesia"))return true;
        if(Cotesia.GET==null||Cotesia.IS_PARASITE==null||Cotesia.DECIDED==null)return false;
        try{
            Object cap=Cotesia.GET.invoke(null,player);
            return cap!=null&&Boolean.TRUE.equals(Cotesia.DECIDED.invoke(cap))&&Boolean.FALSE.equals(Cotesia.IS_PARASITE.invoke(cap));
        }catch(ReflectiveOperationException|LinkageError|RuntimeException e){return false;}
    }
    public static boolean isHostile(EntityLivingBase entity) {
        if (entity instanceof EntityPlayer player) return isParasite(player);
        if (entity instanceof EntityAutomaton) return false;
        ResourceLocation key = EntityList.getKey(entity);
        return entity instanceof IMob || (key != null && "srparasites".equals(key.getNamespace()));
    }
}
