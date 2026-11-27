package com.x4yi.automata.manhunt;
/** Stable, explicit AI personas. Pure logic; no world or external model dependency. */
public final class SurvivorPersonality {
    public enum Style { PRACTICAL, CAUTIOUS, FRIENDLY, RESERVED, OPTIMISTIC, ANALYTICAL }
    private SurvivorPersonality(){}
    public static Style style(int hash){return Style.values()[Math.floorMod(hash,Style.values().length)];}
    public static String description(Style style){
        return switch(style){
            case PRACTICAL -> "практичный, короткие ответы";
            case CAUTIOUS -> "осторожный, сначала оценивает опасность";
            case FRIENDLY -> "дружелюбный, охотно помогает";
            case RESERVED -> "сдержанный, говорит по делу";
            case OPTIMISTIC -> "спокойно подбадривает союзников";
            case ANALYTICAL -> "аналитический, объясняет свои действия";
        };
    }
    public static String share(Style style,int count,String material,String recipient){
        return switch(style){
            case PRACTICAL -> recipient+", передал "+count+" "+material+".";
            case CAUTIOUS -> recipient+", держи "+count+" "+material+". Остальное оставлю про запас.";
            case FRIENDLY -> recipient+", поделился ресурсами: "+count+" "+material+".";
            case RESERVED -> recipient+": "+count+" "+material+", забирай.";
            case OPTIMISTIC -> recipient+", теперь запас пополнился: "+count+" "+material+".";
            case ANALYTICAL -> recipient+", передал "+count+" "+material+" из излишков.";
        };
    }
    public static String acknowledge(Style style,String sender){
        return switch(style){
            case PRACTICAL, RESERVED -> "Принял, "+sender+".";
            case CAUTIOUS -> "Спасибо, "+sender+". Пригодится.";
            case FRIENDLY -> "Спасибо за помощь, "+sender+"!";
            case OPTIMISTIC -> "Отлично, "+sender+", продолжаем!";
            case ANALYTICAL -> "Запасы пополнены, "+sender+".";
        };
    }
}
