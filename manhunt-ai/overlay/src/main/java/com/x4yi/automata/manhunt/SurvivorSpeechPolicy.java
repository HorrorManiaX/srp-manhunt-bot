package com.x4yi.automata.manhunt;
import java.util.*;
/** Server-thread-only speech admission. World coordination must never depend on speech. */
public final class SurvivorSpeechPolicy {
    private final LinkedHashSet<String> used=new LinkedHashSet<>();
    private final ArrayDeque<String> recent=new ArrayDeque<>();
    private final LinkedHashMap<String,Long> contacts=new LinkedHashMap<>();
    private final LinkedHashMap<String,Long> localNext=new LinkedHashMap<>();
    private long nextLocal;
    public static String normalized(String s){
        return s==null?"":s.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").trim();
    }
    public boolean fresh(String line){
        String n=normalized(line);
        if(n.isEmpty()||used.contains(n))return false;
        Set<String> words=new HashSet<>(Arrays.asList(n.split(" ")));
        if(words.size()<4)return true;
        for(String prev:recent){
            Set<String> old=new HashSet<>(Arrays.asList(normalized(prev).split(" ")));
            if(old.size()<4)continue;
            int common=0;for(String w:words)if(old.contains(w))common++;
            if(common*100>=78*(words.size()+old.size()-common))return false;
        }
        return true;
    }
    public boolean accept(String line){
        if(!fresh(line))return false;
        used.add(normalized(line));while(used.size()>4096)used.remove(used.iterator().next());
        recent.addLast(line);while(recent.size()>20)recent.removeFirst();
        return true;
    }
    public String context(){return String.join(" | ",recent);}
    public boolean contact(String group,long tick){
        Long last=contacts.get(group);
        // Sliding encounter window: new mobs and repeated hits are not new conversations.
        contacts.put(group,tick);
        while(contacts.size()>64)contacts.remove(contacts.keySet().iterator().next());
        return last==null||tick-last>=900;
    }
    public boolean localReady(String bot,long tick){return tick>=nextLocal&&tick>=localNext.getOrDefault(bot,0L);}
    public void localSent(String bot,long tick){
        nextLocal=tick+100;localNext.put(bot,tick+300);
        while(localNext.size()>64)localNext.remove(localNext.keySet().iterator().next());
    }
    public void clear(){used.clear();recent.clear();contacts.clear();localNext.clear();nextLocal=0;}
}
