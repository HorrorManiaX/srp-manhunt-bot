/* SPDX-License-Identifier: MIT */
package levosilimo.everlastingskins.skinchanger;
import com.google.gson.*;
import com.mojang.authlib.properties.Property;
import levosilimo.everlastingskins.Config;
import levosilimo.everlastingskins.EverlastingSkins;
import levosilimo.everlastingskins.util.CustomSkinProperty;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;

final class LoginSkinLoader {
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(64), r -> {
            Thread t = new Thread(r,"EverlastingSkins-Manhunt-Login");
            t.setDaemon(true); return t;
        });
    static { IO.allowCoreThreadTimeOut(true); }
    private static final Map<String, Cached> CACHE = new LinkedHashMap<String,Cached>(64,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Cached> e) { return size()>1000; }
    };
    private static final class Cached {
        final CustomSkinProperty skin;
        final long expires;
        Cached(CustomSkinProperty skin) {
            this.skin=skin;
            expires=System.currentTimeMillis()+(skin==null?180000L:3600000L);
        }
    }
    static void applySavedBeforeJoin(EntityPlayerMP player, SkinStorage storage) {
        boolean override=Config.DEFAULT_SKINS_ENABLED && Config.DEFAULT_SKINS_LIST.length>0
                && Config.DEFAULT_SKINS_APPLY_FOR_PREMIUM;
        if (override) return;
        CustomSkinProperty stored=storage.getSkin(player.getUniqueID());
        if (stored==null || stored.isEmpty() || !stored.isValid()) return;
        player.getGameProfile().getProperties().removeAll("textures");
        player.getGameProfile().getProperties().put("textures",stored.getOriginalProperty());
    }
    static void onLogin(EntityPlayerMP player, SkinStorage storage) {
        if (storage==null) return;
        UUID uuid=player.getUniqueID();
        MinecraftServer server=player.mcServer;
        String name=player.getGameProfile().getName();
        CustomSkinProperty initial=storage.getSkin(uuid);
        boolean configured=Config.DEFAULT_SKINS_ENABLED && Config.DEFAULT_SKINS_LIST.length>0;
        boolean override=configured && Config.DEFAULT_SKINS_APPLY_FOR_PREMIUM;
        if (!storage.hasDefaultSkin(uuid) && !override) {
            SkinRefreshTask.loginRefresh(player,initial); return;
        }
        if (!Config.AUTO_LOGIN_SKINS && !configured) return;
        if (name==null || !name.matches("[A-Za-z0-9_]{1,16}")) return;
        if (!configured && !player.getGameProfile().getProperties().get("textures").isEmpty()) return;
        try {
            IO.execute(() -> {
                try {
                    CustomSkinProperty result;
                    if (configured) {
                        Property p=DefaultSkinResolver.resolveDefault(Arrays.asList(Config.DEFAULT_SKINS_LIST),SkinCommand.getMojangAPI());
                        result=p==null?null:new CustomSkinProperty(p.getName(),p.getValue(),p.getSignature(),"DefaultSkin");
                    } else result=lookup(name);
                    if (result==null || result.isEmpty()) return;
                    server.addScheduledTask(() -> {
                        if (SkinRestorer.getServer()!=server || SkinRestorer.getSkinStorage()!=storage) return;
                        if (server.getPlayerList().getPlayerByUUID(uuid)!=player || player.connection==null) return;
                        if (!Objects.equals(initial,storage.getSkin(uuid))) return;
                        if (!override && !storage.hasDefaultSkin(uuid)) return;
                        if (!configured) {
                            storage.saveSkinAsync(uuid,result);
                            storage.setSkin(uuid,result);
                        }
                        SkinRefreshTask.loginRefresh(player,result);
                    });
                } catch (Exception e) { EverlastingSkins.logger.debug("Login skin lookup failed for {}",name,e); }
            });
        } catch (RejectedExecutionException e) { EverlastingSkins.logger.debug("Login skin queue full for {}",name); }
    }
    private static CustomSkinProperty lookup(String name) {
        boolean ely=Config.AUTO_ELY_FALLBACK;
        String key=name.toLowerCase(Locale.ROOT)+":"+ely+":"+System.identityHashCode(SkinCommand.getMojangAPI());
        synchronized(CACHE) {
            Cached c=CACHE.get(key);
            if(c!=null && c.expires>System.currentTimeMillis()) return c.skin;
            CACHE.remove(key);
        }
        CustomSkinProperty p=null;
        try { p=SkinCommand.getMojangAPI().getSkin(name).map(d->d.skinProperty()).orElse(null); }
        catch(Exception e) { EverlastingSkins.logger.debug("Mojang lookup failed for {}",name,e); }
        if(!hasSkinTexture(p)) p=ely?fetchEly(name):null;
        synchronized(CACHE) { CACHE.put(key,new Cached(p)); }
        return p;
    }
    // MojangAPI already requests signed properties; the client verifies their
    // signature. Check the payload here to distinguish missing skins from a
    // usable result. For the extra Ely provider verify Mojang's RSA signature too.
    private static boolean hasSkinTexture(CustomSkinProperty p) {
        if(p==null || p.isEmpty() || !p.isValid()) return false;
        try {
            if(!p.getOriginalProperty().hasSignature()) return false;
            JsonObject body=new JsonParser().parse(new String(Base64.getDecoder().decode(p.getValue()),StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject textures=body.getAsJsonObject("textures");
            if(textures==null || !textures.has("SKIN")) return false;
            URI url=URI.create(textures.getAsJsonObject("SKIN").get("url").getAsString());
            return ("https".equalsIgnoreCase(url.getScheme())||"http".equalsIgnoreCase(url.getScheme()))
                && "textures.minecraft.net".equalsIgnoreCase(url.getHost()) && url.getUserInfo()==null
                && url.getPath().matches("/texture/[a-fA-F0-9]{32,128}");
        } catch(Exception e) { return false; }
    }
    private static boolean usable(CustomSkinProperty p) {
        if(p==null || p.isEmpty() || !p.isValid()) return false;
        try {
            Property prop=p.getOriginalProperty();
            if(!prop.hasSignature()) return false;
            try(InputStream in=com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService.class.getResourceAsStream("/yggdrasil_session_pubkey.der")) {
                if(in==null) return false;
                PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(read(in,8192)));
                if(!prop.isSignatureValid(key)) return false;
            }
            JsonObject body=new JsonParser().parse(new String(Base64.getDecoder().decode(p.getValue()),StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject textures=body.getAsJsonObject("textures");
            if(textures==null || !textures.has("SKIN")) return false;
            URI url=URI.create(textures.getAsJsonObject("SKIN").get("url").getAsString());
            return ("https".equalsIgnoreCase(url.getScheme())||"http".equalsIgnoreCase(url.getScheme()))
                && "textures.minecraft.net".equalsIgnoreCase(url.getHost()) && url.getUserInfo()==null
                && url.getPath().matches("/texture/[a-fA-F0-9]{32,128}");
        } catch(Exception e) { return false; }
    }
    private static byte[] read(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] b=new byte[4096]; int n;
        while((n=in.read(b))!=-1) {
            if(out.size()+n>limit) throw new IOException("Response too large");
            out.write(b,0,n);
        }
        return out.toByteArray();
    }
    private static CustomSkinProperty fetchEly(String name) {
        HttpsURLConnection c=null;
        try {
            c=(HttpsURLConnection)new URL("https://skinsystem.ely.by/textures/signed/"+name+"?version=2&minecraft_version=1.12.2&authlib_version=1.5.25").openConnection();
            c.setConnectTimeout(3000); c.setReadTimeout(3000);
            c.setInstanceFollowRedirects(false); c.setUseCaches(false);
            c.setRequestProperty("Accept","application/json");
            c.setRequestProperty("User-Agent","EverlastingSkins-Manhunt/1.0");
            if(c.getResponseCode()!=200) return null;
            JsonArray props;
            try(InputStream in=c.getInputStream()) {
                props=new JsonParser().parse(new String(read(in,65536),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("properties");
            }
            if(props==null) return null;
            for(JsonElement element:props) {
                JsonObject p=element.getAsJsonObject();
                if(!"textures".equals(p.get("name").getAsString())||!p.has("signature")) continue;
                CustomSkinProperty result=new CustomSkinProperty("textures",p.get("value").getAsString(),p.get("signature").getAsString(),"Ely.by",name);
                if(usable(result)) return result;
            }
        } catch(Exception e) { EverlastingSkins.logger.debug("Ely.by lookup failed for {}",name,e); }
        finally { if(c!=null) c.disconnect(); }
        return null;
    }
}
