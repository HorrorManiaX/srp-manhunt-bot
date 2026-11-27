package com.x4yi.automata.manhunt;
import static com.x4yi.automata.manhunt.SurvivorProgression.*;
public final class ProgressionSelfTest {
    static Stock stock(int food,int logs,int planks,int sticks,int stone,boolean bench,boolean pick,boolean stonePick,boolean axe,boolean sword,int blocks,boolean sheltered){
        return new Stock(food,logs,planks,sticks,stone,bench,pick,stonePick,axe,sword,blocks,sheltered);
    }
    static void expect(Task expected,Stock s,Role role){
        if(choose(s,role)!=expected)throw new AssertionError(expected+" != "+choose(s,role));
    }
    public static void main(String[] args){
        Role role=Role.SUPPLIER;
        expect(Task.FOOD,stock(0,0,0,0,0,false,false,false,false,false,0,false),role);
        expect(Task.WOOD,stock(5,0,0,0,0,false,false,false,false,false,0,false),role);
        expect(Task.PLANKS,stock(5,3,0,0,0,false,false,false,false,false,0,false),role);
        expect(Task.STICKS,stock(5,3,8,0,0,false,false,false,false,false,8,false),role);
        expect(Task.BENCH,stock(5,3,8,4,0,false,false,false,false,false,8,false),role);
        expect(Task.PICKAXE,stock(5,3,8,4,0,true,false,false,false,false,8,false),role);
        expect(Task.STONE,stock(5,3,8,4,0,true,true,false,false,false,8,false),role);
        expect(Task.STONE_PICKAXE,stock(5,3,8,4,3,true,true,false,false,false,8,false),role);
        expect(Task.SWORD,stock(5,3,8,4,3,true,true,true,false,false,8,false),role);
        expect(Task.AXE,stock(5,3,8,4,3,true,true,true,false,true,8,false),role);
        expect(Task.SHELTER,stock(5,3,8,4,3,true,true,true,true,true,15,false),Role.BUILDER);
        expect(Task.STONE,stock(5,5,16,4,8,true,true,true,true,true,20,true),role);
        expect(Task.IDLE,stock(5,5,16,4,16,true,true,true,true,true,20,true),role);
        if(role(Integer.MIN_VALUE)==null||role(-1)==null)throw new AssertionError("negative role hash");
        System.out.println("14 progression scenarios passed");
    }
}
