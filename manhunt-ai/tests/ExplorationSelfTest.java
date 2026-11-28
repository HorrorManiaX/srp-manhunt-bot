package com.x4yi.automata.manhunt;
public final class ExplorationSelfTest {
    static void check(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args){
        check(SurvivorExplorationPolicy.within(128*128));
        check(!SurvivorExplorationPolicy.within(129*129));
        java.util.Set<String> cells=new java.util.HashSet<>();
        for(int i=0;i<SurvivorExplorationPolicy.SCAN_SIZE;i++){
            int[] p=SurvivorExplorationPolicy.scanOffset(i);
            check(Math.abs(p[0])<=12&&Math.abs(p[2])<=12&&Math.abs(p[1])<=4);
            check(cells.add(java.util.Arrays.toString(p)));
        }
        java.util.Set<String> waypoints=new java.util.HashSet<>();
        for(int i=0;i<48;i++){
            int[] p=SurvivorExplorationPolicy.waypoint(i);
            check(p[0]*p[0]+p[1]*p[1]<48*48);
            check(waypoints.add(java.util.Arrays.toString(p)));
            check(java.util.Arrays.equals(p,SurvivorExplorationPolicy.waypoint(i+48)));
        }
        System.out.println("Exploration geometry checks passed");
    }
}
