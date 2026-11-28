package com.x4yi.automata.manhunt;
public final class BrainSelfTest {
    static void expect(SurvivorBrain.Action expected, float health, boolean enemy, boolean gun, boolean melee, double distance, boolean explosive, boolean hazard, boolean hungry, boolean build, boolean loot) {
        var input=new SurvivorBrain.Situation(health,20,hungry,true,enemy,true,gun,melee,distance,25,explosive,hazard,build,loot);
        if(SurvivorBrain.choose(input)!=expected) throw new AssertionError(expected+" != "+SurvivorBrain.choose(input));
    }
    public static void main(String[] args) {
        var A=SurvivorBrain.Action.class;
        expect(SurvivorBrain.Action.EVADE,20,true,true,true,4,false,true,false,true,true);
        expect(SurvivorBrain.Action.RETREAT,6,true,true,true,64,false,false,false,false,false);
        expect(SurvivorBrain.Action.RETREAT,20,true,true,true,25,true,false,false,false,false);
        expect(SurvivorBrain.Action.RANGED,20,true,true,true,100,false,false,false,false,false);
        expect(SurvivorBrain.Action.MELEE,20,true,false,true,4,false,false,false,false,false);
        expect(SurvivorBrain.Action.APPROACH,20,true,false,true,100,false,false,false,false,false);
        expect(SurvivorBrain.Action.RETREAT,20,true,false,false,100,false,false,false,false,false);
        expect(SurvivorBrain.Action.EAT,20,false,false,false,100,false,false,true,true,true);
        expect(SurvivorBrain.Action.BUILD,20,false,false,false,100,false,false,false,true,true);
        expect(SurvivorBrain.Action.SCAVENGE,20,false,false,false,100,false,false,false,false,true);
        expect(SurvivorBrain.Action.PATROL,20,false,false,false,100,false,false,false,false,false);
        if(SurvivorBrain.scanInterval(true,false)>=SurvivorBrain.scanInterval(false,false) || SurvivorBrain.scanInterval(false,true)<=SurvivorBrain.scanInterval(false,false)) throw new AssertionError("cadence");
        System.out.println("12 decision/cadence scenarios passed");
    }
}
