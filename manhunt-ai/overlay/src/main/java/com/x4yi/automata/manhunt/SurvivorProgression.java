package com.x4yi.automata.manhunt;
/** Pure survival policy; priorities are evaluated with actual inventory counts. */
public final class SurvivorProgression {
    public enum Role { ENGINEER, SCOUT, GUARD, BUILDER, SUPPLIER, MEDIC }
    public enum Task { FOOD, WOOD, PLANKS, STICKS, BENCH, PICKAXE, STONE, STONE_PICKAXE, AXE, SWORD, SHELTER, IDLE }
    public record Stock(int food,int logs,int planks,int sticks,int stone,boolean bench,boolean pickaxe,boolean stonePickaxe,boolean axe,boolean sword,int blocks,boolean sheltered) {}
    private SurvivorProgression() {}
    public static Role role(int hash) { return Role.values()[Math.floorMod(hash,Role.values().length)]; }
    public static Task choose(Stock s, Role role) {
        if(s.food()<2) return Task.FOOD;
        if(s.planks()<4 && s.logs()>0) return Task.PLANKS;
        if(s.planks()<4 && s.logs()==0) return Task.WOOD;
        if(s.sticks()<2) return Task.STICKS;
        if(!s.bench()) return Task.BENCH;
        if(!s.pickaxe()) return Task.PICKAXE;
        if(s.stone()<3 && !s.stonePickaxe()) return Task.STONE;
        if(!s.stonePickaxe()) return Task.STONE_PICKAXE;
        if(!s.sword()) return Task.SWORD;
        if(!s.axe() && s.stone()>=3) return Task.AXE;
        if(!s.sheltered() && role==Role.BUILDER && s.blocks()>=15) return Task.SHELTER;
        if(s.logs()<4 && s.planks()<16) return Task.WOOD;
        if(s.stone()<16) return Task.STONE;
        if(!s.sheltered() && s.blocks()>=15) return Task.SHELTER;
        return Task.IDLE;
    }
}
