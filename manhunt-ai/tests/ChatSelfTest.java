package com.x4yi.automata.manhunt;
import java.util.*;
public final class ChatSelfTest {
    static void require(boolean yes,String msg){if(!yes)throw new AssertionError(msg);}
    public static void main(String[] args){
        var state=new SurvivorChatPolicy.State("combat",10,20,8,false,false,true,false);
        var style=SurvivorPersonality.Style.PRACTICAL;
        var r=new Random(7);
        require(SurvivorChatPolicy.addressed("боря, помоги","Boris"),"Russian nickname");
        require(SurvivorChatPolicy.addressed("а @Alex че делаешь?","Alex"),"mid-message mention");
        require(!SurvivorChatPolicy.addressed("Alex123","Alex"),"boundary");
        require(SurvivorChatPolicy.topic("БЛЯТЬ ПОМОГИ СУКА ТУТ ПИЗДЕЦ")==SurvivorChatPolicy.Topic.HELP,"distress");
        require(SurvivorChatPolicy.topic("Братан дай мне че нибудь пожрать")==SurvivorChatPolicy.Topic.FOOD,"food");
        require(SurvivorChatPolicy.topic("ты знаешь как на этом сервере вообще че делать?")==SurvivorChatPolicy.Topic.GAME,"game");
        require(SurvivorChatPolicy.topic("ты бот?")==SurvivorChatPolicy.Topic.IDENTITY,"identity");
        require(SurvivorChatPolicy.group("ребят кто знает"),"group");
        var responses=new HashSet<String>();
        for(int i=0;i<40;i++)responses.add(SurvivorChatPolicy.answer(SurvivorChatPolicy.Topic.THREAT,SurvivorChatPolicy.Side.PARASITE,style,state,"",r));
        require(responses.size()>=4,"variation");
        for(var side:SurvivorChatPolicy.Side.values())for(var t:SurvivorChatPolicy.Topic.values()){
            String a=SurvivorChatPolicy.answer(t,side,style,state,"",r);
            require(a!=null&&!a.isBlank()&&a.length()<220,"empty or long");
            if(t==SurvivorChatPolicy.Topic.IDENTITY)require(a.contains("ИИ")||a.contains("бот"),"identity transparency");
        }
        require(!SurvivorChatPolicy.answer(SurvivorChatPolicy.Topic.FOOD,SurvivorChatPolicy.Side.HUMAN,style,state,"",r).contains("еда есть"),"no fabricated supplies");
        System.out.println("Chat policy checks passed");
    }
}