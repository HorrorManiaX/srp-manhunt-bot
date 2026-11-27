package com.x4yi.automata.manhunt;
/** Pure scheduling policy. Capacity is always bounded and recovery is deliberately slow. */
public final class SurvivorPopulationPolicy {
    private SurvivorPopulationPolicy(){}
    public static int capacity(int requested){return Math.max(0,Math.min(6,requested));}
    public static boolean mayJoin(boolean enabled,boolean stressed,int active,int target,long now,long nextJoin){
        return enabled&&!stressed&&active<capacity(target)&&now>=nextJoin;
    }
    public static int retryDelay(int consecutiveFailures){return Math.min(6000,200*(1<<Math.min(5,Math.max(0,consecutiveFailures))));}
    public static int deathDelay(int deaths){return Math.min(24000,6000*Math.max(1,Math.min(4,deaths)));}
}
