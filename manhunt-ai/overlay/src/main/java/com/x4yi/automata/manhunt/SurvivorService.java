package com.x4yi.automata.manhunt;
import com.mojang.authlib.GameProfile;
import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.movement.CellClassifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
/** Keeps AI sessions separate from the authenticated PlayerList and player count. */
public final class SurvivorService extends CommandBase {
    public static final SurvivorService INSTANCE = new SurvivorService();
    private static final int MAX = 6;
    private final Map<String, SurvivorPlayer> bots = new LinkedHashMap<>();
    private SurvivorService() {}
    private final SurvivorPopulation population=new SurvivorPopulation();
    private final SurvivorHelp assistance=new SurvivorHelp();
    private final SurvivorDialogue dialogue=new SurvivorDialogue();
    private record ChatTurn(String botKey,int entityId,UUID sender,SurvivorChatPolicy.Side side,int due,int expiry,String message,String reply,SurvivorChatPolicy.Topic topic,long requestId,boolean ambient) {}
    private record ChatMemory(String botKey,SurvivorChatPolicy.Side side,int tick,String message,String reply,SurvivorChatPolicy.Topic topic) {}
    private final java.util.ArrayDeque<ChatTurn> chatQueue=new java.util.ArrayDeque<>();
    private final Map<UUID,ChatMemory> chatMemory=new LinkedHashMap<>();
    private final Map<String,Integer> chatCooldown=new LinkedHashMap<>();
    private final Map<String,java.util.ArrayDeque<String>> chatRecent=new LinkedHashMap<>();
    private final Map<UUID,ChatTurn> modelPending=new LinkedHashMap<>();
    private final SurvivorSpeechPolicy speech=new SurvivorSpeechPolicy();
    private record LocalTurn(SurvivorPlayer bot,int expiry,double range,String[] fallback,boolean combat){}
    private final Map<Long,LocalTurn> localPending=new LinkedHashMap<>();
    private int nextPublicChat,nextAmbientChat;
    private long dialogueSequence;
    private static SurvivorChatPolicy.Side side(EntityPlayerMP p){
        if(com.x4yi.automata.compat.ManhuntCompat.isParasite(p))return SurvivorChatPolicy.Side.PARASITE;
        if(p instanceof SurvivorPlayer)return SurvivorChatPolicy.Side.HUMAN;
        if(com.x4yi.automata.compat.ManhuntCompat.confirmedHuman(p))return SurvivorChatPolicy.Side.HUMAN;
        return SurvivorChatPolicy.Side.UNKNOWN;
    }
    private EntityPlayerMP chatSender(ChatTurn turn,SurvivorPlayer bot){
        if(bot==null)return null;
        if(turn.ambient()){
            for(SurvivorPlayer ally:bots.values())if(ally.getUniqueID().equals(turn.sender()))return ally;
            return null;
        }
        return bot.getServer().getPlayerList().getPlayerByUUID(turn.sender());
    }
    private boolean valid(ChatTurn turn,SurvivorPlayer bot,EntityPlayerMP player){
        return bot!=null&&bot.isEntityAlive()&&bot.getEntityId()==turn.entityId()&&player!=null&&player.isEntityAlive()&&player.world==bot.world&&side(player)==turn.side()&&ticks<=turn.expiry()
            &&(!turn.ambient()||player instanceof SurvivorPlayer ally&&bot.calmForSupply()&&ally.calmForSupply()&&bot.getDistanceSq(ally)<=256);
    }
    private void emit(ChatTurn turn,SurvivorPlayer bot,String answer){
        answer=SurvivorDialogue.sanitize(answer);if(answer.isEmpty()||!speech.accept(answer))return;
        SurvivorTelemetry.event("chat",bot,answer);
        // Public chat, same audience as ServerChatEvent. No tactical secrets or coordinates in replies.
        TextComponentString line=new TextComponentString("<"+bot.getName()+"> "+answer);
        for(EntityPlayerMP p:bot.getServer().getPlayerList().getPlayers())p.sendMessage(line.createCopy());
        if(chatMemory.size()>=96&&!chatMemory.containsKey(turn.sender()))chatMemory.remove(chatMemory.keySet().iterator().next());
        chatMemory.put(turn.sender(),new ChatMemory(turn.botKey(),turn.side(),ticks,turn.message(),answer,turn.topic()));
        var recent=chatRecent.computeIfAbsent(turn.botKey(),k->new java.util.ArrayDeque<>());
        recent.addLast(answer);while(recent.size()>8)recent.removeFirst();
        nextPublicChat=ticks+25;
        if(bot.world instanceof WorldServer world)SurvivorMemory.get(world).rememberConversation(bot.getUniqueID(),turn.sender(),turn.side(),turn.topic());
        if(turn.ambient()&&turn.message().isEmpty()&&chatSender(turn,bot) instanceof SurvivorPlayer ally){
            queueAmbient(ally,bot,answer);
        }
    }
    private void deliverDialogue(){
        for(int i=0;i<2;i++){
            SurvivorDialogue.Answer answer=dialogue.poll();if(answer==null)break;
            LocalTurn local=localPending.remove(answer.requestId());
            if(local!=null){
                if(local.bot().getEntityId()==answer.entityId()&&ticks<=local.expiry()&&(!local.combat()||local.bot().visibleEnemy()!=null)){
                    String line=answer.text();
                    if(line.isEmpty()||line.matches(".*\\d{2,}.*")||!localSpeech(local.bot(),local.range(),line))
                        localSpeech(local.bot(),local.range(),local.fallback());
                }
                continue;
            }
            ChatTurn turn=modelPending.get(answer.senderId());
            if(turn==null||!turn.botKey().equals(answer.botKey())||turn.entityId()!=answer.entityId()||turn.requestId()!=answer.requestId())continue;
            modelPending.remove(answer.senderId());
            SurvivorPlayer bot=bots.get(turn.botKey());
            EntityPlayerMP player=chatSender(turn,bot);
            if(valid(turn,bot,player)&&chatQueue.size()<16){
                String line=answer.text();
                if(line.isEmpty()||line.matches(".*\\d{2,}.*")||chatRecent.getOrDefault(turn.botKey(),new java.util.ArrayDeque<>()).contains(line))line=turn.reply();
                chatQueue.addFirst(new ChatTurn(turn.botKey(),turn.entityId(),turn.sender(),turn.side(),Math.max(ticks,turn.due()),turn.expiry(),turn.message(),line,turn.topic(),turn.requestId(),turn.ambient()));
            }
        }
        if(ticks%20==0){
            var it=modelPending.values().iterator();
            while(it.hasNext()){
                ChatTurn t=it.next();
                if(ticks>=t.expiry()-80){it.remove();if(chatQueue.size()<16)chatQueue.addLast(t);}
            }
            chatMemory.values().removeIf(m->ticks-m.tick()>2400);
            var locals=localPending.values().iterator();
            while(locals.hasNext()){
                LocalTurn t=locals.next();
                if(ticks>=t.expiry()){locals.remove();if(!t.combat()||t.bot().visibleEnemy()!=null)localSpeech(t.bot(),t.range(),t.fallback());}
            }
        }
        if(ticks<nextPublicChat||chatQueue.isEmpty())return;
        ChatTurn turn=null;
        var ready=chatQueue.iterator();
        while(ready.hasNext()){ChatTurn candidate=ready.next();if(candidate.due()<=ticks){turn=candidate;ready.remove();break;}}
        if(turn==null)return;
        SurvivorPlayer bot=bots.get(turn.botKey());
        EntityPlayerMP player=chatSender(turn,bot);
        if(!valid(turn,bot,player))return;
        emit(turn,bot,turn.reply());
    }
    private net.minecraft.util.text.ITextComponent statusOriginal,statusPublished;
    private String lastStatusNames="";
    private void updateAgentStatus(MinecraftServer server){
        var response=server.getServerStatusResponse();
        var current=response.getServerDescription();
        if(current==null)return;
        if(current!=statusPublished){statusOriginal=current;statusPublished=null;lastStatusNames="";}
        String names=bots.values().stream().filter(SurvivorPlayer::isEntityAlive).map(SurvivorPlayer::getName).collect(java.util.stream.Collectors.joining(", "));
        if(names.equals(lastStatusNames))return;
        if(names.isEmpty()){
            if(statusPublished!=null&&current==statusPublished)response.setServerDescription(statusOriginal);
            statusPublished=null;
        }else{
            statusPublished=statusOriginal.createCopy().appendText("\n"+names);
            response.setServerDescription(statusPublished);
        }
        lastStatusNames=names;
    }
    private double meanAiMs;
    private long lastTickNanos;
    private double meanTickMs=50;
    private int ticks;
    private boolean stressed;
    private final Map<String,Long> squadMessages=new LinkedHashMap<>();
    double supportStrength(SurvivorPlayer caller,net.minecraft.entity.EntityLivingBase enemy){
        double support=0;
        for(SurvivorPlayer ally:bots.values()){
            if(ally==caller||ally.world!=caller.world||!ally.isEntityAlive()||ally.getDistanceSq(caller)>144||ally.getDistanceSq(enemy)>256||!ally.canEntityBeSeen(enemy))continue;
            if(ally.fightingSame(enemy)||ally.visibleEnemy()==enemy)support+=ally.combatStrength();
        }
        return support;
    }
    private boolean localAudience(SurvivorPlayer bot,double range){
        if(bot==null||!bot.isEntityAlive()||bot.getServer()==null||bots.get(key(bot.getName()))!=bot)return false;
        for(EntityPlayerMP p:bot.getServer().getPlayerList().getPlayers())
            if(!(p instanceof SurvivorPlayer)&&!(p instanceof net.minecraftforge.common.util.FakePlayer)&&p.isEntityAlive()&&p.world==bot.world&&p.getDistanceSq(bot)<=range*range&&side(p)==SurvivorChatPolicy.Side.HUMAN)return true;
        return false;
    }
    boolean localSpeech(SurvivorPlayer bot,double range,String... options){
        if(options.length==0||!localAudience(bot,range)||!speech.localReady(key(bot.getName()),ticks))return false;
        int start=bot.getRNG().nextInt(options.length);
        for(int i=0;i<options.length;i++){
            String line=SurvivorDialogue.sanitize(options[(start+i)%options.length]);
            if(!speech.accept(line))continue;
            speech.localSent(key(bot.getName()),ticks);
            SurvivorTelemetry.event("chat",bot,line);
            for(EntityPlayerMP p:bot.getServer().getPlayerList().getPlayers())
                if(!(p instanceof SurvivorPlayer)&&!(p instanceof net.minecraftforge.common.util.FakePlayer)&&p.isEntityAlive()&&p.world==bot.world&&p.getDistanceSq(bot)<=range*range&&side(p)==SurvivorChatPolicy.Side.HUMAN)
                    p.sendMessage(new TextComponentString(bot.getName()+": "+line));
            return true;
        }
        return false;
    }
    private void eventSpeech(SurvivorPlayer bot,double range,boolean combat,String fact,String... fallback){
        if(!localAudience(bot,range)||!speech.localReady(key(bot.getName()),ticks)||!localPending.isEmpty())return;
        String context="Боец Купола "+bot.getName()+"; характер "+SurvivorPersonality.description(bot.personality())+"; подтвержденное событие: "+fact;
        long id=++dialogueSequence;
        if(!stressed&&modelPending.isEmpty()&&dialogue.offer(key(bot.getName()),bot.getEntityId(),bot.getUniqueID(),id,context,
            "Одна живая короткая реакция на событие. Не придумывай детали. Не повторяй ни одну из предыдущих реплик: "+speech.context(),"")){
            localPending.put(id,new LocalTurn(bot,ticks+180,range,fallback,combat));
        }else localSpeech(bot,range,fallback);
    }
    void reportOrder(SurvivorPlayer bot,SurvivorCombatPolicy.Order order){
        if(!speech.localReady(key(bot.getName()),ticks)||!localAudience(bot,32))return;
        if(!speech.contact("order:"+bot.getName(),ticks))return;
        switch(order){
            case ENGAGE -> eventSpeech(bot,32,true,"решил атаковать вместе с союзниками","давайте вместе","есть шанс, заходим","вдвоем проще будет","ну че, попробуем");
            case COVER -> eventSpeech(bot,32,true,"решил вести бой с расстояния","держитесь подальше от него","близко не надо","с дистанции проще","не подставляйтесь");
            case WITHDRAW -> eventSpeech(bot,32,true,"решил отойти из-за опасности","не, надо отходить","бля, не вывожу","назад, парни","лучше отступить сейчас");
        }
    }
    void reportVictory(SurvivorPlayer bot){
        eventSpeech(bot,16,false,"действительно уничтожил противника","одного снял","фух, минус один","с этим разобрался","ну хоть этот упал","готов, больше не встает","так, один есть");
    }
    void observedThreat(SurvivorPlayer observer,net.minecraft.entity.EntityLivingBase enemy){
        if(enemy==null||enemy.world!=observer.world||!enemy.isEntityAlive()||!com.x4yi.automata.compat.ManhuntCompat.isHostile(enemy)||!observer.canEntityBeSeen(enemy))return;
        long now=observer.world.getTotalWorldTime();
        String key=observer.world.provider.getDimension()+":"+enemy.getUniqueID();
        Long last=squadMessages.get(key);
        if(last==null||now-last>=160){
            squadMessages.put(key,now);
            while(squadMessages.size()>64)squadMessages.remove(squadMessages.keySet().iterator().next());
            for(SurvivorPlayer ally:bots.values())if(ally!=observer&&ally.world==observer.world&&ally.getDistanceSq(observer)<=32*32)ally.squadContact(enemy,enemy.getPosition());
        }
        if(!localAudience(observer,48))return;
        boolean vagrant=enemy instanceof net.minecraft.entity.player.EntityPlayer;
        boolean heavy=SurvivorParasiteTactics.noMelee(enemy);
        String group="threat:"+observer.world.provider.getDimension()+":"+(vagrant?"vagrant":heavy?"heavy":"ordinary");
        if(!speech.contact(group,ticks))return;
        if(vagrant){
            String alias=SurvivorContacts.alias(enemy.getName());
            if(alias.equals("Vagrantik"))eventSpeech(observer,48,true,"заметил вагранта Проповедника (Vagrantik)","о, Проповедник тут","Vagrantik на контакте","бля, это же Проповедник");
            else eventSpeech(observer,48,true,"заметил вражеского вагранта","вагрант тут","о бля, вагрант","парни, осторожно, вагрант","вижу одного из них");
        }else if(heavy)eventSpeech(observer,48,true,"заметил опасного паразита, ближний бой нежелателен","не подходите к этой твари","бля, держите дистанцию","этого лучше издалека","осторожно с этим");
        else eventSpeech(observer,48,true,"заметил паразита","там зараженный","паразит тут","опа, еще один","осторожнее, контакт","у нас гости","блин, заметил тварь");
    }
    @SubscribeEvent(receiveCanceled=false)
    public void onAttacked(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        if(event.getEntityLiving().world.isRemote || !(event.getEntityLiving() instanceof SurvivorPlayer victim)) return;
        victim.tookDamage();
        net.minecraft.entity.Entity attacker=event.getSource().getTrueSource();
        if(attacker instanceof net.minecraft.entity.EntityLivingBase living && com.x4yi.automata.compat.ManhuntCompat.isHostile(living)) {
            victim.alert(living,living.getPosition());
            observedThreat(victim,living);
            for(SurvivorPlayer ally:bots.values()) if(ally!=victim && ally.world==victim.world && ally.getDistanceSq(victim)<=144 && ally.canEntityBeSeen(victim)) ally.alert(living,living.getPosition());
        } else victim.alert(null,victim.getPosition());
    }
    @SubscribeEvent(receiveCanceled=false)
    public void onDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event){
        if(!event.getEntityLiving().world.isRemote&&event.getEntityLiving() instanceof SurvivorPlayer bot&&bots.get(key(bot.getName()))==bot)SurvivorTelemetry.event("death",bot,event.getSource().getDeathMessage(bot).getUnformattedText());
        if(event.getEntityLiving().world.isRemote)return;
        if(event.getSource().getTrueSource() instanceof SurvivorPlayer bot && bots.get(key(bot.getName()))==bot && com.x4yi.automata.compat.ManhuntCompat.isHostile(event.getEntityLiving())){
            bot.recordVictory();
            SurvivorTelemetry.event("kill",bot,event.getEntityLiving().getName());
        }
    }
    /** Telegram telemetry only: report PvP kills separately from mob kills. */
    @SubscribeEvent(priority=net.minecraftforge.fml.common.eventhandler.EventPriority.LOWEST, receiveCanceled=false)
    public void onPlayerKillTelemetry(net.minecraftforge.event.entity.living.LivingDeathEvent event){
        if(event.getEntityLiving().world.isRemote || !(event.getEntityLiving() instanceof EntityPlayerMP victim) || victim instanceof SurvivorPlayer)return;
        if(event.getSource().getTrueSource() instanceof SurvivorPlayer bot && bots.get(key(bot.getName()))==bot)
            SurvivorTelemetry.event("player_kill",bot,victim.getName());
    }
    @SubscribeEvent(receiveCanceled=false)
    public void onExplosion(net.minecraftforge.event.world.ExplosionEvent.Start event) {
        if(event.getWorld().isRemote) return;
        net.minecraft.util.math.Vec3d p=event.getExplosion().getPosition();
        BlockPos pos=new BlockPos(p.x,p.y,p.z);
        for(SurvivorPlayer bot:bots.values()) if(bot.world==event.getWorld() && bot.getDistanceSqToCenter(pos)<=144) bot.explosionAlert(pos);
    }
    @Override public String getName() { return "manhuntai"; }
    @Override public String getUsage(ICommandSender sender) { return "/manhuntai spawn <name> | reinforce | population <on|off|0..6> | list | park <name> | resume <name> | parked | pause | remove <name> | clear | furnace <name> | smelt <name> <item-id> | vehicle <name> <recipe-id> | preparearena <name> <x> <y> <z> | arena <x> <y> <z> | goal <name> <item-id|clear> | industry <name> <on|off> | auto <name> <on|off> | fortify <name> | squadbuild <name> | lava <name> <x> <y> <z> | defense <name> <x> <y> <z> <ammo-id> <side> | defense <name> clear | supply <name> | craft <name> <recipe-id> | place <name> <x> <y> <z> [item-id]"; }
    @Override public int getRequiredPermissionLevel() { return 2; }
    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if(args.length==1&&args[0].equals("rosterjson")){
            com.google.gson.JsonArray result=new com.google.gson.JsonArray();
            for(SurvivorPlayer bot:bots.values()){
                if(!bot.isEntityAlive())continue;
                com.google.gson.JsonObject row=new com.google.gson.JsonObject();
                row.addProperty("name",bot.getName());row.addProperty("ai",true);
                row.addProperty("hp",bot.getHealth());row.addProperty("max_hp",bot.getMaxHealth());
                result.add(row);
            }
            sender.sendMessage(new TextComponentString("MANHUNT_AI_ROSTER "+result.toString()));return;
        }
        if(args.length==1&&args[0].equals("reinforce")){
            population.reinforce();sender.sendMessage(new TextComponentString("AI reinforcement search scheduled for next second, prioritizing loaded safe areas near human BRO. No forced spawn."));return;
        }
        if(args.length==2 && args[0].equals("population")){
            if(args[1].equals("off"))population.configure(false,population.target());
            else if(args[1].equals("on"))population.configure(true,population.target()==0?4:population.target());
            else {int count=parseInt(args[1],0,MAX);population.configure(count>0,count);}
            sender.sendMessage(new TextComponentString("Automatic AI population: "+population.enabled()+" / target "+population.target()));return;
        }
        if((args.length==5&&args[0].equals("lava"))||(args.length==7&&args[0].equals("defense"))||(args.length==3&&args[0].equals("defense")&&args[2].equals("clear"))){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null||sender.getEntityWorld()!=bot.world)throw new CommandException("AI must be in the same dimension");
            if(args.length==3){bot.clearDefense();sender.sendMessage(new TextComponentString("Defense service cleared"));return;}
            BlockPos pos=parseBlockPos(sender,args,2,false);
            if(args[0].equals("lava")){
                if(!bot.requestLava(pos))throw new CommandException("Requires a carried lava bucket, contained stone basin, safe area and permission");
            }else{
                net.minecraft.util.EnumFacing face=net.minecraft.util.EnumFacing.byName(args[6].toLowerCase(java.util.Locale.ROOT));
                if(face==null||!bot.registerDefense(pos,new ResourceLocation(args[5]),face))throw new CommandException("Requires a preconfigured OMT base, accepted sided inventory, valid ammo ID and permission");
            }
            sender.sendMessage(new TextComponentString("Infrastructure job queued; normal game rules apply"));return;
        }
        if(args.length==2 && args[0].equals("squadbuild")){
            SurvivorPlayer leader=bots.get(key(args[1]));
            if(leader==null||leader.world!=sender.getEntityWorld())throw new CommandException("AI leader must be in this dimension");
            var team=new ArrayList<SurvivorPlayer>();
            team.add(leader);
            for(SurvivorPlayer bot:bots.values()){
                if(bot!=leader&&bot.isEntityAlive()&&bot.world==leader.world&&bot.homePosition().distanceSq(leader.homePosition())<=16&&bot.getDistanceSq(leader)<=144)team.add(bot);
            }
            for(int i=0;i<team.size();i++)team.get(i).queueShelter(leader.homePosition(),i,team.size(),2);
            sender.sendMessage(new TextComponentString("Shared 5x5 two-level wall plan: "+team.size()+" AI workers, 30 blocks, open entrance; actual supplies required."));return;
        }
        if(args.length==1 && args[0].equals("parked")){
            if(!(sender.getEntityWorld() instanceof WorldServer world))throw new CommandException("Server world required");
            sender.sendMessage(new TextComponentString("Parked in this dimension: "+SurvivorRoster.get(world).names()));return;
        }
        if(args.length==1 && args[0].equals("pause")){
            population.configure(false,population.target());
            int parked=0;
            java.util.List<String> failed=new ArrayList<>();
            for(SurvivorPlayer bot:new ArrayList<>(bots.values())){
                try{if(park(server,bot)){population.suppress(bot.getName());parked++;}
                    else failed.add(bot.getName());}
                catch(RuntimeException|LinkageError e){failed.add(bot.getName());AutomataMod.LOGGER.warn("Could not safely park "+bot.getName(),e);}
            }
            sender.sendMessage(new TextComponentString("AI paused; saved and parked: "+parked+"; remaining active: "+bots.size()+(failed.isEmpty()?"":"; failed: "+String.join(", ",failed))));
            return;
        }
        if(args.length==2 && args[0].equals("park")){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null)throw new CommandException("AI survivor not found");
            if(!park(server,bot))throw new CommandException("Snapshot unavailable or roster full");
            population.suppress(bot.getName());
            sender.sendMessage(new TextComponentString("AI parked: "+args[1]));return;
        }
        if(args.length==2 && args[0].equals("resume")){
            if(!(sender.getEntityWorld() instanceof WorldServer world))throw new CommandException("Server world required");
            String k=key(args[1]);
            SurvivorRoster roster=SurvivorRoster.get(world);
            var snapshot=roster.snapshot(k);
            if(snapshot==null)throw new CommandException("No snapshot in this dimension");
            String savedName=snapshot.getString("name");
            String name=plainName(savedName);
            if(!name.matches("[A-Za-z0-9_]{1,16}")||!key(name).equals(k)||bots.size()>=MAX||bots.containsKey(k)||server.getPlayerList().getPlayerByUsername(name)!=null)throw new CommandException("AI identity or capacity conflict");
            BlockPos pos=SurvivorRoster.position(snapshot),home=SurvivorRoster.home(snapshot);
            if(!standable(world,pos))throw new CommandException("Saved position unavailable; load its chunk and clear the position first");
            UUID uuid=SurvivorRoster.uuid(snapshot);
            SurvivorPlayer bot=new SurvivorPlayer(server,world,new GameProfile(uuid,name),home);
            bot.setPositionAndRotation(pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
            SurvivorRoster.restore(bot,snapshot);
            // Consume snapshot before activating; put it back only if activation fails.
            roster.forget(k);
            try{activate(server,world,bot);}
            catch(CommandException ex){roster.park(k,bot);throw ex;}
            sender.sendMessage(new TextComponentString("Resumed "+name+" (AI)"));return;
        }
        if((args.length==2&&args[0].equals("furnace"))||(args.length==3&&args[0].equals("smelt"))){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null)throw new CommandException("AI survivor not found");
            if(sender.getEntityWorld()!=bot.world)throw new CommandException("Same dimension required");
            if(args[0].equals("furnace")){
                if(!bot.furnaceStation())throw new CommandException("Needs carried furnace and a permitted placement");
            }else if(!bot.smelt(new ResourceLocation(args[2])))throw new CommandException("Needs smeltable carried item and no pending furnace job");
            sender.sendMessage(new TextComponentString("Production task queued"));return;
        }
        if(args.length==3&&args[0].equals("vehicle")){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null||!bot.vehicleGoal(new ResourceLocation(args[2])))throw new CommandException("Unknown registered MCHeli vehicle recipe");
            sender.sendMessage(new TextComponentString("Vehicle drafting task queued"));return;
        }
        if(args.length==5&&args[0].equals("preparearena")){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null||bot.world!=sender.getEntityWorld()||!bot.prepareArena(parseBlockPos(sender,args,2,false)))throw new CommandException("Requires loaded open sky site near agent home");
            sender.sendMessage(new TextComponentString("Arena preparation queued. Beacon placement starts the global event; operator must authorize it."));return;
        }
        if(args.length==4&&args[0].equals("arena")){
            BlockPos p=parseBlockPos(sender,args,1,false);
            sender.sendMessage(new TextComponentString("OverLast arena: "+SurvivorFinalBattle.validate(sender.getEntityWorld(),p)));return;
        }
        if(args.length==3 && args[0].equals("goal")){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null)throw new CommandException("AI survivor not found");
            if(args[2].equals("clear")){bot.clearTechGoal();return;}
            if(!bot.techGoal(new ResourceLocation(args[2])))throw new CommandException("Unknown or disallowed technology goal");
            sender.sendMessage(new TextComponentString("Goal queued; enable autonomous work with /manhuntai auto "+args[1]+" on"));return;
        }
        if(args.length==3 && args[0].equals("industry")){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null)throw new CommandException("AI survivor not found");
            if(!args[2].equals("on")&&!args[2].equals("off"))throw new CommandException("Expected on/off");
            bot.industry(args[2].equals("on"));
            sender.sendMessage(new TextComponentString("Industrial progression "+args[2]+"; requires autonomous work enabled"));return;
        }
        if(args.length==3 && args[0].equals("auto")){
            SurvivorPlayer bot=bots.get(key(args[1]));
            if(bot==null)throw new CommandException("AI survivor not found");
            if(!args[2].equals("on")&&!args[2].equals("off"))throw new CommandException("Expected on/off");
            bot.autonomous(args[2].equals("on"));
            sender.sendMessage(new TextComponentString(bot.getName()+": autonomous work "+args[2]+" (local protected area only)"));return;
        }
        if (args.length >= 2 && (args[0].equals("craft") || args[0].equals("place") || args[0].equals("supply") || args[0].equals("fortify"))) {
            SurvivorPlayer bot = bots.get(key(args[1]));
            if (bot == null) throw new CommandException("AI survivor not found");
            if (sender.getEntityWorld() != bot.world) throw new CommandException("Same dimension required");
            if (args[0].equals("fortify") && args.length==2) {
                bot.fortify(); sender.sendMessage(new TextComponentString("5x5 perimeter queued; uses actual carried full blocks")); return;
            }
            if (args[0].equals("craft") && args.length==3) {
                if (!SurvivorCrafting.craft(bot,new ResourceLocation(args[2]))) throw new CommandException("Recipe not supported, insufficient ingredients or space");
                sender.sendMessage(new TextComponentString("Crafted: " + args[2])); return;
            }
            if (args[0].equals("place") && (args.length==5 || args.length==6)) {
                BlockPos p = parseBlockPos(sender,args,2,false);
                if(!bot.requestBuild(p,args.length==6 ? new ResourceLocation(args[5]) : null)) throw new CommandException("Invalid or protected build target");
                sender.sendMessage(new TextComponentString("Placement queued, requires real materials and line of sight")); return;
            }
            if (args[0].equals("supply") && args.length==2 && sender instanceof EntityPlayerMP player && player.getDistanceSq(bot)<=16) {
                ItemStack held=player.getHeldItemMainhand();
                if (held.isEmpty()) throw new CommandException("Hold item to transfer");
                int before=held.getCount(); ItemStack moved=held.copy();
                bot.inventory.addItemStackToInventory(moved);
                int transferred=before-moved.getCount();
                if(transferred<=0) throw new CommandException("AI inventory full");
                held.shrink(transferred); player.inventory.markDirty(); bot.inventory.markDirty();
                sender.sendMessage(new TextComponentString("Transferred " + transferred)); return;
            }
            throw new CommandException(getUsage(sender));
        }
        if (args.length == 1 && args[0].equals("list")) {
            sender.sendMessage(new TextComponentString("AI survivors: " + bots.size() + "/" + MAX
                + " | population="+population.enabled()+"/"+population.target()+" | AI avg " + String.format(java.util.Locale.ROOT,"%.2f",meanAiMs) + " ms | tick interval " + String.format(java.util.Locale.ROOT,"%.1f",meanTickMs) + " ms | degraded=" + stressed));
            for (SurvivorPlayer bot : bots.values()) sender.sendMessage(new TextComponentString(bot.getName() + " " + bot.activity() + " HP=" + bot.getHealth()+" "+bot.infrastructureStatus()));
            return;
        }
        if (args.length == 1 && args[0].equals("clear")) { population.configure(false,population.target());clear(server); return; }
        if (args.length == 2 && args[0].equals("remove")) {
            SurvivorPlayer bot = bots.get(key(args[1]));
            if (bot == null) throw new CommandException("AI survivor not found");
            population.suppress(bot.getName());remove(server, bot); return;
        }
        if (args.length != 2 || !args[0].equals("spawn") || !args[1].matches("[A-Za-z0-9_]{1,13}")) throw new CommandException(getUsage(sender));
        if (bots.size() >= MAX) throw new CommandException("AI survivor limit reached");
        String name = plainName(args[1]);
        if(name.isEmpty())throw new CommandException("Empty agent name");
        if (bots.containsKey(key(name)) || server.getPlayerList().getPlayerByUsername(name) != null) throw new CommandException("Name already in use");
        if (!(sender.getEntityWorld() instanceof WorldServer world)) throw new CommandException("Server world required");
        BlockPos pos = sender.getPosition();
        for(WorldServer dimension:server.worlds)if(dimension!=null&&SurvivorRoster.get(dimension).contains(key(name)))throw new CommandException("Parked AI already uses this name; resume it instead");
        if (!standable(world,pos)) throw new CommandException("Choose a loaded, standable location");
        UUID uuid = UUID.nameUUIDFromBytes(("ManhuntAI:" + name).getBytes(StandardCharsets.UTF_8));
        SurvivorPlayer bot = new SurvivorPlayer(server, world, new GameProfile(uuid, name), pos);
        activate(server,world,bot);
        sender.sendMessage(new TextComponentString("Spawned " + name + " (AI, not counted as an authenticated user)"));
    }
    static boolean standable(WorldServer world,BlockPos pos){return SurvivorTerrain.stand(world,pos);}
    int activeCount(){return bots.size();}
    boolean activeName(String name){return bots.containsKey(key(name));}
    boolean parkedElsewhere(MinecraftServer server,String k){for(WorldServer w:server.worlds)if(w!=null&&w.provider.getDimension()!=0&&SurvivorRoster.get(w).contains(k))return true;return false;}
    BlockPos overworldCamp(WorldServer world){for(SurvivorPlayer bot:bots.values())if(bot.world==world)return bot.homePosition();return null;}
    BlockPos broAnchor(WorldServer world){
        for(EntityPlayerMP p:world.getMinecraftServer().getPlayerList().getPlayers())
            if(p.world==world&&SurvivorContacts.alias(p.getName()).equals("BRO")&&SurvivorContacts.friendly(p))return p.getPosition();
        return null;
    }
    java.util.List<BlockPos> safeHumanAnchors(WorldServer world){
        var out=new ArrayList<BlockPos>();
        for(EntityPlayerMP p:world.getMinecraftServer().getPlayerList().getPlayers()){
            if(p.world==world&&!p.isSpectator()&&!com.x4yi.automata.compat.ManhuntCompat.isHostile(p)&&out.size()<12)out.add(p.getPosition());
        }
        return out;
    }
    boolean homeTooClose(WorldServer world,BlockPos p){for(SurvivorPlayer bot:bots.values())if(bot.world==world&&bot.homePosition().distanceSq(p)<36)return true;return false;}
    boolean occupied(WorldServer world,BlockPos p){for(SurvivorPlayer bot:bots.values())if(bot.world==world&&bot.getDistanceSqToCenter(p)<16)return true;return false;}
    boolean autoSpawn(MinecraftServer server,WorldServer world,String name,BlockPos pos,boolean siegeKit)throws CommandException{
        name=plainName(name);
        String k=key(name);if(bots.size()>=MAX||activeName(name)||server.getPlayerList().getPlayerByUsername(name)!=null||!standable(world,pos))return false;
        UUID uuid=UUID.nameUUIDFromBytes(("ManhuntAI:"+name).getBytes(StandardCharsets.UTF_8));
        SurvivorPlayer bot=new SurvivorPlayer(server,world,new GameProfile(uuid,name),pos);
        if(!SurvivorPermissions.allowed(bot,pos,true)){bot.cancelPlanning();return false;}
        SurvivorRoster roster=SurvivorRoster.get(world);
        if(roster.recruited(k))bot.inventory.clear();
        else if(siegeKit)bot.equipSiegeStarter();
        bot.autonomous(true);bot.industry(true);
        activate(server,world,bot);roster.markRecruited(k);return true;
    }
    boolean autoResume(MinecraftServer server,WorldServer world,String k)throws CommandException{
        SurvivorRoster roster=SurvivorRoster.get(world);var n=roster.snapshot(k);
        if(n==null)return false;
        String savedName=n.getString("name"),name=plainName(savedName);BlockPos pos=SurvivorRoster.position(n),home=SurvivorRoster.home(n);
        if(!name.matches("[A-Za-z0-9_]{1,16}")||!key(name).equals(k)||activeName(name)||server.getPlayerList().getPlayerByUsername(name)!=null||bots.size()>=MAX||!standable(world,pos)||occupied(world,pos)||!world.getEntitiesWithinAABB(net.minecraft.entity.EntityLivingBase.class,new net.minecraft.util.math.AxisAlignedBB(pos).grow(16),e->e.isEntityAlive()&&com.x4yi.automata.compat.ManhuntCompat.isHostile(e)).isEmpty())return false;
        UUID uuid=SurvivorRoster.uuid(n);
        SurvivorPlayer bot=new SurvivorPlayer(server,world,new GameProfile(uuid,name),home);
        bot.setPositionAndRotation(pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);SurvivorRoster.restore(bot,n);
        roster.forget(k);
        try{activate(server,world,bot);roster.markRecruited(k);return true;}
        catch(CommandException e){roster.park(k,bot);throw e;}
    }
    private static void announce(MinecraftServer server,String message){for(EntityPlayerMP p:server.getPlayerList().getPlayers())p.sendMessage(new TextComponentString(message));}
    private void activate(MinecraftServer server,WorldServer world,SurvivorPlayer bot)throws CommandException{
        String name=bot.getName();
        // Validate before world insertion: vanilla can replace an existing entity on a UUID collision.
        if(!name.matches("[A-Za-z0-9_]{1,16}") || bot.getUniqueID()==null || bots.containsKey(key(name)) || server.getPlayerList().getPlayerByUsername(name)!=null || server.getPlayerList().getPlayerByUUID(bot.getUniqueID())!=null){
            bot.cancelPlanning();throw new CommandException("Invalid or conflicting agent profile");
        }
        for(WorldServer dimension:server.worlds)if(dimension!=null && dimension.getEntityFromUuid(bot.getUniqueID())!=null){
            bot.cancelPlanning();throw new CommandException("Agent UUID already exists in a loaded world");
        }
        if(!SurvivorFaction.human(bot)){bot.cancelPlanning();throw new CommandException("Cotesia HUMAN selection failed; AI not activated");}
        broadcast(server, new SPacketPlayerListItem(SPacketPlayerListItem.Action.ADD_PLAYER, bot));
        try {
            if (!world.spawnEntity(bot)) throw new CommandException("AI spawn rejected");
            bots.put(key(name), bot);
            SurvivorTelemetry.event("join",bot,"");
            announce(server,name+" присоединился к миру.");
        } catch (RuntimeException | CommandException e) {
            bots.remove(key(name));bot.cancelPlanning();
            world.removeEntityDangerously(bot);
            broadcast(server, new SPacketPlayerListItem(SPacketPlayerListItem.Action.REMOVE_PLAYER, bot));
            throw new CommandException("AI spawn failed: " + e.getMessage());
        }
    }
    private boolean park(MinecraftServer server,SurvivorPlayer bot){
        if(com.x4yi.automata.compat.ManhuntCompat.isParasite(bot))return false;
        if(!SurvivorRoster.get((WorldServer)bot.world).park(key(bot.getName()),bot))return false;
        remove(server,bot);return true;
    }
    public void shutdown(MinecraftServer server){
        if(server!=null)for(SurvivorPlayer bot:new ArrayList<>(bots.values())){
            try{if(!park(server,bot))AutomataMod.LOGGER.warn("Could not park AI "+bot.getName());}
            catch(RuntimeException ex){AutomataMod.LOGGER.error("AI snapshot failure",ex);}
        }
        clear(server);population.reset();
        if(server!=null)updateAgentStatus(server);
        statusOriginal=null;statusPublished=null;lastStatusNames="";
        if(server!=null)for(WorldServer world:server.worlds)if(world!=null)world.getPerWorldStorage().saveAllData();
    }
    void showParticipants(MinecraftServer server,ICommandSender sender){
        java.util.List<String> humans=new ArrayList<>(),agents=new ArrayList<>();
        for(EntityPlayerMP p:server.getPlayerList().getPlayers())humans.add(p.getName());
        for(SurvivorPlayer bot:bots.values())if(bot.isEntityAlive())agents.add(bot.getName());
        sender.sendMessage(new TextComponentString("Участники мира: "+(humans.size()+agents.size())));
        sender.sendMessage(new TextComponentString("Игроки ("+humans.size()+"): "+(humans.isEmpty()?"—":String.join(", ",humans))));
        sender.sendMessage(new TextComponentString("Агенты ИИ ("+agents.size()+"): "+(agents.isEmpty()?"—":String.join(", ",agents))));
    }
    private static String plainName(String name){
        return name.regionMatches(true,0,"AI_",0,3)?name.substring(3):name;
    }
    @SubscribeEvent
    public void onRconList(net.minecraftforge.event.CommandEvent event){
        if(!(event.getSender() instanceof net.minecraft.network.rcon.RConConsoleSource)
            ||!"list".equals(event.getCommand().getName())||event.getParameters().length!=0)return;
        MinecraftServer server=event.getSender().getServer();
        if(server==null||bots.isEmpty())return;
        java.util.List<String> names=new ArrayList<>();
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(EntityPlayerMP p:server.getPlayerList().getPlayers()){
            names.add(p.getName());seen.add(p.getName().toLowerCase(java.util.Locale.ROOT));
        }
        int humanCount=names.size();
        for(SurvivorPlayer bot:bots.values()){
            if(!bot.isEntityAlive())continue;
            String label=plainName(bot.getName());
            if(!seen.add(label.toLowerCase(java.util.Locale.ROOT)))continue;
            names.add(label);
        }
        if(names.size()==humanCount)return;
        int capacity=Math.max(names.size(),server.getPlayerList().getMaxPlayers());
        event.setCanceled(true);
        event.getSender().sendMessage(new TextComponentString("There are "+names.size()+"/"+capacity+" players online: "+String.join(", ",names)));
    }
    private static String key(String input) {
        return (input.regionMatches(true, 0, "AI_", 0, 3) ? input : "AI_" + input).toLowerCase(java.util.Locale.ROOT);
    }
    private static void broadcast(MinecraftServer server, SPacketPlayerListItem packet) {
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) if (player.connection != null) player.connection.sendPacket(packet);
    }
    private void remove(MinecraftServer server, SurvivorPlayer bot) {
        bots.remove(key(bot.getName()));
        bot.cancelPlanning();
        bot.world.removeEntityDangerously(bot);
        broadcast(server, new SPacketPlayerListItem(SPacketPlayerListItem.Action.REMOVE_PLAYER, bot));
        SurvivorTelemetry.event("leave",bot,"");
        announce(server,bot.getName()+" покинул мир.");
    }
    public void clear(MinecraftServer server) {
        for (SurvivorPlayer bot : new ArrayList<>(bots.values())) {
            MinecraftServer owner = server != null ? server : bot.getServer();
            if (owner != null) remove(owner, bot);
            else bot.cancelPlanning();
        }
        bots.clear(); assistance.clear(); dialogue.clear(); chatQueue.clear(); chatMemory.clear(); chatCooldown.clear(); chatRecent.clear(); modelPending.clear(); localPending.clear(); speech.clear(); nextPublicChat=0; nextAmbientChat=0; squadMessages.clear(); lastTickNanos=0; meanAiMs=0; meanTickMs=50; ticks=0; stressed=false;
    }
    /** Vanilla clients resolve SPacketSpawnPlayer through the tab profile map.
     * PlayerLoggedInEvent is too late: world insertion can start entity tracking first. */
    @SubscribeEvent(priority=net.minecraftforge.fml.common.eventhandler.EventPriority.HIGHEST, receiveCanceled=false)
    public void beforePlayerWorldJoin(net.minecraftforge.event.entity.EntityJoinWorldEvent event){
        if(event.getWorld().isRemote || !(event.getEntity() instanceof EntityPlayerMP player) || player instanceof SurvivorPlayer || player instanceof net.minecraftforge.common.util.FakePlayer || player.connection==null)return;
        MinecraftServer server=player.getServer();if(server==null)return;
        for(SurvivorPlayer bot:new ArrayList<>(bots.values())){
            if(bot.getUniqueID().equals(player.getUniqueID()) || bot.getName().equalsIgnoreCase(player.getName())){
                population.suppress(bot.getName());remove(server,bot);
            }
        }
        if(!bots.isEmpty()){
            java.util.List<EntityPlayerMP> profiles=new ArrayList<>();
            for(SurvivorPlayer bot:bots.values())if(bot.isEntityAlive())profiles.add(bot);
            if(!profiles.isEmpty())try{
                player.connection.sendPacket(new SPacketPlayerListItem(SPacketPlayerListItem.Action.ADD_PLAYER, profiles));
            }catch(RuntimeException|LinkageError e){
                AutomataMod.LOGGER.error("Agent profile synchronization failed for real player "+player.getName(),e);
                population.configure(false,population.target());
                for(SurvivorPlayer bot:new ArrayList<>(bots.values())){
                    try{if(!park(server,bot))AutomataMod.LOGGER.warn("Agent remains active: "+bot.getName());}
                    catch(RuntimeException|LinkageError failure){AutomataMod.LOGGER.error("Could not safely park "+bot.getName(),failure);}
                }
            }
        }
    }
    @SubscribeEvent public void onLogout(PlayerEvent.PlayerLoggedOutEvent event){
        if(event.player instanceof EntityPlayerMP player && !(player instanceof SurvivorPlayer) && !(player instanceof net.minecraftforge.common.util.FakePlayer)){
            AutomataMod.LOGGER.info("Real player logout: {}; live AI: {}; dimension: {}; AI tick avg: {} ms",player.getName(),bots.size(),player.world.provider.getDimension(),meanAiMs);
        }
    }
    @SubscribeEvent public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player && !(player instanceof SurvivorPlayer) && !(player instanceof net.minecraftforge.common.util.FakePlayer) && player.connection != null) {
            player.sendMessage(new TextComponentString("Состав участников: /participants"));
        }
    }
    private int nextHumanHelp;
    @SubscribeEvent(receiveCanceled=false) public void onChat(ServerChatEvent event) {
        EntityPlayerMP sender=event.getPlayer();
        if(sender instanceof SurvivorPlayer||sender instanceof net.minecraftforge.common.util.FakePlayer||bots.isEmpty())return;
        String msg=SurvivorDialogue.sanitize(event.getMessage());if(msg.isEmpty()||chatQueue.size()>=12||modelPending.containsKey(sender.getUniqueID()))return;
        for(ChatTurn pending:chatQueue)if(pending.sender().equals(sender.getUniqueID()))return;
        var faction=side(sender);var topic=SurvivorChatPolicy.topic(msg);
        ChatMemory memory=chatMemory.get(sender.getUniqueID());
        if(memory!=null&&(ticks-memory.tick()>1200||memory.side()!=faction))memory=null;
        boolean explicit=false;
        for(SurvivorPlayer bot:bots.values())if(SurvivorChatPolicy.addressed(msg,bot.getName())){explicit=true;break;}
        SurvivorPlayer selected=null;boolean direct=false;double best=-Double.MAX_VALUE;
        for(SurvivorPlayer bot:bots.values()){
            if(!bot.isEntityAlive()||bot.world!=sender.world||ticks<chatCooldown.getOrDefault(key(bot.getName()),0))continue;
            boolean named=SurvivorChatPolicy.addressed(msg,bot.getName());
            if(explicit&&!named)continue;
            boolean continuation=memory!=null&&memory.botKey().equals(key(bot.getName()));
            boolean group=SurvivorChatPolicy.group(msg);
            boolean nearby=sender.getDistanceSq(bot)<=64*64;
            if(!named&&!continuation&&(!SurvivorChatPolicy.relevant(topic)||(!group&&!nearby)))continue;
            double score=(named?1000:0)+(continuation?100:0)+(group?25:0)+(nearby?20:0)-Math.min(40,sender.getDistanceSq(bot)/256.0)+bot.getRNG().nextDouble()*15;
            if(score>best){selected=bot;best=score;direct=named;}
        }
        if(selected==null)return;
        boolean continued=memory!=null&&memory.botKey().equals(key(selected.getName()));
        if(!direct&&!continued&&!SurvivorChatPolicy.group(msg)&&topic!=SurvivorChatPolicy.Topic.HELP&&selected.getRNG().nextInt(3)!=0)return;
        if(!direct&&continued&&topic==SurvivorChatPolicy.Topic.CHAT&&selected.getRNG().nextInt(4)==0)return;
        String botKey=key(selected.getName());
        if(chatCooldown.size()>64){chatCooldown.clear();chatRecent.clear();}
        chatCooldown.put(botKey,ticks+65+selected.getRNG().nextInt(90));
        String reply=null;
        // Gameplay assistance remains separately validated by Cotesia and actual proximity/LOS.
        if(faction==SurvivorChatPolicy.Side.HUMAN&&topic==SurvivorChatPolicy.Topic.HELP&&ticks>=nextHumanHelp&&selected.acceptHumanHelp(sender)){
            nextHumanHelp=ticks+200;
            String[] replies={"братан, вижу тебя, попробую прикрыть","держись, попробую помочь","вижу тебя, щас попробую подойти","бля, держись, попробую прикрыть"};
            reply=replies[selected.getRNG().nextInt(replies.length)];
        }
        var previous=memory==null||!continued?"":memory.topic().name();
        if(reply==null){
            for(int i=0;i<6;i++){
                reply=SurvivorChatPolicy.answer(topic,faction,selected.personality(),selected.chatState(),previous,selected.getRNG());
                reply=SurvivorChatPolicy.stylize(reply,selected.personality(),selected.getRNG());
                if(!chatRecent.getOrDefault(botKey,new java.util.ArrayDeque<>()).contains(reply))break;
            }
        }
        if(chatRecent.getOrDefault(botKey,new java.util.ArrayDeque<>()).contains(reply)){
            if(!direct&&!continued)return;
            chatRecent.get(botKey).pollFirst();
        }
        int delay=18+selected.getRNG().nextInt(43);
        var turn=new ChatTurn(botKey,selected.getEntityId(),sender.getUniqueID(),faction,ticks+delay,ticks+400,msg,reply,topic,++dialogueSequence,false);
        // Social replies may be generated for every faction. Identity, locations, inventory,
        // combat facts and assistance remain deterministic and grounded in server state.
        boolean social=switch(topic){case CHAT,QUESTION,JOKE,GREETING,FAREWELL,THANKS,YES,NO -> true;default -> false;};
        if(social&&!stressed&&modelPending.size()<2){
            String context="Собеседник "+sender.getName()+"; фракция "+faction+"; характер "+SurvivorPersonality.description(selected.personality())+"; сторона людей, отряд Купол";
            if(selected.world instanceof WorldServer world)context+="; "+SurvivorMemory.get(world).recallConversation(selected.getUniqueID(),sender.getUniqueID(),faction);
            String history=memory==null||!continued?"":"Предыдущее сообщение: "+memory.message()+"\nПредыдущий ответ: "+memory.reply()+"\n";
            if(dialogue.offer(botKey,selected.getEntityId(),sender.getUniqueID(),turn.requestId(),context,history+"Новое сообщение: "+msg+"\nНе повторяй ранее сказанное: "+speech.context(),reply)){
                modelPending.put(sender.getUniqueID(),turn);return;
            }
        }
        chatQueue.addLast(turn);
    }
    private void queueAmbient(SurvivorPlayer speaker,SurvivorPlayer listener,String previous){
        if(stressed||chatQueue.size()>=12||modelPending.size()>=2||modelPending.containsKey(listener.getUniqueID()))return;
        String k=key(speaker.getName());
        if(!speaker.calmForSupply()||!listener.calmForSupply()||speaker.world!=listener.world||speaker.getDistanceSq(listener)>256)return;
        if(ticks<chatCooldown.getOrDefault(k,0))return;
        boolean opening=previous.isEmpty();
        String fallback=opening?listener.getName()+", как там, держишься?":SurvivorChatPolicy.answer(SurvivorChatPolicy.Topic.CHAT,SurvivorChatPolicy.Side.HUMAN,speaker.personality(),speaker.chatState(),"",speaker.getRNG());
        var turn=new ChatTurn(k,speaker.getEntityId(),listener.getUniqueID(),SurvivorChatPolicy.Side.HUMAN,ticks+30,ticks+400,previous,fallback,SurvivorChatPolicy.Topic.CHAT,++dialogueSequence,true);
        chatCooldown.put(k,ticks+160);
        String context="Отряд Купол, союзник "+listener.getName()+"; характер "+SurvivorPersonality.description(speaker.personality())+"; оба сейчас вне боя.";
        String prompt=opening?"Начни короткий естественный разговор с этим союзником, без обещаний или выдуманных событий.":"Ответь союзнику на его настоящую реплику: "+previous;
        if(dialogue.offer(k,speaker.getEntityId(),listener.getUniqueID(),turn.requestId(),context,prompt+"; не повторяй: "+speech.context(),fallback))modelPending.put(listener.getUniqueID(),turn);
        else chatQueue.addLast(turn);
    }
    private void ambientChat(){
        if(ticks<nextAmbientChat||stressed||!chatQueue.isEmpty()||!modelPending.isEmpty()||!localPending.isEmpty())return;
        nextAmbientChat=ticks+1200+java.util.concurrent.ThreadLocalRandom.current().nextInt(1201);
        for(SurvivorPlayer a:bots.values())for(SurvivorPlayer b:bots.values()){
            if(!localAudience(a,48)||a==b||a.world!=b.world||!a.isEntityAlive()||!b.isEntityAlive()||a.getDistanceSq(b)>256||!a.calmForSupply()||!b.calmForSupply())continue;
            if(ticks<chatCooldown.getOrDefault(key(a.getName()),0)||ticks<chatCooldown.getOrDefault(key(b.getName()),0))continue;
            queueAmbient(a,b,"");return;
        }
    }
    @SubscribeEvent public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long start=System.nanoTime();
        if(ticks%20==0)SurvivorFinalBattle.refresh();
        deliverDialogue();
        if(lastTickNanos!=0) meanTickMs=meanTickMs*.95 + Math.min(1000,(start-lastTickNanos)/1_000_000.0)*.05;
        lastTickNanos=start;
        if(++ticks%20==0) {
            if(meanAiMs>4 || meanTickMs>65) stressed=true;
            else if(meanAiMs<2 && meanTickMs<55) stressed=false;
        }
        for (SurvivorPlayer bot : new ArrayList<>(bots.values())) {
            MinecraftServer server = bot.getServer();
            if (server == null) continue;
            if (!bot.isEntityAlive() || com.x4yi.automata.compat.ManhuntCompat.isParasite(bot) || server.getPlayerList().getPlayerByUsername(bot.getName()) != null) { population.lost(bot.getName(),!bot.isEntityAlive());remove(server, bot); continue; }
            try { bot.setStressed(stressed || (System.nanoTime()-start)>4_000_000L); bot.drive(); } catch (RuntimeException | LinkageError e) {
                AutomataMod.LOGGER.error("Manhunt AI disabled after error: " + bot.getName(), e);
                population.lost(bot.getName(),false);remove(server, bot);
            }
        }
        MinecraftServer server=net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if(server!=null&&ticks%20==0)try{population.tick(server,this,stressed||(System.nanoTime()-start)>4_000_000L);}catch(RuntimeException | LinkageError ex){population.failure(ex);}
        if(server!=null&&ticks%20==0)updateAgentStatus(server);
        if(!stressed&&ticks%40==0&&(System.nanoTime()-start)<3_000_000L)try{assistance.tick(new ArrayList<>(bots.values()),ticks);}catch(RuntimeException|LinkageError ex){assistance.clear();for(SurvivorPlayer bot:bots.values())bot.clearHelp();AutomataMod.LOGGER.warn("AI help coordinator reset",ex);}
        if(!stressed&&ticks%200==0&&(System.nanoTime()-start)<2_000_000L)SurvivorSeason.pool(new ArrayList<>(bots.values()));
        if(!stressed&&ticks%100==0&&(System.nanoTime()-start)<2_000_000L)SurvivorSquad.coordinate(new ArrayList<>(bots.values()),ticks/100);
        if(!stressed&&ticks%100==0&&(System.nanoTime()-start)<2_000_000L)ambientChat();
        meanAiMs=meanAiMs*.95+(System.nanoTime()-start)/1_000_000.0*.05;
    }
}
