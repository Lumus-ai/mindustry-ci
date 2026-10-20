import arc.struct.*;
import arc.util.Log;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import mindustry.gen.*;
import mindustry.game.Team;
import mindustry.world.blocks.storage.CoreBlock;

import java.nio.file.Files;
import java.nio.file.Path;

import static mindustry.Vars.*;

/**
 * Adaptation hook: chooses defense mix / build-order nudges from OBSERVED enemy waves.
 * Enable: -Dadapt=1, policy file: -Dadaptpol=policies.json (hot-reloaded on change).
 * An "engagement" = a contiguous period with enemies present; when it ends, its peak
 * air/ground composition becomes the "last wave" the policy reacts to. Works for both
 * natural waves and externally spawned waves, since it watches units, not wave numbers.
 * Pure policy evaluation is separated (eval()) so it can be unit-tested offline.
 */
public class Adaptation {
    public static final boolean enabled = System.getProperty("adapt") != null && !System.getProperty("adapt").equals("0");
    static final String polPath = System.getProperty("adaptpol", "policies.json");

    // ---- observed state ----
    static boolean engagement = false;
    static int curAir, curGround;                       // peak sightings in current engagement
    static int lastAir, lastGround;                     // last completed engagement
    static long totalEngagements = 0;
    static final ObjectIntMap<String> curTypes = new ObjectIntMap<>();
    static final ObjectIntMap<String> lastTypes = new ObjectIntMap<>();
    static String activeRule = "defaults";

    // ---- policy ----
    static class Rule {
        String name;
        ObjectMap<String, Integer> when = new ObjectMap<>();   // key e.g. "lastAir>=", "wave>=" ; value threshold
        ObjectMap<String, Integer> set = new ObjectMap<>();    // duoWant/scatterWant/hailWant/wallWant
    }
    static final Seq<Rule> rules = new Seq<>();
    static final ObjectMap<String, Integer> defaults = new ObjectMap<>();
    static long polMtime = -1;
    static String polError = "";

    static ObjectMap<String, Integer> current = null; // last evaluated wants

    static void loadPolicy() {
        try {
            Path p = Path.of(polPath);
            long mt = Files.getLastModifiedTime(p).toMillis();
            if (mt == polMtime) return;
            polMtime = mt;
            rules.clear(); defaults.clear();
            JsonValue j = new JsonReader().parse(Files.readString(p));
            JsonValue d = j.get("defaults");
            if (d != null) for (JsonValue e = d.child; e != null; e = e.next) defaults.put(e.name, e.asInt());
            JsonValue r = j.get("rules");
            if (r != null) for (JsonValue e = r.child; e != null; e = e.next) {
                Rule rule = new Rule(); rule.name = e.getString("name", "rule" + rules.size);
                JsonValue w = e.get("when");
                if (w != null) for (JsonValue c = w.child; c != null; c = c.next) rule.when.put(c.name, c.asInt());
                JsonValue st = e.get("set");
                if (st != null) for (JsonValue c = st.child; c != null; c = c.next) rule.set.put(c.name, c.asInt());
                rules.add(rule);
            }
            polError = "";
            Log.info("ADAPT: loaded policy @ rules=@ defaults=@", polPath, rules.size, defaults.toString());
        } catch (Exception e) { polError = e.getMessage(); Log.err("ADAPT: policy load failed: " + e); }
    }

    /** metric values the "when" keys read */
    static int metric(String key) {
        return switch (key) {
            case "lastAir" -> lastAir; case "lastGround" -> lastGround;
            case "curAir" -> curAir; case "curGround" -> curGround;
            case "engagements" -> (int) totalEngagements;
            case "maces" -> lastTypes.get("mace", 0);
            case "flares" -> lastTypes.get("flare", 0);
            default -> 0;
        };
    }

    static boolean match(Rule r) {
        for (ObjectMap.Entry<String, Integer> c : r.when) {
            String k = c.key; int v = c.value;
            if (k.endsWith(">=")) { if (metric(k.substring(0, k.length() - 2)) < v) return false; }
            else if (k.endsWith("<=")) { if (metric(k.substring(0, k.length() - 2)) > v) return false; }
        }
        return true;
    }

    static Rule matched; // first matching rule, null if none

    /** Evaluate policy against current observations; returns merged wants (rule over defaults), for display/tests. */
    public static ObjectMap<String, Integer> eval() {
        ObjectMap<String, Integer> out = new ObjectMap<>();
        for (ObjectMap.Entry<String, Integer> e : defaults) out.put(e.key, e.value);
        activeRule = "none";
        matched = null;
        for (Rule r : rules) if (match(r)) { for (ObjectMap.Entry<String, Integer> e : r.set) out.put(e.key, e.value); activeRule = r.name; matched = r; break; }
        return out;
    }

    /** Semantics: no rule matched -> bot keeps its own base want. Rule matched -> its "set" keys are absolute wants; other keys keep base. */
    static int want(String key, int base) {
        if (!enabled || matched == null) return base;
        return matched.set.get(key, base);
    }
    public static int duoWant(int base)    { return want("duoWant", base); }
    public static int scatterWant(int base){ return want("scatterWant", base); }
    public static int hailWant(int base)   { return want("hailWant", base); }
    public static int wallWant(int base)   { return want("wallWant", base); }

    public static String status() {
        return enabled ? "rule=" + activeRule + " lastAir=" + lastAir + " lastGround=" + lastGround + (polError.isEmpty() ? "" : " POLERR=" + polError) : "off";
    }

    // ---- in-game observation (called from Brain.update) ----
    public static void update(float dt, CoreBlock.CoreBuild core, Unit u) {
        if (!enabled) return;
        loadPolicy();
        int air = 0, ground = 0;
        ObjectIntMap<String> types = new ObjectIntMap<>();
        for (Unit e : Groups.unit) if (!e.dead && e.team != player.team() && e.team != Team.derelict) {
            if (e.isFlying()) air++; else ground++;
            types.increment(e.type.name);
        }
        boolean any = air + ground > 0;
        if (any) {
            engagement = true;
            curAir = Math.max(curAir, air); curGround = Math.max(curGround, ground);
            for (ObjectIntMap.Entry<String> e : types) curTypes.put(e.key, Math.max(curTypes.get(e.key, 0), e.value));
        } else if (engagement) {
            engagement = false; totalEngagements++;
            lastAir = curAir; lastGround = curGround;
            lastTypes.clear(); for (ObjectIntMap.Entry<String> e : curTypes) lastTypes.put(e.key, e.value);
            curAir = 0; curGround = 0; curTypes.clear();
            Log.info("ADAPT: engagement ended: air=@ ground=@ types=@ -> reeval", lastAir, lastGround, lastTypes.toString());
        }
        current = eval();
    }

    // ---- offline test hooks ----
    public static void testInject(int air, int ground, ObjectMap<String, Integer> types) {
        lastAir = air; lastGround = ground;
        lastTypes.clear(); if (types != null) for (ObjectMap.Entry<String, Integer> e : types) lastTypes.put(e.key, e.value);
        current = eval();
    }
}
