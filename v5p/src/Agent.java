import arc.Core;
import arc.struct.*;
import arc.math.geom.Vec2;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.Item;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock;

import static mindustry.Vars.*;

/** Stage-1 bot actions: move, mine, build, + text state summary. Runs from the update loop. */
public class Agent {
    // ---- shared command state read by NetClientG.sync ----
    public static volatile Tile mineTarget;
    public static final Queue<BuildPlan> plans = new Queue<>();
    public static volatile Block selected;
    public static volatile arc.math.geom.Position aim; // set => unit shoots at it

    static float tickNow = 0;

    // ---------- state summary ----------
    public static String summary() {
        StringBuilder sb = new StringBuilder();
        Unit u = player.unit();
        CoreBlock.CoreBuild core = player.core();
        sb.append("map=").append(state.map == null ? "?" : state.map.name()).append(" wave=").append(state.wave)
          .append(" enemies=").append(state.enemies).append(" team=").append(player.team()).append("\n");
        if (u == null || u.dead) { sb.append("unit=none\n"); }
        else sb.append("unit=").append(u.type.name).append(" tile=(").append(u.tileX()).append(",").append(u.tileY()).append(") hp=").append((int)u.health)
               .append(" mining=").append(u.mineTile == null ? "no" : u.mineTile.x + "," + u.mineTile.y).append(" plans=").append(plans.size).append("\n");
        if (core != null) {
            sb.append("core=(").append(core.tileX()).append(",").append(core.tileY()).append(") items:");
            for (Item it : content.items()) { int n = core.items.get(it); if (n > 0) sb.append(" ").append(it.name).append("=").append(n); }
            sb.append("\n");
            int[] c = new int[1];
            Seq<String> own = new Seq<>();
            ObjectIntMap<String> counts = new ObjectIntMap<>();
            for (int x = core.tileX() - 40; x <= core.tileX() + 40; x++) for (int y = core.tileY() - 40; y <= core.tileY() + 40; y++) {
                Tile t = world.tile(x, y);
                if (t != null && t.build != null && t.build.tile == t && t.team() == player.team()) counts.increment(t.block().name);
            }
            sb.append("buildings:"); for (var e : counts) sb.append(" ").append(e.key).append("x").append(e.value); sb.append("\n");
            for (Item it : new Item[]{Items.copper, Items.lead, Items.sand, Items.coal, Items.scrap}) {
                Tile t = nearestOre(it, core.tile, 30);
                if (t != null) sb.append("ore ").append(it.name).append(" nearest=(").append(t.x).append(",").append(t.y).append(") ");
            }
            sb.append("\n");
        }
        if (core != null) {
            sb.append("core_hp=").append((int)core.health).append("/").append((int)core.maxHealth).append(" turrets:");
            for (Building b : Brain.turrets(core.tile)) sb.append(" ").append(b.block.name).append("(").append(b.tileX()).append(",").append(b.tileY()).append(") ammo=").append(((mindustry.world.blocks.defense.turrets.Turret.TurretBuild)b).totalAmmo);
            sb.append("\n");
            sb.append("enemies:");
            int shown = 0;
            for (Unit e : Groups.unit) if (e.team != player.team() && shown++ < 6) sb.append(" ").append(e.type.name).append("(").append(e.tileX()).append(",").append(e.tileY()).append(") d=").append((int)(e.dst(core) / tilesize));
            sb.append("\n");
        }
        int ep = 0; for (Unit e : Groups.unit) if (e.team != player.team()) ep++;
        sb.append("enemy_units=").append(ep).append(" players=").append(Groups.player.size());
        return sb.toString();
    }

    public static Tile nearestOre(Item item, Tile from, int radius) {
        Tile best = null; float bd = 1e9f;
        for (int x = from.x - radius; x <= from.x + radius; x++) for (int y = from.y - radius; y <= from.y + radius; y++) {
            Tile t = world.tile(x, y);
            if (t == null || t.block() != Blocks.air || t.drop() != item || t.overlay() == Blocks.air) continue;
            float d = Mathf2.dst(x, y, from.x, from.y);
            if (d < bd) { bd = d; best = t; }
        }
        return best;
    }
    static class Mathf2 { static float dst(float a, float b, float c, float d) { return (float)Math.hypot(a - c, b - d); } }

    // ---------- movement ----------
    /** call every frame; moves the unit toward world coords. returns true when arrived. */
    public static boolean moveTo(float wx, float wy, float arrive) {
        Unit u = player.unit();
        if (u == null || u.dead) return false;
        float dst = u.dst(wx, wy);
        if (dst <= arrive) { u.vel.setZero(); return true; }
        Vec2 v = new Vec2(wx - u.x, wy - u.y).setLength(u.speed());
        u.movePref(v);
        u.lookAt(wx, wy);
        return false;
    }

    // ---------- build planning ----------
    /** cheapest drill spot: among spots with >=3 ore tiles pick min (path length*2 + distance) */
    public static Tile bestDrillSpot(Item item, Tile core, int radius) {
        Seq<Tile> cands = new Seq<>();
        for (int x = core.x - radius; x <= core.x + radius; x++) for (int y = core.y - radius; y <= core.y + radius; y++) {
            Tile t = world.tile(x, y);
            if (t == null || !Build.validPlace(Blocks.mechanicalDrill, player.team(), x, y, 0)) continue;
            int n = 0;
            for (Tile q : t.getLinkedTilesAs(Blocks.mechanicalDrill, new Seq<>())) if (q.drop() == item && q.block() == Blocks.air) n++;
            if (n >= 3) cands.add(t);
        }
        cands.sort(t -> Mathf2.dst(t.x, t.y, core.x, core.y));
        Tile best = null; float bs = 1e9f; int tried = 0;
        for (Tile t : cands) {
            if (tried++ > 25) break;
            Seq<BuildPlan> p = conveyorPath(t, core);
            if (p == null) continue;
            float score = p.size * 2f + Mathf2.dst(t.x, t.y, core.x, core.y);
            if (score < bs) { bs = score; best = t; }
        }
        return best;
    }

    /** BFS conveyor path from tiles adjacent to the drill to tiles adjacent to the core. Returns plans (conveyors only). */
    public static Seq<BuildPlan> conveyorPath(Tile drill, Tile coreTile) {
        return conveyorPath(drill, coreTile.build.tile.getLinkedTilesAs(coreTile.block(), new Seq<>()));
    }
    public static Seq<BuildPlan> conveyorPath(Tile drill, Seq<Tile> cTiles) {
        Seq<Tile> dTiles = drill.getLinkedTilesAs(Blocks.mechanicalDrill, new Seq<>());
        ObjectSet<Tile> dSet = new ObjectSet<>(); for (Tile t : dTiles) dSet.add(t);
        ObjectSet<Tile> cSet = new ObjectSet<>(); for (Tile t : cTiles) cSet.add(t);
        int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
        ObjectMap<Tile, Tile> prev = new ObjectMap<>();
        Queue<Tile> q = new Queue<>();
        for (Tile t : dTiles) for (int i = 0; i < 4; i++) {
            Tile n = world.tile(t.x + dx[i], t.y + dy[i]);
            if (n == null || dSet.contains(n) || prev.containsKey(n)) continue;
            if (!okConv(n)) continue;
            prev.put(n, null); q.addLast(n);
        }
        Tile goal = null;
        while (!q.isEmpty() && goal == null) {
            Tile c = q.removeFirst();
            for (int i = 0; i < 4; i++) {
                Tile n = world.tile(c.x + dx[i], c.y + dy[i]);
                if (n == null) continue;
                if (cSet.contains(n)) { goal = c; break; }
                if (prev.containsKey(n) || dSet.contains(n) || !okConv(n)) continue;
                prev.put(n, c); q.addLast(n);
            }
        }
        if (goal == null) return null;
        Seq<Tile> path = new Seq<>();
        for (Tile t = goal; t != null; t = prev.get(t)) path.add(t);
        path.reverse(); // from drill side to core
        Seq<BuildPlan> out = new Seq<>();
        for (int i = 0; i < path.size; i++) {
            Tile t = path.get(i);
            int rot;
            if (i + 1 < path.size) { Tile n = path.get(i + 1); rot = dirOf(n.x - t.x, n.y - t.y); }
            else { rot = 0; for (int k = 0; k < 4; k++) { Tile n = world.tile(t.x + dx[k], t.y + dy[k]); if (n != null && cSet.contains(n)) { rot = k; break; } } }
            out.add(new BuildPlan(t.x, t.y, rot, Blocks.conveyor));
        }
        return out;
    }
    static boolean okConv(Tile t) { return Build.validPlace(Blocks.conveyor, player.team(), t.x, t.y, 0); }
    static int dirOf(int x, int y) { return x > 0 ? 0 : y > 0 ? 1 : x < 0 ? 2 : 3; }

    public static boolean built(BuildPlan p) {
        Tile t = world.tile(p.x, p.y);
        return t != null && t.build != null && t.block() == p.block && !(t.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild) && t.team() == player.team();
    }

    // ---------- scripted stage-1 demo ----------
    static int step = 0; static float wait = 0; static int startCopper;
    static Seq<BuildPlan> all = new Seq<>(); static Tile drillSpot;
    static float stepStart = 0;
    public static boolean done = false;
    public static String result = "";

    public static void update(float delta) {
        tickNow += delta; wait += delta;
        Unit u = player.unit();
        CoreBlock.CoreBuild core = player.core();
        if (u == null || u.dead || core == null || !state.isGame()) return;
        if (System.getProperty("schem") != null) { SchemTest.update(delta); return; }
        if (System.getProperty("brain") != null) { Brain.update(delta); return; }
        if (System.getProperty("inspect") != null) {
            if (wait > 180 && !done) {
                Log.info("PROBE-INSPECT:\n@", summary());
                for (int[] c : new int[][]{{61,98},{61,99},{61,100},{61,101},{62,98}}) { Tile t = world.tile(c[0], c[1]); Log.info("PROBE-INSPECT tile (@,@) block=@ team=@", c[0], c[1], t.block().name, t.team()); }
                done = true;
            }
            return;
        }
        switch (step) {
            case 0: { // start, wait a moment, print summary
                if (wait < 120) return;
                Log.info("PROBE-SUMMARY-0:\n@", summary());
                // MOVE target: 8 tiles right of core
                step = 1; wait = 0; return;
            }
            case 1: { // MOVE
                float tx = core.x + 8 * tilesize, ty = core.y + 4 * tilesize;
                if (moveTo(tx, ty, 6)) { Log.info("PROBE-MOVE: arrived local=(@,@) target=(@,@)", u.x, u.y, tx, ty); step = 2; wait = 0; }
                else if (wait > 60 * 20) { Log.info("PROBE-MOVE: TIMEOUT at (@,@)", u.x, u.y); step = 2; wait = 0; }
                return;
            }
            case 2: { // verify movement persisted after server snapshots
                if (wait < 180) return;
                float tx = core.x + 8 * tilesize, ty = core.y + 4 * tilesize;
                Log.info("PROBE-MOVE-VERIFY: pos=(@,@) dist_to_target=@ (persisted after 3s of server snapshots)", u.x, u.y, u.dst(tx, ty));
                step = 3; wait = 0; return;
            }
            case 3: case 8: { // MINE by hand: step3 copper, step8 lead
                Item item = step == 3 ? Items.copper : Items.lead;
                int goalAdd = step == 3 ? 15 : 40;
                Tile ore = nearestOre(item, core.tile, 40);
                if (ore == null) { Log.info("PROBE-MINE: no @ ore", item); step = step == 3 ? 8 : 4; return; }
                if (mineTarget == null) {
                    startCopper = core.items.get(item);
                    Log.info("PROBE-MINE: target @ ore (@,@) before=@ canMine=@", item, ore.x, ore.y, startCopper, u.canMine(item));
                }
                float range = u.type.mineRange * 0.8f;
                if (u.dst(ore.worldx(), ore.worldy()) > range) moveTo(ore.worldx(), ore.worldy(), range * 0.6f);
                else { u.vel.setZero(); mineTarget = ore; u.mineTile = ore; }
                if (mineTarget != null) {
                    int c = core.items.get(item);
                    if (c >= startCopper + goalAdd) { Log.info("PROBE-MINE: OK @ @ -> @ (mined by hand)", item, startCopper, c); mineTarget = null; u.mineTile = null; step = step == 3 ? 8 : 4; wait = 0; }
                    else if (wait > 60 * 60) { Log.info("PROBE-MINE: TIMEOUT @ @ -> @", item, startCopper, c); mineTarget = null; u.mineTile = null; step = step == 3 ? 8 : 4; wait = 0; }
                }
                return;
            }
            case 4: { // BUILD drill + conveyors
                drillSpot = bestDrillSpot(Items.copper, core.tile, 25);
                if (drillSpot == null) { Log.info("PROBE-BUILD: no drill spot"); step = 5; return; }
                Seq<BuildPlan> path = conveyorPath(drillSpot, core.tile);
                if (path == null) { Log.info("PROBE-BUILD: no conveyor path"); step = 5; return; }
                all.clear();
                all.add(new BuildPlan(drillSpot.x, drillSpot.y, 0, Blocks.mechanicalDrill));
                all.addAll(path);
                Log.info("PROBE-BUILD: conveyor cost=@ drill cost=@", Blocks.conveyor.requirements[0].item.name + "x" + Blocks.conveyor.requirements[0].amount + (Blocks.conveyor.requirements.length > 1 ? "," + Blocks.conveyor.requirements[1].item.name + "x" + Blocks.conveyor.requirements[1].amount : ""), Blocks.mechanicalDrill.requirements[0].item.name + "x" + Blocks.mechanicalDrill.requirements[0].amount);
                Log.info("PROBE-BUILD: drill at (@,@), @ conveyors; copper=@ lead=@", drillSpot.x, drillSpot.y, path.size, core.items.get(Items.copper), core.items.get(Items.lead));
                step = 6; wait = 0; startCopper = core.items.get(Items.copper); return;
            }
            case 6: { // executing build: move near, feed plans, check completion
                float cx = drillSpot.worldx(), cy = drillSpot.worldy();
                if (u.dst(cx, cy) > 120) moveTo(cx, cy, 80); else u.vel.setZero();
                plans.clear();
                int missing = 0;
                for (BuildPlan p : all) if (!built(p)) { plans.addLast(p); missing++; }
                if (missing == 0) { Log.info("PROBE-BUILD: all @ blocks present in world state", all.size); step = 7; wait = 0; return; }
                if (wait % 300 < delta) Log.info("PROBE-BUILD: waiting, missing=@ copper=@ lead=@", missing, core.items.get(Items.copper), core.items.get(Items.lead));
                if (wait > 60 * 90) { Log.info("PROBE-BUILD: TIMEOUT missing=@", missing); plans.clear(); step = 7; wait = 0; }
                return;
            }
            case 7: { // verify drill output reaches core
                plans.clear();
                if (wait < 60 * 30) return;
                Log.info("PROBE-DRILL: copper start-of-build=@ now=@", startCopper, core.items.get(Items.copper));
                for (BuildPlan p : all) { Tile t = world.tile(p.x, p.y); Log.info("PROBE-PLAN (@,@) want=@ got=@ build=@", p.x, p.y, p.block.name, t.block().name, t.build == null ? "null" : t.build.getClass().getSimpleName()); }
                Log.info("PROBE-SUMMARY-END:\n@", summary());
                step = 5; return;
            }
            case 5: done = true; return;
        }
    }
}
