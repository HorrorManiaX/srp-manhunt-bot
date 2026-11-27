package com.x4yi.automata.manhunt;
import com.google.gson.JsonObject;
import com.x4yi.automata.AutomataMod;
/** Structured events for existing latest.log/SFTP bridge. No fake Forge login events. */
final class SurvivorTelemetry {
    private SurvivorTelemetry(){}
    static void event(String type,SurvivorPlayer player,String text){
        JsonObject data=new JsonObject();
        data.addProperty("type",type);
        data.addProperty("player",player.getName());
        data.addProperty("text",text==null?"":text.substring(0,Math.min(240,text.length())));
        data.addProperty("ai",true);
        AutomataMod.LOGGER.info("MANHUNT_AI_EVENT "+data.toString());
    }
}
