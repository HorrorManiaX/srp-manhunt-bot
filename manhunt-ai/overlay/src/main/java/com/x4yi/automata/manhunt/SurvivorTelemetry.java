package com.x4yi.automata.manhunt;
import com.google.gson.JsonObject;
import com.x4yi.automata.AutomataMod;
/** Log genuine agent events in structured and existing Telegram-compatible chat formats.
 * The old SFTP parser understands <name> text but not lifecycle events. Label all AI messages. */
final class SurvivorTelemetry {
    private SurvivorTelemetry(){}
    static void event(String type,SurvivorPlayer player,String text){
        String body=text==null?"":text.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ").trim();
        body=body.substring(0,Math.min(240,body.length()));
        JsonObject data=new JsonObject();
        data.addProperty("type",type);
        data.addProperty("player",player.getName());
        data.addProperty("text",body);
        data.addProperty("ai",true);
        AutomataMod.LOGGER.info("MANHUNT_AI_EVENT "+data.toString());
        if(!player.getName().matches("[A-Za-z0-9_]{1,16}"))return;
        String line=switch(type){
            case "join" -> "ИИ-выживший присоединился к миру.";
            case "leave" -> "ИИ-выживший покинул мир.";
            case "chat" -> body;
            case "death" -> "Погиб: "+body;
            case "kill" -> "Уничтожил противника: "+body;
            default -> "";
        };
        if(!line.isEmpty())AutomataMod.LOGGER.info("<{}> [ИИ] {}",player.getName(),line);
    }
}
