package com.x4yi.automata.manhunt;
/** Bounded exploration geometry shared by the server and regression tests. */
public final class SurvivorExplorationPolicy {
    public static final int MAX_RADIUS=128, SCAN_SIZE=25*25*9;
    private SurvivorExplorationPolicy(){}
    public static boolean within(double distanceSquared){return distanceSquared<=MAX_RADIUS*MAX_RADIUS;}
    private static final int[][] OFFSETS=offsets();
    private static int[][] offsets(){
        int[][] list=new int[SCAN_SIZE][];
        for(int k=0;k<SCAN_SIZE;k++)list[k]=new int[]{k%25-12,k/625-4,(k/25)%25-12};
        java.util.Arrays.sort(list,java.util.Comparator.comparingInt(a->a[0]*a[0]+a[2]*a[2]+2*a[1]*a[1]));
        return list;
    }
    public static int[] scanOffset(int k){if(k<0||k>=SCAN_SIZE)throw new IllegalArgumentException();return OFFSETS[k].clone();}
    public static int[] waypoint(int index){
        int k=Math.floorMod(index,48);
        double angle=(k%16)*Math.PI/8;
        int distance=12+(k/16)*8;
        return new int[]{(int)Math.round(Math.cos(angle)*distance),(int)Math.round(Math.sin(angle)*distance)};
    }
}
