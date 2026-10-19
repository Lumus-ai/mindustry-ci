import arc.struct.*;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.*;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.storage.CoreBlock;
import static mindustry.Vars.*;

/** Rule-based decision loop on the world state. One goal at a time; re-decided whenever the goal finishes or priorities change. */
public class Brain {
    public interface Goal { String name(); boolean step(float dt, CoreBlock.CoreBuild core, Unit u); }

    static Goal goal;
    static float sinceDecide = 0, sinceLog = 0, total = 0;
    public static int drillsBuilt = 0, turretsBuilt = 0, feeds = 0, kills0 = -1;
    public static final Seq<String> events = new Seq<>();
    static void ev(String s) { events.add(s); Log.info("BRAIN: " + s); }

    // ---------- helpers ----------
    static int count(Block b, Tile core) {
        int n = 0;
        for (int x = core.x - 45; x <= core.x + 45; x++) for (int y = core.y - 45; y <= core.y + 45; y++) {
            Tile t = world.tile(x, y);
            if (t != null && t.build != null && t.build.tile == t && t.block() == b && t.team() == player.team()) n++;
        }
        return n;
    }
    static Seq<Building> turrets(Tile core) {
        Seq<Building> out = new Seq<>();
        for (int x = core.x - 45; x <= core.x + 45; x++) for (int y = core.y - 45; y <= core.y + 45; y++) {
            Tile t = world.tile(x, y);
            if (t != null && t.build != null && t.build.tile == t && t.block() instanceof Turret && t.team() == player.team()) out.add(t.build);
        }
        return out;
    }
    static boolean canAfford(Block b, CoreBlock.CoreBuild core, int mult) {
        for (ItemStack s : b.requirements) if (core.items.get(s.item) < s.amount * mult) return false;
        return true;
    }
    static final Item[] feedPref = {Items.graphite, Items.silicon, Items.copper, Items.lead, Items.scrap, Items.metaglass};
    static Item feedItem(Building b, CoreBlock.CoreBuild core) {
        if (!(b.block instanceof ItemTurret it)) return null;
        for (Item i : feedPref) { int need = (i == Items.graphite && b.block != Blocks.hail) ? 150 : 30; if (it.ammoTypes.containsKey(i) && core.items.get(i) >= need) return i; }
        return null;
    }
    static final IntSet badSites = new IntSet();
    static boolean flyersSeen = false; static int lastCore = 0;
    static String diff = System.getProperty("diff", "normal");
    static int reactTicks() { return diff.equals("easy") ? 240 : diff.equals("hard") ? 0 : 60; }
    static float idle = 0; static boolean scouted = false; public static String scoutReport = "none";
    static final Seq<BuildPlan> blueprint = new Seq<>();
    static String style = System.getProperty("style", "balanced");
    static Tile threatTile(Tile core) {
        Unit best = null; float bd = 1e9f;
        for (Unit e : Groups.unit) if (e.team != player.team() && !e.dead) { float d = e.dst(core.worldx(), core.worldy()); if (d < bd) { bd = d; best = e; } }
        if (best != null) return best.tileOn() != null ? best.tileOn() : core;
        Seq<Tile> sp = spawner.getSpawns();
        return sp.any() ? sp.first() : core;
    }
    static Tile turretSite(Tile core, Block blk) {
        Tile threat = threatTile(core);
        Seq<Building> ts = turrets(core);
        Tile best = null; float bd = 1e9f;
        for (int x = core.x - 14; x <= core.x + 14; x++) for (int y = core.y - 14; y <= core.y + 14; y++) {
            float dc = Agent.Mathf2.dst(x, y, core.x, core.y);
            if (dc < 5 || dc > 12) continue;
            Tile t = world.tile(x, y);
            if (t == null || badSites.contains(x * 10000 + y) || !Build.validPlace(blk, player.team(), x, y, 0)) continue;
            boolean close = false; for (Building b : ts) if (Agent.Mathf2.dst(b.tileX(), b.tileY(), x, y) < 3.5f) close = true;
            if (close) continue;
            float d = Agent.Mathf2.dst(x, y, threat.x, threat.y);
            if (d < bd) { bd = d; best = t; }
        }
        return best;
    }

    // ---------- goals ----------
    static class MineGoal implements Goal {
        Item item; int add; int start = -1; float t = 0;
        MineGoal(Item i, int add) { item = i; this.add = add; }
        public String name() { return "mine " + item.name + " +" + add; }
        public boolean step(float dt, CoreBlock.CoreBuild core, Unit u) {
            t += dt; if (start < 0) start = core.items.get(item);
            Tile ore = Agent.nearestOre(item, core.tile, 45);
            if (ore == null) { ev("no ore " + item.name); return true; }
            float range = u.type.mineRange * 0.8f;
            if (u.dst(ore.worldx(), ore.worldy()) > range) Agent.moveTo(ore.worldx(), ore.worldy(), range * 0.6f);
            else { u.vel.setZero(); Agent.mineTarget = ore; u.mineTile = ore; }
            if (core.items.get(item) >= start + add || t > 60 * 45) { Agent.mineTarget = null; u.mineTile = null; return true; }
            return false;
        }
    }
    static class BuildGoal implements Goal {
        Seq<BuildPlan> all; String label; float t = 0;
        BuildGoal(String label, Seq<BuildPlan> p) { this.label = label; all = p; }
        public String name() { return "build " + label; }
        public boolean step(float dt, CoreBlock.CoreBuild core, Unit u) {
            t += dt;
            Seq<BuildPlan> missing = all.select(p -> !Agent.built(p));
            if (missing.isEmpty()) { Agent.plans.clear(); return true; }
            BuildPlan f = missing.first();
            float wx = f.x * tilesize, wy = f.y * tilesize;
            if (u.dst(wx, wy) > 100) Agent.moveTo(wx, wy, 60); else u.vel.setZero();
            Agent.plans.clear(); for (BuildPlan p : missing) Agent.plans.addLast(p);
            if (t > 60 * 25) { ev("build timeout " + label + " missing=" + missing.size + " -> marking site(s) bad"); Agent.plans.clear(); for (BuildPlan p : missing) { badSites.add(p.x * 10000 + p.y); blueprint.removeAll(q -> q.x == p.x && q.y == p.y); } return true; }
            return false;
        }
    }
    static class FeedGoal implements Goal {
        Building tur; int phase = 0; float t = 0; int before;
        Item item; FeedGoal(Building b, Item i) { tur = b; item = i; }
        public String name() { return "feed turret (" + tur.tileX() + "," + tur.tileY() + ")"; }
        public boolean step(float dt, CoreBlock.CoreBuild core, Unit u) {
            t += dt;
            if (t > 60 * 30 || !tur.isValid()) { ev("feed timeout"); return true; }
            int ammo = ((Turret.TurretBuild) tur).totalAmmo;
            if (phase == 0) {
                before = ammo;
                if (u.dst(core.x, core.y) > 60) { Agent.moveTo(core.x, core.y, 40); return false; }
                u.vel.setZero();
                if (u.stack.amount == 0) Call.requestItem(player, core, item, 30);
                if (u.stack.amount > 0 && u.stack.item == item) phase = 1; else if (u.stack.amount > 0) Call.transferInventory(player, core);
                return false;
            }
            if (u.dst(tur.x, tur.y) > 20) { Agent.moveTo(tur.x, tur.y, 14); return false; }
            u.vel.setZero();
            if (u.stack.amount > 0) Call.transferInventory(player, tur);
            if (ammo > before) { ev("turret (" + tur.tileX() + "," + tur.tileY() + ") ammo " + before + " -> " + ammo); feeds++;
                if (u.stack.amount > 0 && u.stack.item == item) { Building nx = null; int best = 1 << 30; for (Building b : turrets(core.tile)) { if (b == tur || !(b instanceof Turret.TurretBuild tb2) || !tb2.block.consumesItem(item) && !tb2.acceptItem(tb2, item)) continue; int a = tb2.totalAmmo; if (a < 20 && a < best) { best = a; nx = b; } }
                    if (nx != null) { tur = nx; before = ((Turret.TurretBuild) nx).totalAmmo; t = 0; return false; } }
                return true; }
            if (u.stack.amount == 0 && t > 60 * 3) phase = 0;
            return false;
        }
    }

    static class ScoutGoal implements Goal {
        float t = 0; boolean back = false; Tile target;
        public String name() { return back ? "scout: returning" : "scout: enemy base"; }
        public boolean step(float dt, CoreBlock.CoreBuild core, Unit u) {
            t += dt;
            if (target == null) {
                Building ec = state.teams.closestEnemyCore(core.x, core.y, player.team());
                if (ec == null) { scoutReport = "no enemy core known"; ev("scout: " + scoutReport); scouted = true; return true; }
                target = ec.tile;
            }
            if (!back) {
                if (Agent.moveTo(target.worldx(), target.worldy(), 35 * tilesize) || t > 60 * 40) {
                    int blds = 0, tur = 0, drl = 0; ObjectIntMap<String> types = new ObjectIntMap<>();
                    for (Building b : Groups.build) if (b.team != player.team() && b.team != Team.derelict && b.dst(target.worldx(), target.worldy()) < 50 * tilesize) { blds++; if (b.block instanceof Turret) tur++; types.increment(b.block.name); }
                    int units = 0; for (Unit e : Groups.unit) if (e.team != player.team() && e.dst(u) < 60 * tilesize) units++;
                    StringBuilder sb = new StringBuilder("enemy core at (" + target.x + "," + target.y + ") dist=" + (int)(u.dst(core) / tilesize) + " tiles; seen buildings=" + blds + " turrets=" + tur + " units_near=" + units + " types:");
                    for (var e : types) sb.append(" ").append(e.key).append("x").append(e.value);
                    scoutReport = sb.toString(); ev("scout report: " + scoutReport); scouted = true; back = true; t = 0;
                }
                return false;
            }
            if (Agent.moveTo(core.x, core.y, 60) || t > 60 * 40) return true;
            return false;
        }
    }

    /** press touching the core (it dumps graphite straight into the core) + coal drill + conveyor into the press */
    static Seq<BuildPlan> graphitePlan(CoreBlock.CoreBuild core) {
        Tile ct = core.tile;
        Seq<Tile> coreTiles = ct.build.tile.getLinkedTilesAs(ct.block(), new Seq<>());
        ObjectSet<Tile> cs = new ObjectSet<>(); for (Tile t : coreTiles) cs.add(t);
        Seq<Tile> sites = new Seq<>();
        for (int x = ct.x - 8; x <= ct.x + 8; x++) for (int y = ct.y - 8; y <= ct.y + 8; y++) {
            Tile t = world.tile(x, y);
            if (t == null || !Build.validPlace(Blocks.graphitePress, player.team(), x, y, 0)) continue;
            boolean touch = false;
            for (Tile q : t.getLinkedTilesAs(Blocks.graphitePress, new Seq<>())) for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) { Tile n = world.tile(q.x + d[0], q.y + d[1]); if (n != null && cs.contains(n)) touch = true; }
            if (touch) sites.add(t);
        }
        Seq<BuildPlan> best = null; int bs = 1 << 30;
        // try coal drill spots, nearest first, each against press sites
        Seq<Tile> drills = new Seq<>();
        for (int x = ct.x - 30; x <= ct.x + 30; x++) for (int y = ct.y - 30; y <= ct.y + 30; y++) {
            Tile t = world.tile(x, y); if (t == null || !Build.validPlace(Blocks.mechanicalDrill, player.team(), x, y, 0)) continue;
            int n = 0; for (Tile q : t.getLinkedTilesAs(Blocks.mechanicalDrill, new Seq<>())) if (q.drop() == Items.coal && q.block() == Blocks.air) n++;
            if (n >= 3) drills.add(t);
        }
        drills.sort(t -> Agent.Mathf2.dst(t.x, t.y, ct.x, ct.y));
        int tries = 0;
        for (Tile d : drills) {
            if (tries++ > 6) break;
            for (Tile st : sites) {
                Seq<BuildPlan> path = Agent.conveyorPath(d, st.getLinkedTilesAs(Blocks.graphitePress, new Seq<>()));
                if (path != null && path.size < bs) {
                    boolean clash = false; // conveyor must not sit on the press footprint
                    for (BuildPlan q : path) for (Tile pt : st.getLinkedTilesAs(Blocks.graphitePress, new Seq<>())) if (pt.x == q.x && pt.y == q.y) clash = true;
                    if (clash) continue;
                    bs = path.size; best = new Seq<>(); best.add(new BuildPlan(st.x, st.y, 0, Blocks.graphitePress)); best.add(new BuildPlan(d.x, d.y, 0, Blocks.mechanicalDrill)); best.addAll(path);
                }
            }
        }
        return best;
    }

    /** raid: fly to the nearest enemy target (unit first, else building), shoot it, retreat when hurt */
    static class AttackGoal implements Goal {
        float t = 0; Healthc tgt; float lastHp = -1; String label = "";
        public String name() { return "attack " + label; }
        public boolean step(float dt, CoreBlock.CoreBuild core, Unit u) {
            t += dt;
            if (u.health < u.maxHealth * 0.45f || t > 60 * 50) { Agent.aim = null; ev("attack end: unit hp=" + (int)u.health + " target=" + label + " lastHp=" + (int)lastHp); return true; }
            if (tgt == null || !tgt.isValid()) {
                Teamc best = null; float bd = 1e9f;
                for (Unit e : Groups.unit) if (e.team != player.team() && e.team != Team.derelict && !e.dead && e.dst(u) < bd) { bd = e.dst(u); best = e; }
                Building eb = state.teams.closestEnemyCore(core.x, core.y, player.team());
                if (best == null && eb != null) best = eb;
                if (best == null) { Agent.aim = null; return true; }
                tgt = (Healthc) best; label = best instanceof Unit uu ? uu.type.name : ((Building) best).block.name;
            }
            float tx = ((Posc) tgt).getX(), ty = ((Posc) tgt).getY();
            float range = u.type.maxRange * 0.7f;
            if (u.dst(tx, ty) > range) { Agent.moveTo(tx, ty, range * 0.8f); Agent.aim = null; }
            else { u.vel.setZero(); Agent.aim = new arc.math.geom.Vec2(tx, ty); lastHp = tgt.health(); }
            return false;
        }
    }

    // ---------- decision ----------
    static Goal drillGoal(Tile ct, int cu) {
        Tile spot = Agent.bestDrillSpot(Items.copper, ct, 30);
        if (spot == null) return null;
        Seq<BuildPlan> path = Agent.conveyorPath(spot, ct);
        if (path == null || cu < 12 + path.size) return null;
        Seq<BuildPlan> p = new Seq<>(); p.add(new BuildPlan(spot.x, spot.y, 0, Blocks.mechanicalDrill)); p.addAll(path);
        drillsBuilt++; return new BuildGoal("drill@" + spot.x + "," + spot.y + "+" + path.size + "conv", p);
    }
    static Goal decide(CoreBlock.CoreBuild core, Unit u) {
        Tile ct = core.tile;
        int cu = core.items.get(Items.copper), pb = core.items.get(Items.lead);
        int drills = count(Blocks.mechanicalDrill, ct);
        Seq<Building> ts = turrets(ct);
        boolean threat = false; int fly = 0;
        for (Unit e : Groups.unit) if (e.team != player.team() && !e.dead && e.dst(core) < 60 * tilesize) { threat = true; if (e.isFlying()) { fly++; flyersSeen = true; } }
        // 1. keep turrets fed
        for (Building b : ts) {
            if (!(b instanceof Turret.TurretBuild tb) || tb.totalAmmo >= (threat ? 20 : 27)) continue;
            Item fi = feedItem(b, core);
            if (fi != null) return new FeedGoal(b, fi);
        }
        // 2. rebuild destroyed defences
        Seq<BuildPlan> lost = blueprint.select(p -> !Agent.built(p));
        if (lost.any() && cu >= 20) { ev("rebuilding " + lost.size + " lost blocks"); return new BuildGoal("rebuild x" + lost.size, lost.copy()); }
        // 3. targets by style
        int duoWant = style.equals("turtle") ? 6 : style.equals("rush") ? 2 : 4;
        duoWant += Math.min(2, (int)(total / 60 / 150));
        duoWant += Math.min(10, cu / 500);
        int scatterWant = (flyersSeen || style.equals("turtle")) ? (style.equals("turtle") ? 2 : 1) : 0;
        int wallWant = (style.equals("turtle") ? 12 : style.equals("rush") ? 0 : 6) + Math.min(18, cu / 400);
        int duos = 0, scat = 0; for (Building b : ts) { if (b.block == Blocks.duo) duos++; if (b.block == Blocks.scatter) scat++; }
        if (!threat && drills < 2 && ts.size >= 2 && cu >= 32) { Goal g = drillGoal(ct, cu); if (g != null) return g; }
        if (scat < scatterWant && canAfford(Blocks.scatter, core, 1)) {
            Tile s2 = turretSite(ct, Blocks.scatter);
            if (s2 != null) { Seq<BuildPlan> p = new Seq<>(); BuildPlan bp = new BuildPlan(s2.x, s2.y, 0, Blocks.scatter); p.add(bp); blueprint.add(bp); return new BuildGoal("scatter@" + s2.x + "," + s2.y, p); }
        }
        if (duos < Math.max(duoWant - (threat ? 0 : 1), 2) && canAfford(Blocks.duo, core, 1)) {
            Tile s2 = turretSite(ct, Blocks.duo);
            if (s2 != null) { Seq<BuildPlan> p = new Seq<>(); BuildPlan bp = new BuildPlan(s2.x, s2.y, 0, Blocks.duo); p.add(bp); blueprint.add(bp); return new BuildGoal("duo@" + s2.x + "," + s2.y, p); }
        }
        if (drills < (total > 60 * 120 ? 6 : 4) && cu >= 12 + 20 + (threat ? 0 : 25 * ts.size / 2)) {
            Tile spot = Agent.bestDrillSpot(Items.copper, ct, 30);
            if (spot != null) {
                Seq<BuildPlan> path = Agent.conveyorPath(spot, ct);
                if (path != null && cu >= 12 + path.size) {
                    Seq<BuildPlan> p = new Seq<>(); p.add(new BuildPlan(spot.x, spot.y, 0, Blocks.mechanicalDrill)); p.addAll(path);
                    drillsBuilt++; return new BuildGoal("drill@" + spot.x + "," + spot.y + "+" + path.size + "conv", p);
                }
            }
        }
        // walls in front of turrets (toward threat)
        int reserve = 25 * ts.size;
        int gr = core.items.get(Items.graphite);
        if (!threat && drills >= 2 && count(Blocks.graphitePress, ct) < 1 && cu >= 75 + 12 + 25 + reserve && pb >= 30 + 10) {
            Seq<BuildPlan> gp = graphitePlan(core);
            if (gp != null) { int cost = 75 + 12 + gp.size - 2; if (cu >= cost + reserve) { ev("graphite plan: press + coal drill + " + (gp.size - 2) + " conveyors"); return new BuildGoal("graphite chain", gp); } }
        }
        int hails = 0; for (Building b : ts) if (b.block == Blocks.hail) hails++;
        if (hails < (style.equals("rush") ? 0 : 2) && gr >= 30 && canAfford(Blocks.hail, core, 1) && cu >= 60) {
            Tile s2 = turretSite(ct, Blocks.hail);
            if (s2 != null) { Seq<BuildPlan> p = new Seq<>(); BuildPlan bp = new BuildPlan(s2.x, s2.y, 0, Blocks.hail); p.add(bp); blueprint.add(bp); return new BuildGoal("hail@" + s2.x + "," + s2.y, p); }
        }
        int walls = 0; for (BuildPlan p : blueprint) if (p.block == Blocks.copperWall) walls++;
        if (walls < wallWant && cu >= 120 + reserve && ts.any()) {
            Tile threatT = threatTile(ct); Seq<BuildPlan> p = new Seq<>();
            for (Building b : ts) {
                float dx = threatT.x - b.tileX(), dy = threatT.y - b.tileY(); float l = (float)Math.hypot(dx, dy); if (l < 1) continue;
                int wx = Math.round(b.tileX() + dx / l * 3), wy = Math.round(b.tileY() + dy / l * 3);
                for (int k = -1; k <= 1; k++) {
                    int px = wx + (Math.abs(dx) > Math.abs(dy) ? 0 : k), py = wy + (Math.abs(dx) > Math.abs(dy) ? k : 0);
                    if (Build.validPlace(Blocks.copperWall, player.team(), px, py, 0)) { BuildPlan bp = new BuildPlan(px, py, 0, Blocks.copperWall); boolean dup = false; for (BuildPlan q : blueprint) if (q.x == px && q.y == py) dup = true; if (!dup) { p.add(bp); } }
                }
                if (p.size >= 3) break;
            }
            if (p.any()) { blueprint.addAll(p); return new BuildGoal("walls x" + p.size, p); }
        }
        if (scouted && !threat && u.health > u.maxHealth * 0.8f && !style.equals("turtle") && duos >= (style.equals("rush") ? 2 : 4) && total > 60 * (style.equals("rush") ? 120 : 240) && System.getProperty("raid") != null) return new AttackGoal();
        if (!scouted && !threat && !style.equals("turtle") && duos >= 2 && cu > 60) return new ScoutGoal();
        if (cu < 400) return new MineGoal(Items.copper, threat ? 40 : 120);
        if (pb < 80 && (scatterWant > 0 || style.equals("turtle"))) return new MineGoal(Items.lead, 40);
        if (pb < 40) return new MineGoal(Items.lead, 30);
        return new MineGoal(Items.copper, 60);
    }

    static boolean streamStarted = false;
    public static void update(float dt) {
        if (!streamStarted && System.getProperty("stream") != null) { streamStarted = true; Stream.start(Integer.parseInt(System.getProperty("stream"))); }
        Unit u = player.unit(); CoreBlock.CoreBuild core = player.core();
        if (u == null || u.dead || core == null || !state.isGame()) return;
        total += dt; sinceLog += dt;
        int cpos = core.tileX() * 10000 + core.tileY();
        if (lastCore != 0 && lastCore != cpos) { ev("core changed (team switch): resetting blueprint and goals"); blueprint.clear(); goal = null; scouted = false; Agent.plans.clear(); Agent.aim = null; }
        lastCore = cpos;
        if (goal == null && idle < reactTicks()) { idle += dt; return; }
        if (goal == null) { idle = 0; goal = decide(core, u); ev("goal: " + goal.name() + " | copper=" + core.items.get(Items.copper) + " enemies=" + state.enemies); }
        else if (goal.step(dt, core, u)) { goal = null; Agent.plans.clear(); }
        else {
            // preempt economy goals when enemies appear and turrets are missing/unfed
            if (state.enemies > 0 && !(goal instanceof FeedGoal)) {
                int lim = (goal instanceof BuildGoal) ? 4 : 10;
                for (Building b : turrets(core.tile)) if (((Turret.TurretBuild) b).totalAmmo < lim && feedItem(b, core) != null) { goal = null; Agent.mineTarget = null; u.mineTile = null; break; }
            }
        }
        if (sinceLog > 60 * 15) { sinceLog = 0; Log.info("BRAIN-SUMMARY t=@s style=@ diff=@ goal=@ scout=@\n@", (int)(total / 60), style, diff, goal == null ? "-" : goal.name(), scoutReport, Agent.summary()); }
    }
}
