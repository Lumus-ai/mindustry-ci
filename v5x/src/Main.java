import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.util.Align;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.*;
import mindustry.server.ServerLauncher;

import java.lang.reflect.Field;
import static mindustry.Vars.*;

public class Main {
    public static void main(String[] args) {
        //System.out.println("Hello world!");
        Events.on(SendMessageCallPacket2.class,i->{
            Log.info(i.message);
        });
        /*Events.on(InfoPopupCallPacket.class,i->{
            Log.info(Align.topLeft);
            Log.info(i.align+"::"+i.duration+"::"+i.bottom+"::"+i.top+"::"+i.left+"::"+i.right);

        });*/
        Events.on(InfoPopupReliableCallPacket.class,i->{
        });
        Events.on(EventType.ServerLoadEvent.class,i->{
            try{
                java.lang.reflect.Field uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
                sun.misc.Unsafe u=(sun.misc.Unsafe)uf.get(null);
                mindustry.core.Control c=(mindustry.core.Control)u.allocateInstance(mindustry.core.Control.class);
                c.sound=(mindustry.audio.SoundControl)u.allocateInstance(mindustry.audio.SoundControl.class);
                Vars.control=c;
            }catch(Throwable t){ t.printStackTrace(); }
            player = Player.create();
            player.name = System.getProperty("pname","Cipher-Bot");

            playerColors[0] = Color.red;
            net = new NetG(Vars.platform.getNet());
            netClient = new NetClientG();
            Core.app.addListener(netClient);
            Log.info("PROBE: connecting local 127.0.0.1:6567");
            String cj=System.getProperty("claj");
            NetClientG.connect("127.0.0.1",Integer.getInteger("port",6567));
            final int[] st={0};
            arc.util.Timer.schedule(()->{
                st[0]+=2;
                if(Agent.done||st[0]>=Integer.getInteger("maxsec",300)){ Log.info("PROBE: finishing done="+Agent.done); Vars.net.disconnect(); System.exit(0); }
            },2,2);
        });
        //ServerLauncher.main(new String[]{});
        ServerL.main(new String[]{});
    }
    public static <T> T getField(String name,Class requireClass,Class<T> returnType,Object obj){
        try {
            Field field = requireClass.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(obj);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public static void setField(String name,Class requireClass,Object setting,Object obj){
        try{
            Field field = requireClass.getDeclaredField(name);
            field.setAccessible(true);
            field.set(obj,setting);
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }
}
