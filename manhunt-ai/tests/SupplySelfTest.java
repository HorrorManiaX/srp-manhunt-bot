package com.x4yi.automata.manhunt;
import static com.x4yi.automata.manhunt.SurvivorSupplyPolicy.*;
import static com.x4yi.automata.manhunt.SurvivorProgression.Role;
public final class SupplySelfTest {
    public static void main(String[] args) {
        int cases=0;
        for(Resource r:Resource.values())for(Role from:Role.values())for(Role to:Role.values())for(int stock=0;stock<=64;stock++)for(int dest=0;dest<=32;dest++){
            int n=transfer(r,from,to,stock,dest);
            if(n<0||n>8||n>stock||n>Math.max(0,stock-reserve(r,from))||n>Math.max(0,desired(r,to)-dest))
                throw new AssertionError("reserve or deficit violation");
            if(n!=Math.min(8,Math.min(Math.max(0,stock-reserve(r,from)),Math.max(0,desired(r,to)-dest))))
                throw new AssertionError("incorrect amount");
            cases++;
        }
        for(var style:SurvivorPersonality.Style.values()){
            if(SurvivorPersonality.description(style).isBlank()||SurvivorPersonality.share(style,3,"coal","AI_B").isBlank()||SurvivorPersonality.acknowledge(style,"AI_A").isBlank())throw new AssertionError(style);
        }
        System.out.println(cases+" supply policy combinations and 6 personality styles passed");
    }
}
