package com.x4yi.automata.manhunt;
public final class SpeechSelfTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        var p=new SurvivorSpeechPolicy();
        check(p.contact("threat:0:ordinary",0));
        for(int t=1;t<2400;t++)check(!p.contact("threat:0:ordinary",t));
        check(p.contact("threat:0:ordinary",3300));
        check(p.accept("Паразиты рядом, прикройте меня!"));
        check(!p.accept("ПАРАЗИТЫ рядом — прикройте меня"));
        check(!p.accept("Паразиты рядом, прикройте меня! Бля"));
        check(p.accept("Опа, ещё одна тварь"));
        check(p.localReady("Boris",0));p.localSent("Boris",0);
        check(!p.localReady("Alex",99));check(p.localReady("Alex",100));
        check(!p.localReady("Boris",299));check(p.localReady("Boris",300));
        p.clear();check(p.accept("Паразиты рядом, прикройте меня!"));
        System.out.println("Speech anti-spam checks passed");
    }
}
