import arc.struct.*;
import arc.util.Log;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.storage.CoreBlock;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

import static mindustry.Vars.*;

/**
 * Time-series telemetry logger for bot runs. Enable: -Dtelemetry=<file.jsonl>
 * Samples every -DtelemetryInterval=N seconds (default 2). One JSON object per line:
 * header, sample (resources/blocks/turrets/power/units/goal/events), loss (block-count drops), wave (wave change).
 * Pure observation: no game-state mutation. Works on the client view of the world.
 */
public class Telemetry {
    static final boolean enabled = System.getProperty("telemetry") != null;
    static final String path = System.getProperty("telemetry", "telemetry.jsonl");
    static final float intervalTicks = Float.parseFloat(System.getProperty("telemetryInterval", "2")) * 60f;
    static PrintWriter out;
    static float acc = 1e9f; // first sample immediately
    static int lastWave = -1;
    static final ObjectIntMap<String> lastCounts = new ObjectIntMap<>();
    static boolean inited = false;
    static int evIdx = 0;

    static void init() {
        try {
            out = new PrintWriter(new FileWriter(path, false));
            StringBuilder h = new StringBuilder("{\"type\":\"header\"");
            h.append(",\"wall\":\"").append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new Date())).append("\"");
            h.append(",\"map\":\"").append(state.map == null ? "?" : state.map.name()).append("\"");
            h.append(",\"style\":\"").append(System.getProperty("style", "balanced")).append("\"");
            h.append(",\"diff\":\"").append(System.getProperty("diff", "normal")).append("\"");
            h.append(",\"economy\":\"").append(System.getProperty("economy", "0")).append("\"");
            h.append(",\"tactics\":\"").append(System.getProperty("tactics", "0")).append("\"");
            h.append(",\"units\":\"").append(System.getProperty("units", "0")).append("\"");
            h.append(",\"adapt\":\"").append(System.getProperty("adapt", "0")).append("\"");
            h.append(",\"rules\":{\"waves\":").append(state.rules.waves).append(",\"pvp\":").append(state.rules.pvp)
             .append(",\"attackMode\":").append(state.rules.attackMode).append("}");
            h.append("}");
            out.println(h); out.flush();
            Log.info("TELEMETRY: logging to @", path);
        } catch (Exception e) { Log.err("telemetry init failed", e); }
    }

    public static void update(float dt, CoreBlock.CoreBuild core, Unit u) {
        if (!enabled) return;
        if (!inited) { inited = true; init(); if (out == null) { return; } }
        acc += dt;
        if (acc < intervalTicks) return;
        acc = 0;
        try { sample(core, u); } catch (Throwable t) { /* never break the bot over logging */ }
    }

    static void sample(CoreBlock.CoreBuild core, Unit u) {
        float tsec = Brain.total / 60f;
        StringBuilder s = new StringBuilder(2048);
        s.append("{\"type\":\"sample\",\"t\":").append((int) tsec);
        s.append(",\"wave\":").append(state.wave).append(",\"enemies\":").append(state.enemies);
        s.append(",\"core_hp\":").append((int) core.health).append(",\"core_maxhp\":").append((int) core.maxHealth);
        // items
        s.append(",\"items\":{");
        boolean first = true;
        for (Item it : content.items()) { int n = core.items.get(it); if (n > 0) { if (!first) s.append(','); first = false; s.append('\"').append(it.name).append("\":").append(n); } }
        s.append("}");
        // block counts own team near core
        ObjectIntMap<String> counts = new ObjectIntMap<>();
        Tile ct = core.tile;
        for (int x = ct.x - 45; x <= ct.x + 45; x++) for (int y = ct.y - 45; y <= ct.y + 45; y++) {
            Tile t = world.tile(x, y);
            if (t != null && t.build != null && t.build.tile == t && t.team() == player.team()) {
                String bn = t.build.block.name;
                if (!bn.matches("build\\d")) counts.increment(bn); // skip under-construction placeholders
            }
        }
        s.append(",\"blocks\":{");
        first = true;
        for (ObjectIntMap.Entry<String> e : counts) { if (!first) s.append(','); first = false; s.append('\"').append(e.key).append("\":").append(e.value); }
        s.append("}");
        // losses/builds vs previous sample
        StringBuilder lost = new StringBuilder(), built = new StringBuilder();
        for (ObjectIntMap.Entry<String> e : counts) { int d = e.value - lastCounts.get(e.key, 0); if (d < 0) lost.append('\"').append(e.key).append("\":").append(-d).append(','); else if (d > 0) built.append('\"').append(e.key).append("\":").append(d).append(','); }
        for (ObjectIntMap.Entry<String> e : lastCounts) { if (!counts.containsKey(e.key)) lost.append('\"').append(e.key).append("\":").append(e.value).append(','); }
        lastCounts.clear(); for (ObjectIntMap.Entry<String> e : counts) lastCounts.put(e.key, e.value);
        if (lost.length() > 0) lost.setLength(lost.length() - 1);
        if (built.length() > 0) built.setLength(built.length() - 1);
        s.append(",\"lost\":{").append(lost).append("},\"built\":{").append(built).append("}");
        // turret ammo by type
        s.append(",\"turrets\":{");
        ObjectIntMap<String> tcount = new ObjectIntMap<>(), tammo = new ObjectIntMap<>(), tlow = new ObjectIntMap<>();
        for (Building b : Brain.turrets(ct)) {
            Turret.TurretBuild tb = (Turret.TurretBuild) b;
            tcount.increment(b.block.name); tammo.increment(b.block.name, 0, tb.totalAmmo);
            if (tb.totalAmmo < 10) tlow.increment(b.block.name);
        }
        first = true;
        for (ObjectIntMap.Entry<String> e : tcount) { if (!first) s.append(','); first = false;
            s.append('\"').append(e.key).append("\":{\"n\":").append(e.value).append(",\"ammo\":").append(tammo.get(e.key, 0)).append(",\"low\":").append(tlow.get(e.key, 0)).append("}"); }
        s.append("}");
        // power graphs (own team, loaded chunks)
        double bal = 0, stored = 0, cap = 0; float sat = 1f; int ng = 0;
        ObjectSet<PowerGraph> graphs = new ObjectSet<>();
        for (Building b : Groups.build) if (b.team == player.team() && b.power != null) graphs.add(b.power.graph);
        for (PowerGraph g : graphs) { bal += g.getPowerBalance(); stored += g.getBatteryStored(); cap += g.getTotalBatteryCapacity(); sat = Math.min(sat, g.getSatisfaction()); ng++; }
        s.append(",\"power\":{\"graphs\":").append(ng).append(",\"balance_per_tick\":").append(String.format(java.util.Locale.US, "%.1f", bal))
         .append(",\"stored\":").append((int) stored).append(",\"capacity\":").append((int) cap)
         .append(",\"satisfaction\":").append(String.format(java.util.Locale.US, "%.2f", sat)).append("}");
        // units by team/type
        s.append(",\"own_units\":{"); first = true;
        ObjectIntMap<String> own = new ObjectIntMap<>(), foe = new ObjectIntMap<>();
        for (Unit e : Groups.unit) if (!e.dead && e.team != Team.derelict) (e.team == player.team() ? own : foe).increment(e.type.name);
        for (ObjectIntMap.Entry<String> e : own) { if (!first) s.append(','); first = false; s.append('\"').append(e.key).append("\":").append(e.value); }
        s.append("},\"enemy_units\":{"); first = true;
        for (ObjectIntMap.Entry<String> e : foe) { if (!first) s.append(','); first = false; s.append('\"').append(e.key).append("\":").append(e.value); }
        s.append("}");
        // brain state
        s.append(",\"goal\":\"").append(Brain.goal == null ? "-" : esc(Brain.goal.name())).append("\"");
        s.append(",\"scout\":\"").append(esc(Brain.scoutReport)).append("\"");
        // brain events since last sample
        s.append(",\"events\":[");
        while (evIdx < Brain.events.size) { s.append('\"').append(esc(Brain.events.get(evIdx++))).append("\","); }
        if (s.charAt(s.length() - 1) == ',') s.setLength(s.length() - 1);
        s.append("]}");
        out.println(s.toString());
        // wave change marker
        if (state.wave != lastWave) { out.println("{\"type\":\"wave\",\"t\":" + (int) tsec + ",\"wave\":" + state.wave + "}"); lastWave = state.wave; }
        out.flush();
    }

    static String esc(String x) { return x.replace("\\", "\\\\").replace("\"", "'").replace("\n", " "); }
}
