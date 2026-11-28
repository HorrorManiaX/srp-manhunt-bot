package com.x4yi.automata.manhunt;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import java.util.Set;
/** Conservative SRP threat handling based on actual registry keys and visible observations.
 * Never assumes a fictional weak point or bypasses damage/adaptation. */
final class SurvivorParasiteTactics {
    private static final Set<String> HEAVY=Set.of("draconite","anc_dreadnaut","anc_overlord","anc_dreadnaut_ten","crux","sim_dragone","hi_golem","vigilante");
    private static final Set<String> VOLATILE=Set.of("carrier_heavy","carrier_light","carrier_flying","bomber_light","bomber_heavy");
    private static final Set<String> AURA=Set.of("pri_reeker","ada_reeker","pri_tozoon","ada_tozoon","pri_bolster","ada_bolster");
    private SurvivorParasiteTactics(){}
    static String path(EntityLivingBase e){ResourceLocation id=EntityList.getKey(e);return id!=null&&id.getNamespace().equals("srparasites")?id.getPath():"";}
    static boolean heavy(EntityLivingBase e){return e!=null&&(HEAVY.contains(path(e))||e.getMaxHealth()>=100);}
    static double minRange(EntityLivingBase e){
        String key=path(e);
        if(HEAVY.contains(key)||e.getMaxHealth()>=100)return 14;
        if(VOLATILE.contains(key)||AURA.contains(key))return 9;
        if(e instanceof EntityPlayer)return 6;
        return 3;
    }
    static boolean noMelee(EntityLivingBase e){return heavy(e)||VOLATILE.contains(path(e))||AURA.contains(path(e));}
    static double risk(EntityLivingBase e){
        String key=path(e);
        if(HEAVY.contains(key))return 1.65;
        if(VOLATILE.contains(key)||AURA.contains(key))return 1.35;
        if(key.startsWith("ada_"))return 1.25;
        return 1;
    }
    static String warning(EntityLivingBase e){
        String key=path(e);
        if(HEAVY.contains(key)||e.getMaxHealth()>=100)return "Тяжелый паразит! ХП "+Math.round(e.getHealth())+", не лезем в ближний бой.";
        if(VOLATILE.contains(key))return "Носитель! Отходим, стреляем с расстояния.";
        if(AURA.contains(key))return "Ядовитый паразит, держите дистанцию!";
        return "";
    }
}
