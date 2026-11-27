package com.x4yi.automata.manhunt;
public final class SurvivorHelpPolicy {
    public enum Need { MATERIALS(25), FOOD(55), COMBAT(75), EMERGENCY(95);
        public final int priority;Need(int p){priority=p;}
    }
    public enum Decision { ACCEPT, BUSY, UNSAFE, NO_SUPPLIES }
    private SurvivorHelpPolicy(){}
    public static Decision decide(Need need,int ownPriority,boolean capable,boolean safe){
        if(!safe)return Decision.UNSAFE;
        if(ownPriority>=need.priority)return Decision.BUSY;
        return capable?Decision.ACCEPT:Decision.NO_SUPPLIES;
    }
}