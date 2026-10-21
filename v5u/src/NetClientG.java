import arc.Application;
import arc.Core;
import arc.math.Rand;
import arc.struct.IntSet;
import arc.util.Interval;
import arc.util.Log;
import arc.util.Time;
import arc.util.serialization.Base64Coder;
import mindustry.Vars;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.net.NetworkIO;
import mindustry.net.Packets;
import mindustry.ui.fragments.LoadingFragment;

import java.util.Locale;
import java.util.Objects;
import java.util.zip.InflaterInputStream;

public class NetClientG extends NetClient {
    public static java.nio.ByteBuffer magic;
    public NetG netG;
    public IntSet removed = Main.getField("removed",NetClient.class, IntSet.class,this);
    public void reset(){
        netG.setClientLoaded(false);
        removed.clear();
        Main.setField("timeoutTime",NetClient.class,0.0F,this);
        Main.setField("connecting",NetClient.class,true,this);
        Main.setField("quietReset",NetClient.class,false,this);
        Main.setField("quiet",NetClient.class,false,this);
        Main.setField("lastSent",NetClient.class,0,this);
        Groups.clear();
    }
    public NetClientG(){
        netG = (NetG) Vars.net;
        netG.handCli(Packets.Connect.class, (packet) -> {
            Log.info("Connecting to server: @", new Object[]{packet.addressTCP});
            Vars.player.admin = false;
            this.reset();
            if (magic != null) { Vars.net.send(magic, true); magic = null; Log.info("PROBE: sent CLaJ join packet"); }
            if (!Vars.net.client()) {
                Log.info("Connection canceled.");
                this.disconnectQuietly();
            } else {
                String locale = Core.settings.getString("locale");
                if (locale.equals("default")) {
                    locale = Locale.getDefault().toString();
                }

                Packets.ConnectPacket c = new Packets.ConnectPacket();
                c.name = Vars.player.name;
                c.locale = locale;
                c.mods = Vars.mods.getModStrings();
                c.mobile = Vars.mobile;
                c.versionType = Version.type;
                c.color = Vars.player.color.rgba();
                c.usid = this.getUsid(packet.addressTCP);
                c.uuid = Vars.platform.getUUID();
                if (c.uuid == null) {
                    Log.info(c.name+"::"+c.name+"::"+c);
                } else {
                    Vars.net.send(c, true);
                }
            }
        });
        netG.handCli(Packets.Disconnect.class, (packet) -> {
            if (!Main.getField("quietReset", NetClient.class, Boolean.class, this)) {
                Main.setField("connecting", NetClient.class, false, this);
                Vars.logic.reset();
                Vars.platform.updateRPC();
                Vars.player.name = Core.settings.getString("name");
                Vars.player.color.set(Core.settings.getInt("color-0"));
                if (!Main.getField("quiet",NetClient.class, Boolean.class, this)) {
                    if (packet.reason != null) {
                        Log.info(packet.reason);
                    }
                }
            }
        });
        netG.handCli(Packets.WorldStream.class, (data) -> {
            Log.info("Received world data: @ bytes.", new Object[]{data.stream.available()});
            NetworkIO.loadWorld(new InflaterInputStream(data.stream));
            this.finishConnecting();
        });
    }

    private void finishConnecting() {
        Vars.state.set(GameState.State.playing);
        Main.setField("connecting", NetClient.class,false,this);
        Vars.net.setClientLoaded(true);
        Core.app.post(Call::connectConfirm);
        Platform var10001 = Vars.platform;
        Objects.requireNonNull(var10001);
        Time.runTask(40.0F, var10001::updateRPC);
    }

    String getUsid(String ip) {
        if (ip.contains("/")) {
            ip = ip.substring(ip.indexOf("/") + 1);
        }

        if (Core.settings.getString("usid-" + ip, (String)null) != null) {
            return Core.settings.getString("usid-" + ip, (String)null);
        } else {
            byte[] bytes = new byte[8];
            (new Rand()).nextBytes(bytes);
            String result = new String(Base64Coder.encode(bytes));
            Core.settings.put("usid-" + ip, result);
            return result;
        }
    }
    public static void connect(String ip, int port) {
        if (Vars.steam || !ip.startsWith("steam:")) {
            Vars.netClient.disconnectQuietly();
            Vars.logic.reset();
            Vars.logic.reset();
            Vars.net.reset();
            Vars.netClient.beginConnecting();
            Vars.net.connect(ip,port, () -> {});
        }
    }
    public Interval timer = new Interval(4);
    void sync() {
        if (this.timer.get(0, 4.0F)) {
            Unit unit = Vars.player.unit();
            boolean dead = Vars.player.dead() || unit == null;
            int uid = dead ? -1 : unit.id;
            int ls = Main.getField("lastSent", NetClient.class, Integer.class,this);
            Main.setField("lastSent", NetClient.class,ls+1,this);
            float x = dead ? Vars.player.x : unit.x, y = dead ? Vars.player.y : unit.y;
            float rot = dead ? 0f : unit.rotation;
            float vx = dead ? 0f : unit.vel.x, vy = dead ? 0f : unit.vel.y;
            arc.struct.Queue<mindustry.entities.units.BuildPlan> pl = Agent.plans.isEmpty() ? null : Agent.plans;
            boolean bld = pl != null;
            arc.math.geom.Position am = Agent.aim; boolean shoot = am != null && !dead;
            Call.clientSnapshot(ls, uid, dead, x, y, shoot ? am.getX() : x, shoot ? am.getY() : y, rot, 0f, vx, vy, dead ? null : unit.mineTile, false, shoot, false, bld, bld ? pl.first().block : null, bld ? pl.first().rotation : 0, pl, 0, 0, 0, 0);
        }

        if (this.timer.get(1, 60.0F)) {
            Call.ping(Time.millis());
        }

    }



    public void update() {
        if (Vars.net.client()) {
            if (Vars.state.isGame()) {
                if (!Main.getField("connecting", NetClient.class, Boolean.class,this)) {
                    Agent.update(Time.delta);
                    this.sync();
                }
            } else if (!Main.getField("connecting", NetClient.class, Boolean.class,this)) {
                Vars.net.disconnect();
            } else {
                float time = Main.getField("timeoutTime", NetClient.class,Float.class,this)+Time.delta;
                Main.setField("timeoutTime", NetClient.class,time,this);
                if (time > 1800.0F) {
                    Log.err("Failed to load data!", new Object[0]);
                    Main.setField("quiet", NetClient.class,true,this);
                    Log.info("deconnect");
                    Vars.net.disconnect();
                    Main.setField("timeoutTime", NetClient.class,0.0F,this);
                }
            }

        }
    }
    public static void worldDataBegin() {
        Groups.clear();
        ((NetClientG)Vars.netClient).removed.clear();
        Vars.logic.reset();
        Main.setField("connecting",NetClient.class,true,Vars.netClient);
        Vars.net.setClientLoaded(false);
    }
}
