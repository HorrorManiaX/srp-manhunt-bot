package com.x4yi.automata.manhunt;
import java.util.*;
/** Ordered layered fortification; upgrades use actual resources and existing terrain. */
public final class SurvivorStrongholdPlan {
    public enum Kind { WALL, ROOF, PARAPET, BAFFLE, LIGHT, GATE }
    public record Cell(int x,int y,int z,Kind kind){}
    private SurvivorStrongholdPlan(){}
    public static List<Cell> structure(){
        List<Cell> cells=new ArrayList<>();
        // Core walls; one two-block doorway.
        for(var p:SurvivorBlueprint.perimeter(0,1,2))cells.add(new Cell(p.x(),p.y(),p.z(),Kind.WALL));
        // Solid roof, with a real support chain from outside to inside.
        for(int r=2;r>=0;r--)for(int x=-r;x<=r;x++)for(int z=-r;z<=r;z++)
            if(Math.max(Math.abs(x),Math.abs(z))==r)cells.add(new Cell(x,2,z,Kind.ROOF));
        // Additional parapet on roof, keeps defenders away from unprotected edges.
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)
            if(Math.max(Math.abs(x),Math.abs(z))==2)cells.add(new Cell(x,3,z,Kind.PARAPET));
        // Offset wall outside the doorway. Leaves paths around both sides.
        for(int x=-1;x<=1;x++)for(int y=0;y<2;y++)cells.add(new Cell(x,y,-4,Kind.BAFFLE));
        return List.copyOf(cells);
    }
}
