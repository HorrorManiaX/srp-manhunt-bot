package com.x4yi.automata.manhunt;
import java.util.*;
import java.util.regex.Pattern;
/** Pure bounded Russian conversation policy. Faction is supplied by the server, never inferred from text. */
public final class SurvivorChatPolicy {
    public enum Side { HUMAN, PARASITE, UNKNOWN }
    public enum Topic { IDENTITY, LOCATION, GREETING, FAREWELL, THANKS, HELP, FOOD, GEAR, THREAT, HEALTH, PLAN, TRADE, GAME, JOKE, YES, NO, QUESTION, CHAT }
    public record State(String activity,int health,int maxHealth,int hunger,boolean food,boolean gun,boolean enemy,boolean safeHelp) {}
    private SurvivorChatPolicy(){}
    public static String normalize(String s){return s==null?"":s.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}_@? ]"," ").replaceAll("\\s+"," ").trim();}
    private static boolean any(String s,String... words){for(String w:words)if(s.contains(w))return true;return false;}
    public static boolean addressed(String msg,String name){
        String m=normalize(msg),n=normalize(name);
        if(n.isEmpty())return false;
        if(Pattern.compile("(?<![\\p{L}\\p{N}_])@?"+Pattern.quote(n)+"(?![\\p{L}\\p{N}_])").matcher(m).find())return true;
        String alias=switch(n){case "alex"->"алекс";case "boris"->"борис|боря";case "mira"->"мира";case "viktor"->"виктор|витя";case "lena"->"лена";case "denis"->"денис|дэн";default->"";};
        return !alias.isEmpty()&&Pattern.compile("(?<![\\p{L}\\p{N}_])(?:"+alias+")(?![\\p{L}\\p{N}_])").matcher(m).find();
    }
    public static Topic topic(String message){
        String s=normalize(message);
        if(any(s,"ты бот","вы боты","это бот","ты ии","вы ии","ты человек","вы люди","реальный игрок","нейросеть","боты или","искусственн","это ии","бот или","npc","нпс"))return Topic.IDENTITY;
        if(any(s,"координат","кординат","где баз","где вы жив","кинь корды","скинь корды","где ваша","куда тп","где ты","где вы","местополож"))return Topic.LOCATION;
        if(any(s,"помог","прикрой","выруч","умира","меня бь","меня хуяр","меня еб","меня снос","срочно сюда","спаси"))return Topic.HELP;
        if(any(s,"пожрат","поесть","голод","еду","еда","хав","жрать","хлеб","мясо","жратв"))return Topic.FOOD;
        if(any(s,"патрон","оруж","брон","автомат","пушк","магазин","ствол","тетру","tetra","крафт","ресурс"))return Topic.GEAR;
        if(any(s,"вагрант","паразит","дракс","драконит","адапт","орда","нексус","маяк","рейд","блум","проповедник","vagrantik"))return Topic.THREAT;
        if(any(s,"хп","здоров","живой","живы","ранен","как ты","как дела","как вы"))return Topic.HEALTH;
        if(any(s,"пока","спокойной","до завтра","я офф","я ливаю"))return Topic.FAREWELL;
        if(any(s,"спасиб","спс","благодар","красава","красавчик","выручил"))return Topic.THANKS;
        if(any(s,"привет","даров","здаров","здорово","салам","всем ку","хай","добрый вечер")||s.equals("ку"))return Topic.GREETING;
        if(any(s,"обмен","торг","куплю","продам","меняю","скинь желез","дай желез"))return Topic.TRADE;
        if(any(s,"что делать","че делать","чо делать","как играть","что за сервер","модпак","сборка","фаза","как начать","как тут","как на этом сервере"))return Topic.GAME;
        if(any(s,"что дела","че дела","чо дела","какие планы","куда дальше","куда ид","что дальше","чем занят","че щас","план"))return Topic.PLAN;
        if(any(s,"ахах","хах","лол","ору","рофл","жиза","прикол"))return Topic.JOKE;
        if(s.matches("(?:(?:да|ага|ок|окей|го|понял|пон|ладно|норм)\\s*)+"))return Topic.YES;
        if(s.matches("(?:(?:нет|неа|не|не знаю|хз)\\s*)+"))return Topic.NO;
        if(s.contains("?")||s.matches("^(?:а |и )?(?:че|чо|что|как|почему|зачем|когда|кто|сколько|где|куда|можно|есть ли)\\b.*"))return Topic.QUESTION;
        return Topic.CHAT;
    }
    public static boolean group(String message){
        String s=normalize(message);return any(s,"ребят","пацан","братан","братцы","народ","кто нибудь","кто-нибудь","всем","все жив","кто онлайн","люди","человеки","кто знает");
    }
    public static boolean relevant(Topic t){return switch(t){case HELP,FOOD,THREAT,TRADE,GAME,GREETING,FAREWELL,GEAR,IDENTITY -> true;default -> false;};}
    private static String pick(Random r,String... values){return values[r.nextInt(values.length)];}
    public static String answer(Topic t,Side side,SurvivorPersonality.Style style,State state,String previous,Random r){
        if(t==Topic.IDENTITY)return pick(r,"да, я агент ИИ на этом сервере","я бот, играю за людей. /participants если интересно","ИИ-агент, не настоящий игрок");
        if(t==Topic.LOCATION)return side==Side.PARASITE?pick(r,"ага щас, ещё карту тебе нарисую","хорошая попытка, вагрант","координаты не солью"):pick(r,"корды в общий чат не кидаю","место лучше не светить","не хочу палить позицию");
        if(side==Side.PARASITE)return switch(t){
            case GREETING -> pick(r,"о, вагрант нарисовался","дарова, только без фокусов","здрасьте, опять вы");
            case HELP,FOOD,TRADE,GEAR -> pick(r,"тебе ещё припасов подкинуть? не, спасибо","не, своим помогаем","не рассчитывай на наше снаряжение","свою фабрику спроси");
            case THREAT -> pick(r,"ну давай, посмотрим","не спеши нас хоронить","много разговоров пока","это мы ещё посмотрим","вагранты опять разошлись");
            case HEALTH,PLAN,GAME -> pick(r,"не твоя забота","разведку по чату ведешь?","а вот это тебе знать необязательно");
            case JOKE -> pick(r,"ахах, хорошая попытка","ну ты даешь","ладно, это было смешно");
            case THANKS,FAREWELL -> pick(r,"бывай","ну ладно","увидимся в игре");
            default -> pick(r,"и че дальше?","не уверен что нам есть о чем договариваться","смотря что ты задумал","ну посмотрим","это ты серьёзно?");
        };
        if(side==Side.UNKNOWN)return switch(t){
            case GREETING -> pick(r,"ку","дарова","привет, ты за кого играешь?");
            case HELP,FOOD,TRADE,GEAR,PLAN,HEALTH -> pick(r,"ты за людей или за паразитов?","сначала скажи за кого играешь","давай без подробностей пока");
            default -> pick(r,"а ты на чьей стороне?","понял тебя","в игре разберемся","а подробнее?");
        };
        return switch(t){
            case GREETING -> switch(style){case FRIENDLY -> pick(r,"о братан, дарова","ку, как сам?","здарова, живой?");case RESERVED -> pick(r,"ку","даров","привет");default -> pick(r,"о, здаров","ку ку","дарова, как оно?","привет, че нового?");};
            case FAREWELL -> pick(r,"давай, бывай","спокойной","до связи братан","удачи, не пропадай");
            case THANKS -> pick(r,"да не за что","нормально, прорвемся","ага, держись там","без проблем","взаимно");
            case HELP -> state.safeHelp()?pick(r,"бля, что там у тебя?","живой ещё? что случилось","давай подробнее, кто напал?","слышу тебя, что за ситуация?"):pick(r,"бля я сам пока не вывожу","сейчас не могу обещать помощь","держись, у меня тут тоже проблемы","что там случилось?");
            case FOOD -> state.food()?pick(r,"еда есть, но в общий чат запасы не обсуждаю","давай разберемся, ты сильно голодный?","понял, тебе пожрать надо?"):pick(r,"братан я сам ищу пожрать","блин у меня еды нет","с хавкой пока беда","сам бы не отказался");
            case GEAR -> state.gun()?pick(r,"ствол есть, но надо следить за патронами","с оружием разберемся","какое оружие тебе нужно?"):pick(r,"пока без заряженной пушки","бля, с патронами туго","надо сначала раздобыть нормальный ствол","что именно ищешь?");
            case THREAT -> state.enemy()?pick(r,"да, у меня тут тоже контакт","сука, опять они","вижу противника, не до шуток сейчас","бля, надо аккуратнее"):pick(r,"у себя пока никого не вижу","серьезно? кто там именно?","дракс или обычные?","блин, только этого не хватало","а кто именно полез?");
            case HEALTH -> state.health()*2<state.maxHealth()?pick(r,"бля, потрепало нормально","еле держусь","не оч, надо восстановиться"):pick(r,"жив пока","да норм вроде","пока не сдох ахах","держусь");
            case PLAN -> switch(state.activity()){case "combat"->pick(r,"дерусь щас","тут бой, потом");case "retreat"->pick(r,"пытаюсь отойти","сейчас не до планов");case "build"->pick(r,"строюсь потихоньку","с укреплениями вожусь");case "scavenge"->pick(r,"ищу ресурсы","пока лутаюсь");case "eat"->pick(r,"ем, а то совсем беда","восстанавливаюсь");default->pick(r,"выживаю пока","пытаюсь разобраться что к чему","ищу чем заняться");};
            case TRADE -> pick(r,"что тебе нужно конкретно?","а что предлагаешь?","давай сначала разберемся с ресурсами");
            case GAME -> pick(r,"сука ну и модпак конечно","тут сначала еда и нормальное укрытие, потом уже стволы","я бы в одиночку на паразитов не лез","крафты тут изменены, лучше проверить JEI","самое сложное тут не сдохнуть пока добываешь ресурсы");
            case JOKE -> pick(r,"ахах жиза","бля ахах","хах, ну это сильно","в голос","ага, знакомая ситуация");
            case YES -> switch(previous){case "HELP"->pick(r,"понял, держись там","ага, смотри по обстановке");case "TRADE","GEAR","FOOD"->pick(r,"ага, тогда понятно","ну ок, разберемся с этим");default->pick(r,"вот и норм","ну тогда ладно","понял","ага, договорились");};
            case NO -> switch(previous){case "HELP"->pick(r,"блин, тогда аккуратнее","а что там сейчас?");case "FOOD","GEAR","TRADE"->pick(r,"а, тогда надо искать дальше","ясно, жаль");default->pick(r,"а, понял","ну бывает","ладно, разберемся","не вопрос");};
            case QUESTION -> previous.isEmpty()?pick(r,"а что именно имеешь в виду?","хз, уточни немного","смотря о чем ты","поясни, не понял с первого раза"):pick(r,"ты про то что до этого обсуждали?","уточни чуть, чтобы не наврать","если про прошлое сообщение, то подробнее скажи");
            default -> pick(r,"понял тебя","ну бывает","а дальше что было?","серьезно?","вот это дела","бля, знакомо","и как в итоге?");
        };
    }
    public static String stylize(String s,SurvivorPersonality.Style style,Random r){
        if(style==SurvivorPersonality.Style.RESERVED&&s.length()>70&&s.contains(",")&&r.nextBoolean())return s.substring(0,s.indexOf(','));
        if(style==SurvivorPersonality.Style.FRIENDLY&&r.nextInt(6)==0&&s.length()<65&&!s.contains("братан"))return s+", братан";
        if(style==SurvivorPersonality.Style.ANALYTICAL&&r.nextInt(4)==0&&s.length()>0)return Character.toUpperCase(s.charAt(0))+s.substring(1)+(s.endsWith("?")||s.endsWith("!")||s.endsWith(".")?"":".");
        return s;
    }
}