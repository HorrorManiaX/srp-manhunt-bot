package com.x4yi.automata.manhunt;
import static com.x4yi.automata.manhunt.SurvivorCombatPolicy.*;
public final class CombatSelfTest {
    static void expect(Order expected,float hp,double own,double allied,double enemy,int count,boolean ranged,boolean hazard,boolean vagrant){
        var actual=decide(hp,20,own,allied,enemy,count,ranged,hazard,vagrant);
        if(actual!=expected)throw new AssertionError(expected+" != "+actual);
    }
    public static void main(String[] args){
        expect(Order.WITHDRAW,7,40,100,10,1,true,false,false);
        expect(Order.WITHDRAW,20,40,100,10,1,true,true,false);
        expect(Order.WITHDRAW,20,20,0,100,1,false,false,false);
        expect(Order.ENGAGE,20,30,40,30,1,false,false,true);
        expect(Order.COVER,20,30,0,40,1,true,false,false);
        expect(Order.WITHDRAW,20,30,0,40,5,true,false,true);
        expect(Order.ENGAGE,20,40,0,20,1,false,false,false);
        expect(Order.WITHDRAW,20,0,100,20,1,false,false,false);
        if(strength(20,20,true,true)<=strength(20,0,false,true))throw new AssertionError("equipment");
        if(strength(0,20,true,true)!=0)throw new AssertionError("dead");
        System.out.println("10 coordinated combat tests passed");
    }
}