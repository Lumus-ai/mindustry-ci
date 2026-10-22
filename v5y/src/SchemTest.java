import arc.files.Fi;
import arc.struct.*;
import arc.util.Log;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock;
import static mindustry.Vars.*;

public class SchemTest {
    static int step = 0; static float wait = 0;
    static Schematic sc; static Seq<BuildPlan> plans; static Brain.Goal goal;
    static Schematic load(String path) throws Exception { return Schematics.read(new Fi(path)); }
    public static void update(float dt) {
        wait += dt;
        CoreBlock.CoreBuild core = player.core(); mindustry.gen.Unit u = player.unit();
        if (core == null || u == null || u.dead) return;
        try {
            if (step == 0) {
                if (wait < 120) return;
                // parse-only check of every file in dir
                if (schematics == null) schematics = new Schematics();
                Fi dir = new Fi(System.getProperty("schemdir"));
                int ok = 0, bad = 0;
                for (Fi f : dir.findAll(x -> x.extension().equals("msch"))) {
                    try {
                        Schematic s = Schematics.read(f);
                        ok++;
                        Log.info("SCHEM-PARSE ok @ size=@x@ blocks=@ cost=@ powerProd=@ powerUse=@", f.name(), s.width, s.height, s.tiles.size, s.requirements().toString(), s.powerProduction(), s.powerConsumption());
                    } catch (Throwable e) { bad++; Log.info("SCHEM-PARSE FAIL @ : @", f.name(), e); }
                }
                Log.info("SCHEM-PARSE total ok=@ bad=@", ok, bad);
                sc = load(System.getProperty("schem"));
                Log.info("SCHEM: chosen @ requirements=@ name=@", sc.name(), sc.requirements(), sc.tags.get("name"));
                step = 1; wait = 0; return;
            }
            if (step == 1) { // resource check loop
                boolean ok = true; StringBuilder miss = new StringBuilder();
                for (ItemStack st : sc.requirements().toSeq()) if (core.items.get(st.item) < st.amount) { ok = false; miss.append(st.item.name).append(" need ").append(st.amount).append(" have ").append(core.items.get(st.item)).append("; "); }
                if (!ok) { if (wait % 600 < dt) Log.info("SCHEM: insufficient resources: @", miss); if (wait > 60 * 25) { Log.info("SCHEM: giving up, resources missing"); Agent.done = true; } return; }
                // find site
                Tile ct = core.tile; Tile best = null; float bd = 1e9f; Seq<BuildPlan> bp = null;
                for (int x = ct.x - 40; x <= ct.x + 40; x++) for (int y = ct.y - 40; y <= ct.y + 40; y++) {
                    float d = Agent.Mathf2.dst(x, y, ct.x, ct.y); if (d < 8 || d >= bd) continue;
                    Seq<BuildPlan> p = schematics.toPlans(sc, x, y);
                    boolean valid = true;
                    for (BuildPlan q : p) if (!mindustry.world.Build.validPlace(q.block, player.team(), q.x, q.y, q.rotation)) { valid = false; break; }
                    if (valid) { bd = d; best = world.tile(x, y); bp = p; }
                }
                if (best == null) { Log.info("SCHEM: no valid site found near core"); Agent.done = true; return; }
                plans = bp;
                // build order: nearest to core first
                plans.sort(q -> Agent.Mathf2.dst(q.x, q.y, ct.x, ct.y));
                Log.info("SCHEM: placing @ plans at origin (@,@), @ tiles from core", plans.size, best.x, best.y, (int)bd);
                goal = new Brain.BuildGoal(sc.name(), plans); step = 2; wait = 0; return;
            }
            if (step == 2) {
                if (goal.step(dt, core, u)) {
                    int ok = 0; for (BuildPlan q : plans) if (Agent.built(q)) ok++;
                    Log.info("SCHEM-RESULT: @/@ blocks present in world state; core items now: @", ok, plans.size, core.items.toString());
                    Agent.plans.clear(); step = 3; wait = 0;
                }
                return;
            }
            if (step == 3) { if (wait > 120) { Log.info("SCHEM-SUMMARY:\n@", Agent.summary()); Agent.done = true; } }
        } catch (Throwable e) { Log.err("SCHEM exception", e); Agent.done = true; }
    }
}
