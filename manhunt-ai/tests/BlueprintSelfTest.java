package com.x4yi.automata.manhunt;
import java.util.HashSet;
public final class BlueprintSelfTest {
    public static void main(String[] args){
        int cases=0;
        for(int workers=1;workers<=6;workers++)for(int levels=1;levels<=2;levels++){
            var all=new HashSet<SurvivorBlueprint.Offset>();
            int smallest=Integer.MAX_VALUE,largest=0;
            for(int worker=0;worker<workers;worker++){
                var plan=SurvivorBlueprint.perimeter(worker,workers,levels);
                var seen=new HashSet<SurvivorBlueprint.Offset>();
                smallest=Math.min(smallest,plan.size());largest=Math.max(largest,plan.size());
                for(var p:plan){
                    if(Math.max(Math.abs(p.x()),Math.abs(p.z()))!=2||p.y()<0||p.y()>=levels||p.x()==0&&p.z()==-2||!all.add(p))throw new AssertionError("invalid or duplicate block");
                    if(p.y()>0&&!seen.contains(new SurvivorBlueprint.Offset(p.x(),p.y()-1,p.z())))throw new AssertionError("unsupported order");
                    seen.add(p);
                }
            }
            if(all.size()!=15*levels||largest-smallest>levels)throw new AssertionError("coverage or balance");
            cases++;
        }
        System.out.println(cases+" blueprint scenarios passed");
    }
}
