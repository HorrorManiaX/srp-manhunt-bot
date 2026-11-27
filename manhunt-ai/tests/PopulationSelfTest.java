package com.x4yi.automata.manhunt;
public final class PopulationSelfTest {
    static void require(boolean value,String msg){if(!value)throw new AssertionError(msg);}
    public static void main(String[] args){
        int cases=0;
        for(int requested=-2;requested<=10;requested++)for(int active=0;active<=8;active++)for(boolean enabled:new boolean[]{false,true})for(boolean stress:new boolean[]{false,true})for(long now:new long[]{0,199,200,201}){
            int cap=Math.max(0,Math.min(6,requested));
            require(SurvivorPopulationPolicy.capacity(requested)==cap,"capacity");
            require(SurvivorPopulationPolicy.mayJoin(enabled,stress,active,requested,now,200)==(enabled&&!stress&&active<cap&&now>=200),"join");
            cases++;
        }
        for(int i=0;i<100;i++){
            int delay=SurvivorPopulationPolicy.retryDelay(i);
            require(delay>=200&&delay<=6000,"retry delay");
            require(SurvivorPopulationPolicy.deathDelay(i)>=6000&&SurvivorPopulationPolicy.deathDelay(i)<=24000,"death delay");
        }
        require(SurvivorDefense.overwhelmed(6,20,1,10,true,true),"critical injury");
        require(SurvivorDefense.overwhelmed(20,20,3,10,false,true),"crowd");
        require(!SurvivorDefense.overwhelmed(20,20,1,10,false,true),"normal fight");
        require(SurvivorDefense.overwhelmed(12,20,2,10,false,false),"outnumbered");
        require(SurvivorDefense.overwhelmed(15,20,1,100,false,true),"strong parasite");
        require(!SurvivorDefense.recovery(12,20,18,true,true,25),"don't eat close to threat");
        require(SurvivorDefense.recovery(12,20,18,true,true,225),"heal at distance");
        require(SurvivorDefense.neededBlocks(30,12)==18&&SurvivorDefense.neededBlocks(5,20)==0,"materials");
        System.out.println(cases+" population scenarios, 100 backoff cases and 8 defense checks passed");
    }
}
