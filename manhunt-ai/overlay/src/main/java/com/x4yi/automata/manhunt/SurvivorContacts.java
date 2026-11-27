package com.x4yi.automata.manhunt;

import java.util.Locale;
import net.minecraft.entity.player.EntityPlayer;
import com.x4yi.automata.compat.ManhuntCompat;

/** Publicly supplied game aliases, not authentication or faction evidence. */
final class SurvivorContacts {
    private SurvivorContacts(){}
    static String alias(String name){
        String n=name.toLowerCase(Locale.ROOT);
        if(n.equals("vagrantik")||n.equals("проповедник")||n.contains("проповедник адской инквизиции"))return "Vagrantik";
        if(n.startsWith("bro")||n.equals("бро"))return "BRO";
        return "";
    }
    static boolean mention(String message,String name){
        String m=message.toLowerCase(Locale.ROOT);
        return name.equals("Vagrantik")?(m.contains("vagrantik")||m.contains("проповедник")):(m.contains("бро")||m.matches("(?s).*\\bbro[a-z0-9_]*\\b.*"));
    }
    static String reply(String message){
        if(mention(message,"Vagrantik"))return "Проповедник — Vagrantik. Будем считать его противником только если Cotesia подтвердит фракцию паразитов. В одиночку не полезем.";
        if(mention(message,"BRO"))return "Бро — игрок с ником на BRO. Если он на стороне людей и рядом, можем прикрыть. Сначала проверим обстановку.";
        return null;
    }
    static boolean friendly(EntityPlayer p){return ManhuntCompat.confirmedHuman(p);}
    static boolean distress(String m){
        m=m.toLowerCase(Locale.ROOT);
        return m.contains("помог")||m.contains("помощ")||m.contains("прикрой")||m.contains("умира")||m.contains("сносят")||m.contains("рейд");
    }
}
