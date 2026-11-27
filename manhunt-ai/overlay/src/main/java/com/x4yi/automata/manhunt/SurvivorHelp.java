package com.x4yi.automata.manhunt;
import java.util.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import com.x4yi.automata.compat.ManhuntCompat;

/** Local cooperation with bounded requests, commitments, refusal and real resource transfer. */
final class SurvivorHelp {
    private record Request(SurvivorPlayer requester,SurvivorHelpPolicy.Need need,long expires){}
    private final Map<UUID,Long> nextRequest=new HashMap<>();
    private final Map<UUID,Request> tasks=new HashMap<>();
    void clear(){nextRequest.clear();tasks.clear();}
    void tick(List<SurvivorPlayer> bots,long now){
        var live=new HashSet<UUID>();for(var b:bots)live.add(b.getUniqueID());
        nextRequest.keySet().retainAll(live);tasks.keySet().retainAll(live);
        for(var helper:bots){
            Request r=tasks.get(helper.getUniqueID());
            if(r==null)continue;
            var caller=r.requester();
            if(!caller.isEntityAlive()||!live.contains(caller.getUniqueID())||caller.world!=helper.world||now>=r.expires()||helper.helpPriority()>=r.need().priority||helper.getDistanceSq(caller)>48*48){
                helper.clearHelp();tasks.remove(helper.getUniqueID());say(helper,caller.getName()+", не могу продолжить помощь: меняю задачу.");continue;
            }
            if(r.need()==SurvivorHelpPolicy.Need.COMBAT||r.need()==SurvivorHelpPolicy.Need.EMERGENCY){
                if(caller.helpNeed()==null||caller.helpNeed().priority<75){helper.clearHelp();tasks.remove(helper.getUniqueID());say(helper,"Угроза миновала, возвращаюсь к своим задачам.");continue;}
                helper.assist(caller,true);continue;
            }
            if(helper.getDistanceSq(caller)<=9&&helper.canEntityBeSeen(caller)){
                var resource=r.need()==SurvivorHelpPolicy.Need.FOOD?SurvivorSupplyPolicy.Resource.FOOD:SurvivorSupplyPolicy.Resource.COBBLESTONE;
                int moved=SurvivorSquad.deliver(helper,caller,resource);
                if(moved>0){say(helper,caller.getName()+", передал припасы ("+moved+").");say(caller,"Спасибо, "+helper.getName()+".");}
                else say(helper,caller.getName()+", сейчас не могу передать припасы.");
                helper.clearHelp();tasks.remove(helper.getUniqueID());
            }else helper.assist(caller,false);
        }
        for(var caller:bots){
            var need=caller.helpNeed();
            if(!caller.autonomousEnabled()||need==null||now<nextRequest.getOrDefault(caller.getUniqueID(),0L)||tasks.values().stream().anyMatch(r->r.requester()==caller))continue;
            SurvivorPlayer helper=null;double distance=32*32;
            for(var other:bots){
                if(other==caller||!other.isEntityAlive()||!other.autonomousEnabled()||other.world!=caller.world||other.homePosition().distanceSq(caller.homePosition())>32*32||tasks.containsKey(other.getUniqueID()))continue;
                double d=caller.getDistanceSq(other);if(d<distance){helper=other;distance=d;}
            }
            if(helper==null)continue;
            nextRequest.put(caller.getUniqueID(),now+600);
            say(caller,helper.getName()+", "+switch(need){
                case FOOD -> "нужна еда, запас заканчивается.";
                case MATERIALS -> "нужны материалы для укрепления.";
                case COMBAT -> "нужна поддержка против паразитов!";
                case EMERGENCY -> "срочно нужна помощь, отступаю!";
            });
            boolean combat=need.priority>=75;
            var resource=need==SurvivorHelpPolicy.Need.FOOD?SurvivorSupplyPolicy.Resource.FOOD:SurvivorSupplyPolicy.Resource.COBBLESTONE;
            boolean capable=combat?helper.bestMelee()>=0||helper.hasLoadedGun():SurvivorSquad.available(helper,caller,resource)>0;
            var decision=SurvivorHelpPolicy.decide(need,helper.helpPriority(),capable,helper.canHelpSafely(combat));
            if(decision==SurvivorHelpPolicy.Decision.ACCEPT){
                tasks.put(helper.getUniqueID(),new Request(caller,need,now+600));helper.assist(caller,combat);
                say(helper,caller.getName()+", принято. Иду помогать.");
            }else say(helper,caller.getName()+", "+switch(decision){
                case BUSY -> "не могу, моя задача сейчас важнее.";
                case UNSAFE -> "не могу, сам в опасности.";
                default -> "не могу, нет необходимых припасов или оружия.";
            });
            return;
        }
    }
    private static void say(SurvivorPlayer bot,String text){
        for(EntityPlayerMP p:bot.getServer().getPlayerList().getPlayers())
            if(p.world==bot.world&&p.getDistanceSq(bot)<=48*48&&!ManhuntCompat.isHostile(p))
                p.sendMessage(new TextComponentString("[AI/отряд] "+bot.getName()+": "+text));
    }
}