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
            if(!caller.isEntityAlive()||!live.contains(caller.getUniqueID())||caller.world!=helper.world||now>=r.expires()||helper.continuationPriority(caller,r.need().priority>=75)>=r.need().priority||helper.getDistanceSq(caller)>48*48){
                helper.clearHelp();tasks.remove(helper.getUniqueID());say(helper,caller.getName()+", не могу продолжить помощь: меняю задачу.");continue;
            }
            if(r.need()==SurvivorHelpPolicy.Need.COMBAT||r.need()==SurvivorHelpPolicy.Need.EMERGENCY){
                if(caller.helpNeed()==null||caller.helpNeed().priority<75){helper.clearHelp();tasks.remove(helper.getUniqueID());say(helper,"Угроза миновала, возвращаюсь к своим задачам.");continue;}
                helper.assist(caller,true);continue;
            }
            if(helper.getDistanceSq(caller)<=9&&helper.canEntityBeSeen(caller)){
                var resource=r.need()==SurvivorHelpPolicy.Need.FOOD?SurvivorSupplyPolicy.Resource.FOOD:SurvivorSupplyPolicy.Resource.COBBLESTONE;
                int moved=SurvivorSquad.deliver(helper,caller,resource);
                if(moved>0){say(helper,caller.getName()+", "+pick(helper,"на держи, нашел немного ресов","держи, это могу отдать","на, выручайся")+" ("+moved+").");say(caller,pick(caller,"спасибо братан","о, выручил","красава, спасибо"));}
                else say(helper,caller.getName()+", сейчас не могу передать припасы.");
                helper.clearHelp();tasks.remove(helper.getUniqueID());
            }else helper.assist(caller,false);
        }
        for(var caller:bots){
            var need=caller.helpNeed();
            if(!caller.isEntityAlive()||!caller.autonomousEnabled()||need==null||now<nextRequest.getOrDefault(caller.getUniqueID(),0L)||tasks.values().stream().anyMatch(r->r.requester()==caller))continue;
            SurvivorPlayer helper=null;double distance=32*32;
            for(var other:bots){
                if(other==caller||!other.isEntityAlive()||!other.autonomousEnabled()||other.world!=caller.world||other.homePosition().distanceSq(caller.homePosition())>32*32||tasks.containsKey(other.getUniqueID()))continue;
                double d=caller.getDistanceSq(other);if(d<distance){helper=other;distance=d;}
            }
            if(helper==null)continue;
            nextRequest.put(caller.getUniqueID(),now+600);
            say(caller,helper.getName()+", "+switch(need){
                case FOOD -> pick(caller,"братан, дай че-нибудь пожрать, вообще голяк","еды не осталось, выручишь?","бля, жрать нечего, есть лишнее?");
                case MATERIALS -> pick(caller,"брат, ресы на стены есть? тут вообще голяк","сука, опять стену чинить, есть булыжник?","нужны блоки, иначе нас тут сожрут");
                case COMBAT -> pick(caller,"блять, помоги, тут пиздец с паразитами!","братан, сюда, я один не вывожу!","сука, их тут дохуя, нужна помощь!");
                case EMERGENCY -> pick(caller,"БЛЯТЬ ПОМОГИ, Я ЩАС СДОХНУ!","ПИЗДЕЦ, отхожу, прикрой!","БРАТАН ПОМОГИ СУКА, МЕНЯ СНОСЯТ!");
            });
            boolean combat=need.priority>=75;
            var resource=need==SurvivorHelpPolicy.Need.FOOD?SurvivorSupplyPolicy.Resource.FOOD:SurvivorSupplyPolicy.Resource.COBBLESTONE;
            boolean capable=combat?helper.bestMelee()>=0||helper.hasLoadedGun():SurvivorSquad.available(helper,caller,resource)>0;
            var decision=SurvivorHelpPolicy.decide(need,helper.helpPriority(),capable,helper.canHelpSafely(combat));
            if(decision==SurvivorHelpPolicy.Decision.ACCEPT){
                tasks.put(helper.getUniqueID(),new Request(caller,need,now+600));helper.assist(caller,combat);
                say(helper,caller.getName()+", "+pick(helper,"ща, бегу","давай, попробую помочь","понял, держись, иду"));
            }else say(helper,caller.getName()+", "+switch(decision){
                case BUSY -> pick(helper,"братан, занят, сам тут разгребаю","не могу щас, своя задача горит","погоди, мне сначала свое закончить надо");
                case UNSAFE -> pick(helper,"БЛЯТЬ Я САМ ЩАС УМИРАЮ","сука, у меня тут тоже пиздец","братан, сам еле выживаю");
                default -> pick(helper,"братан, у самого голяк","нечего дать, вообще пусто","сам бы щас не отказался");
            });
            return;
        }
    }
    private static String pick(SurvivorPlayer bot,String... lines){return lines[bot.getRNG().nextInt(lines.length)];}
    private static void say(SurvivorPlayer bot,String text){
        SurvivorTelemetry.event("chat",bot,text);
        for(EntityPlayerMP p:bot.getServer().getPlayerList().getPlayers())
            if(p.world==bot.world&&p.getDistanceSq(bot)<=48*48&&!ManhuntCompat.isHostile(p))
                p.sendMessage(new TextComponentString(bot.getName()+": "+text));
    }
}