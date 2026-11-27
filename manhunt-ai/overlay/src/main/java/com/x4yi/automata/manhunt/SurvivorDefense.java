package com.x4yi.automata.manhunt;

/** Pure threat policy; environmental threats and severe injury always take precedence. */
public final class SurvivorDefense {
    private SurvivorDefense(){}
    public static boolean overwhelmed(float hp,float maxHp,int closeEnemies,float targetHealth,boolean ranged,boolean shield){
        if(maxHp<=0||hp<=maxHp*.35f)return true;
        if(closeEnemies>=3&&!ranged)return true;
        if(closeEnemies>=2&&hp<maxHp*.65f&&!shield)return true;
        return !ranged&&targetHealth>maxHp*3;
    }
    public static boolean recovery(float health,float maxHealth,int hunger,boolean food,boolean visible,double distanceSq){
        return food&&hunger<20&&health<maxHealth*.8f&&(!visible||distanceSq>=144);
    }
    public static int neededBlocks(int missing,int stock){return Math.max(0,Math.min(76,missing)-Math.max(0,stock));}
}
