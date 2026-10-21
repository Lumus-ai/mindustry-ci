import arc.util.Log;
import com.sun.net.httpserver.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.storage.CoreBlock;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import static mindustry.Vars.*;

public class Stream {
    static byte[] mapPng; static int mapW = -1, mapH = -1;
    static final String HTML = "<!doctype html><meta charset=utf-8><title>Mindustry bot live</title><body style='margin:0;background:#111;color:#ddd;font:13px monospace'><div style='display:flex'><div style='position:relative'><canvas id=c width=900 height=900 style='background:#000'></canvas></div><div style='padding:8px;width:420px'><div id=st></div><pre id=lg style='white-space:pre-wrap'></pre></div></div><script>"
     + "var c=document.getElementById('c'),x=c.getContext('2d'),img=new Image(),mw=0,mh=0,S=null,ver=0;function loadMap(){img=new Image();img.src='map.png?'+Date.now()}loadMap();setInterval(loadMap,20000);"
     + "var cam=null;function draw(){if(!S)return;mw=S.w;mh=S.h;var vw=130,sc=900/vw;var cx=S.px,cy=S.py;if(cam==null)cam={x:cx,y:cy};cam.x+=(cx-cam.x)*0.15;cam.y+=(cy-cam.y)*0.15;var ox=cam.x-vw/2,oy=cam.y-vw/2;x.imageSmoothingEnabled=false;x.clearRect(0,0,900,900);"
     + "if(img.complete&&img.naturalWidth){x.drawImage(img,ox,mh-oy-vw,vw,vw,0,0,900,900)}"
     + "function X(t){return (t-ox)*sc}function Y(t){return 900-(t-oy+1)*sc+ (0)}"
     + "for(var i=0;i<S.b.length;i++){var b=S.b[i];var col=b.t==1?'#ffd34d':b.t==2?'#ff5050':'#8af';var s=b.s*sc;var px=(b.x-(b.s-1)/2-ox)*sc,py=(b.y-(b.s-1)/2-oy)*sc;py=900-py-s;if(b.n.indexOf('conveyor')>=0){x.fillStyle=col+'88';x.fillRect(px+s*0.25,py+s*0.25,s*0.5,s*0.5)}else{x.fillStyle=col;x.globalAlpha=0.75;x.fillRect(px+1,py+1,s-2,s-2);x.globalAlpha=1;x.fillStyle='#000';if(b.a!=null){x.fillRect(px,py-4,s,3);x.fillStyle=b.a>0?'#6f6':'#f44';x.fillRect(px,py-4,s*Math.min(1,b.a/30),3)}}}"
     + "for(var i=0;i<S.u.length;i++){var u=S.u[i];var px=(u.x-ox)*sc,py=900-(u.y-oy)*sc;x.fillStyle=u.t==1?'#fff':'#f33';x.beginPath();x.arc(px,py,u.f?4:6,0,7);x.fill();if(u.me){x.strokeStyle='#0f0';x.strokeRect(px-9,py-9,18,18)}}"
     + "var st=document.getElementById('st');st.innerHTML='<b>'+S.goal+'</b><br>copper '+S.cu+' lead '+S.pb+' graphite '+S.gr+'<br>core hp '+S.chp+'/'+S.cmax+'<br>wave '+S.wave+' enemies '+S.en+'<br>t='+S.t+'s';document.getElementById('lg').textContent=S.log.join('\\n')}"
     + "setInterval(function(){fetch('state.json').then(r=>r.json()).then(function(j){S=j;draw()}).catch(function(){})},500);setInterval(draw,100);</script>";

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "'"); }

    static byte[] png() {
        try {
            if (world == null || world.tiles == null) return new byte[0];
            int w = world.width(), h = world.height();
            BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) {
                Tile t = world.tile(x, y); int c = 0;
                if (t != null) {
                    int rgb;
                    if (t.block() != null && t.block().solid && !(t.build != null)) rgb = t.block().mapColor.rgba() >>> 8;
                    else if (t.overlay() != null && t.overlay().itemDrop != null) rgb = t.overlay().itemDrop.color.rgba() >>> 8;
                    else rgb = t.floor().mapColor.rgba() >>> 8;
                    c = rgb == 0 ? 0x20242a : rgb;
                }
                im.setRGB(x, h - 1 - y, c);
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(im, "png", o); return o.toByteArray();
        } catch (Throwable e) { Log.err(e); return new byte[0]; }
    }

    static String state() {
        StringBuilder sb = new StringBuilder();
        try {
            CoreBlock.CoreBuild core = player == null ? null : player.core();
            Unit me = player == null ? null : player.unit();
            sb.append("{\"w\":").append(world.width()).append(",\"h\":").append(world.height());
            sb.append(",\"px\":").append(me != null ? me.x / 8f : (core != null ? core.tileX() : 0)).append(",\"py\":").append(me != null ? me.y / 8f : (core != null ? core.tileY() : 0));
            sb.append(",\"cu\":").append(core == null ? 0 : core.items.get(mindustry.content.Items.copper)).append(",\"pb\":").append(core == null ? 0 : core.items.get(mindustry.content.Items.lead)).append(",\"gr\":").append(core == null ? 0 : core.items.get(mindustry.content.Items.graphite));
            sb.append(",\"chp\":").append(core == null ? 0 : (int) core.health).append(",\"cmax\":").append(core == null ? 0 : (int) core.maxHealth);
            sb.append(",\"wave\":").append(state.wave).append(",\"en\":").append(state.enemies).append(",\"t\":").append((int) (Brain.total / 60));
            sb.append(",\"goal\":\"").append(Brain.goal == null ? "-" : esc(Brain.goal.name())).append("\"");
            sb.append(",\"b\":[");
            boolean first = true; int n = 0;
            for (Building b : Groups.build) {
                if (n++ > 1500) break;
                int tm = b.team == Team.sharded ? 1 : b.team == Team.crux ? 2 : 3;
                if (!first) sb.append(","); first = false;
                sb.append("{\"x\":").append(b.tileX()).append(",\"y\":").append(b.tileY()).append(",\"s\":").append(b.block.size).append(",\"t\":").append(tm).append(",\"n\":\"").append(b.block.name).append("\"");
                if (b instanceof Turret.TurretBuild tb) sb.append(",\"a\":").append((int) tb.totalAmmo);
                sb.append("}");
            }
            sb.append("],\"u\":[");
            first = true;
            for (Unit u : Groups.unit) {
                if (u.dead) continue;
                if (!first) sb.append(","); first = false;
                sb.append("{\"x\":").append(u.x / 8f).append(",\"y\":").append(u.y / 8f).append(",\"t\":").append(u.team == (player == null ? null : player.team()) ? 1 : 2).append(",\"f\":").append(u.isFlying()).append(",\"me\":").append(u == me).append("}");
            }
            sb.append("],\"log\":[");
            first = true; int sz = Brain.events.size; int from = Math.max(0, sz - 30);
            for (int i = from; i < sz; i++) { if (!first) sb.append(","); first = false; sb.append("\"").append(esc(Brain.events.get(i))).append("\""); }
            sb.append("]}");
        } catch (Throwable e) { return "{\"w\":1,\"h\":1,\"px\":0,\"py\":0,\"b\":[],\"u\":[],\"log\":[\"err " + esc(String.valueOf(e)) + "\"],\"goal\":\"-\",\"cu\":0,\"pb\":0,\"gr\":0,\"chp\":0,\"cmax\":0,\"wave\":0,\"en\":0,\"t\":0}"; }
        return sb.toString();
    }

    public static void start(int port) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
            s.createContext("/", ex -> { byte[] b = HTML.getBytes("UTF-8"); ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
            s.createContext("/state.json", ex -> { byte[] b = state().getBytes("UTF-8"); ex.getResponseHeaders().add("Content-Type", "application/json"); ex.getResponseHeaders().add("Cache-Control", "no-store"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
            s.createContext("/map.png", ex -> { if (mapPng == null || mapPng.length == 0 || mapW != world.width()) { mapPng = png(); mapW = world.width(); } byte[] b = mapPng; ex.getResponseHeaders().add("Content-Type", "image/png"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
            s.start(); Log.info("STREAM http on @", port);
        } catch (Throwable e) { Log.err(e); }
    }
}
