package manhunt.divineflood;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

public final class ClientProxy extends DivineFlood.Proxy {
    private int mode;
    private long untilNano;
    @Override public void init(){MinecraftForge.EVENT_BUS.register(this);}
    @Override public void receive(DivineFlood.Packet p){
        Minecraft.getMinecraft().addScheduledTask(()->{
            mode=p.mode; untilNano=System.nanoTime()+Math.max(0L,p.remaining)*1000000L;
        });
    }
    @SubscribeEvent public void disconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        Minecraft.getMinecraft().addScheduledTask(()->{mode=0;untilNano=0;});
    }
    private static void rect(int x,int y,int w,int h,int c){Gui.drawRect(x,y,x+w,y+h,c);}
    private static void center(FontRenderer f,String s,int x,int y,int c){f.drawStringWithShadow(s,x-f.getStringWidth(s)/2f,y,c);}
    private static void icon(int x,int y){
        // Original pixel icon: gold leaf, halo, crimson robe, dark iconographic outlines.
        rect(x,y,32,40,0xff3a180f); rect(x+1,y+1,30,38,0xffd6a74e);
        rect(x+3,y+3,26,34,0xff9c642e); rect(x+4,y+4,24,32,0xffd4a54a);
        rect(x+8,y+5,16,16,0xfff6d887); rect(x+6,y+9,20,9,0xfff6d887);
        rect(x+10,y+7,12,13,0xff5a3323);
        rect(x+12,y+9,8,9,0xffd69a65); rect(x+13,y+11,2,1,0xff432b25);
        rect(x+18,y+11,2,1,0xff432b25); rect(x+14,y+16,5,4,0xff754326);
        rect(x+7,y+23,18,13,0xff54242b); rect(x+10,y+21,12,15,0xff8b343b);
        rect(x+14,y+22,4,14,0xffd5ac65); rect(x+19,y+25,7,9,0xff4b3124);
        rect(x+20,y+26,5,7,0xffe4c47c); rect(x+22,y+27,1,5,0xff7b302b);
        rect(x+21,y+28,3,1,0xff7b302b);
        rect(x+5,y+6,2,2,0xff8a4326); rect(x+25,y+6,2,2,0xff8a4326);
    }
    @SubscribeEvent public void render(RenderGameOverlayEvent.Post e) {
        if(e.getType()!=RenderGameOverlayEvent.ElementType.ALL || (mode!=1 && mode!=2)) return;
        Minecraft mc=Minecraft.getMinecraft();
        if(mc.world==null || mc.player==null || mc.gameSettings.hideGUI) return;
        int screen=e.getResolution().getScaledWidth();
        int w=Math.min(282,screen-8); if(w<155) return;
        int x=(screen-w)/2,y=5,h=48;
        int gold=0xffd3aa63, dark=0xff261410;
        GlStateManager.pushMatrix();
        rect(x+2,y+2,w,h,0x99000000);
        rect(x,y,w,h,dark); rect(x+1,y+1,w-2,h-2,gold); rect(x+2,y+2,w-4,h-4,0xff60312b);
        rect(x+4,y+4,w-8,h-8,0xff281915);
        icon(x+5,y+4);
        int mid=x+39+(w-44)/2;
        FontRenderer f=mc.fontRenderer;
        center(f,mode==1?"ВРЕМЯ ИСКУПЛЕНИЯ":"ГНЕВ БОЖИЙ",mid,y+6,0xffe7ca85);
        long seconds=Math.max(0L,(untilNano-System.nanoTime()+999999999L)/1000000000L);
        String clock=String.format(Locale.ROOT,"%02dд %02d:%02d:%02d",seconds/86400,(seconds/3600)%24,(seconds/60)%60,seconds%60);
        center(f,mode==1?clock:"ВСЕМИРНЫЙ ПОТОП",mid,y+19,seconds<3600||mode==2?0xffff7765:0xffffe7ba);
        center(f,mode==1?"До Всемирного потопа":"Воды и огонь обрушились",mid,y+32,0xffd0b999);
        // Narrow gilded progress bar below the text.
        int bw=w-51;
        rect(x+43,y+43,bw,2,0xff472923);
        int remain=mode==1?(int)Math.min(bw,(seconds*bw)/259200L):bw;
        rect(x+43,y+43,remain,2,mode==2?0xffc94537:gold);
        GlStateManager.popMatrix();
        GlStateManager.color(1,1,1,1);
    }
}
