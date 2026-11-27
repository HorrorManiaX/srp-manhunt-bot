package com.x4yi.automata.manhunt;
/** Conservative strength estimate. All values are game attributes, not hidden player data. */
public final class SurvivorCombatPolicy {
    public enum Order { ENGAGE, COVER, WITHDRAW }
    private SurvivorCombatPolicy(){}
    public static double strength(float health,float armor,boolean ranged,boolean armed){
        if(!Float.isFinite(health)||health<=0)return 0;
        return Math.min(40,health)*(1+Math.max(0,Math.min(20,armor))*.035)*(ranged?1.45:armed?1:.25);
    }
    public static Order decide(float health,float maxHealth,double own,double allied,double enemy,int nearby,boolean ranged,boolean hazard,boolean vagrant){
        if(hazard||maxHealth<=0||health<maxHealth*.4||own<=0)return Order.WITHDRAW;
        double friendly=own+Math.max(0,allied)*.65;
        double hostile=Math.max(1,enemy)*(1+.22*Math.max(0,nearby-1))*(vagrant?1.25:1);
        double ratio=friendly/hostile;
        if(ratio>=1.25&&health>=maxHealth*.6)return Order.ENGAGE;
        if(ranged&&ratio>=.6&&health>=maxHealth*.5)return Order.COVER;
        return Order.WITHDRAW;
    }
}
