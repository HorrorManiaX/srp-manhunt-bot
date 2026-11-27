package com.x4yi.automata.manhunt;
import java.util.HashSet;
public final class StrongholdSelfTest {
    static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        var plan=SurvivorStrongholdPlan.structure();
        var occupied=new HashSet<SurvivorBlueprint.Offset>();
        int walls=0,roof=0,parapet=0,baffle=0;
        for(var p:plan){
            require(occupied.add(new SurvivorBlueprint.Offset(p.x(),p.y(),p.z())),"duplicate position");
            switch(p.kind()){
                case WALL -> walls++;
                case ROOF -> roof++;
                case PARAPET -> parapet++;
                case BAFFLE -> baffle++;
                default -> throw new AssertionError("unexpected block");
            }
            if(p.y()>0){
                boolean support=occupied.contains(new SurvivorBlueprint.Offset(p.x(),p.y()-1,p.z()));
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})support|=occupied.contains(new SurvivorBlueprint.Offset(p.x()+d[0],p.y(),p.z()+d[1]));
                require(support,"no previous structural support "+p);
            }
        }
        require(plan.size()==76&&walls==30&&roof==24&&parapet==16&&baffle==6,"counts");
        require(!occupied.contains(new SurvivorBlueprint.Offset(1,2,1)),"ladder hatch");
        for(int y=0;y<=3;y++)require(occupied.contains(new SurvivorBlueprint.Offset(2,y,1)),"ladder wall support");
        for(int y=0;y<=1;y++)require(!occupied.contains(new SurvivorBlueprint.Offset(0,y,-2)),"entrance");
        require(SurvivorDefense.neededBlocks(76,20)==56,"materials");
        System.out.println("Stronghold blueprint geometry, build order, entrance and ladder support passed");
    }
}