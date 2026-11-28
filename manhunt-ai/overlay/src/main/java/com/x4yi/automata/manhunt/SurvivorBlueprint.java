package com.x4yi.automata.manhunt;
import java.util.ArrayList;
import java.util.List;
/** Pure, deterministic partition of a small shelter among a bounded team.
 * A worker owns both levels of each selected column, preserving support order. */
public final class SurvivorBlueprint {
    public record Offset(int x,int y,int z) {}
    private SurvivorBlueprint(){}
    public static List<Offset> shelter(){
        List<Offset> result=new ArrayList<>(perimeter(0,1,2));
        // Roof goes from supported perimeter toward the middle; entrance stays open below.
        for(int radius=2;radius>=0;radius--)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){
            if(Math.max(Math.abs(x),Math.abs(z))==radius)result.add(new Offset(x,2,z));
        }
        return List.copyOf(result);
    }
    public static List<Offset> perimeter(int worker,int workers,int levels){
        if(workers<1||workers>6||worker<0||worker>=workers||levels<1||levels>2)throw new IllegalArgumentException("invalid team");
        List<Offset> result=new ArrayList<>();
        int column=0;
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            if(Math.abs(x)!=2&&Math.abs(z)!=2)continue;
            if(x==0&&z==-2)continue;
            if(column++%workers!=worker)continue;
            for(int y=0;y<levels;y++)result.add(new Offset(x,y,z));
        }
        return List.copyOf(result);
    }
}
