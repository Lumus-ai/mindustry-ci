import arc.struct.*;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.storage.CoreBlock;
import static mindustry.Vars.*;

/**
 * Sustain & repair module (v4). Default OFF: -Dsustain=1 enables.
 * Four jobs, all bounded so baseline defense and turret feeding always win:
 *  1. power grid basics: up to 2 combustion generators, each with its own coal drill + short
 *     conveyor (solar is NOT the bootstrap - it needs silicon, which needs power).
 *  2. menders: one per turret cluster (max 2) + one near the core, placed only once a power
 *     source exists; each mender grows a power-node chain back to the grid.
 *  3. rebuild priority + escalation: prioritize() sorts Brain's lost-block rebuild queue
 *     (power > turrets > menders > walls > rest); tick() watches blueprint blocks and after the
 *     2nd loss at the same site escalates: walls in front of a churned turret site, one extra
 *     outer layer for a churned wall site (weakness #3: rebuild churn instead of thickening).
 *  4. wall layers: extra copper-wall rows at distance 4 and 5 in front of turrets that already
 *     have an inner row (mace flame pierces ONE wall layer [V]); added to Brain.blueprint so
 *     Brain builds, tracks and rebuilds them like its own walls.
 * Hooks (patches/Brain.sustain.patch): Sustain.decide before AdvancedEconomy.decide,
 * Sustain.tick in Brain.update, Sustain.prioritize in the rebuild branch, status in summary.
 */
public final class Sustain {
    public static final boolean enabled = System.getProperty("sustain", "0").equals("1");

    static final Seq<BuildPlan> assets = new Seq<>();          // module-owned blocks (power, menders)
    static CoreBlock.CoreBuild trackedCore;
    static float clock, nextPlan;
    static final IntSet badSites = new IntSet();
    static final IntSet builtPrev = new IntSet();              // blueprint positions seen built last check
    static final IntIntMap losses = new IntIntMap();           // position -> times lost after being built
    static final IntSet escalated = new IntSet();              // positions already escalated
    static int escalPlans = 0, repairsDone = 0, leadLines = 0;
    static final int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
    static final int MAX_ESCAL = 6;

    static int key(int x, int y) { return x * 10000 + y; }

    public static String status() {
        if (!enabled) return "off";
        int up = 0; for (BuildPlan p : assets) if (Agent.built(p)) up++;
        return "assets=" + up + "/" + assets.size + " repairs=" + repairsDone + " escal=" + escalPlans + " losses=" + losses.size;
    }

    // ---------------- tick: loss tracking + escalation ----------------
    public static void tick(float dt) {
        if (!enabled) return;
        clock += dt;
        CoreBlock.CoreBuild core = player.core();
        if (core != trackedCore) { trackedCore = core; assets.clear(); builtPrev.clear(); losses.clear(); escalated.clear(); escalPlans = 0; leadLines = 0; }
        if (core == null || clock % 60 >= 1f) return; // ~1 Hz
        if (escalPlans >= MAX_ESCAL) return;
        for (BuildPlan p : Brain.blueprint) {
            int pos = key(p.x, p.y);
            if (Agent.built(p)) { builtPrev.add(pos); continue; }
            if (!builtPrev.contains(pos)) continue; // never stood, not a loss
            builtPrev.remove(pos);
            int n = losses.get(pos, 0) + 1;
            losses.put(pos, n);
            if (n >= 2 && !escalated.contains(pos)) escalate(core, p);
        }
    }

    /** 2nd loss at the same site: change the plan instead of re-placing the identical thin line. */
    static void escalate(CoreBlock.CoreBuild core, BuildPlan p) {
        Tile threat = Brain.threatTile(core.tile);
        Seq<BuildPlan> add = new Seq<>();
        if (p.block instanceof Turret) {
            float ddx = threat.x - p.x, ddy = threat.y - p.y, l = (float)Math.hypot(ddx, ddy);
            if (l < 1) return;
            int wx = Math.round(p.x + ddx / l * 3), wy = Math.round(p.y + ddy / l * 3);
            for (int k = -1; k <= 1; k++) {
                int px = wx + (Math.abs(ddx) > Math.abs(ddy) ? 0 : k), py = wy + (Math.abs(ddx) > Math.abs(ddy) ? k : 0);
                tryWall(add, px, py);
            }
        } else if (p.block == Blocks.copperWall || p.block == Blocks.copperWallLarge) {
            float ddx = threat.x - p.x, ddy = threat.y - p.y, l = (float)Math.hypot(ddx, ddy);
            if (l < 1) return;
            tryWall(add, p.x + Math.round(ddx / l), p.y + Math.round(ddy / l));
        }
        if (add.any()) {
            escalated.add(key(p.x, p.y));
            escalPlans += add.size;
            Brain.blueprint.addAll(add);
            Brain.ev("sustain escalate: " + p.block.name + "@" + p.x + "," + p.y + " lost " + losses.get(key(p.x, p.y), 0) + "x -> +" + add.size + " wall layer");
        }
    }

    static void tryWall(Seq<BuildPlan> add, int px, int py) {
        Tile t = world.tile(px, py);
        if (t == null || !Build.validPlace(Blocks.copperWall, player.team(), px, py, 0)) return;
        for (BuildPlan q : Brain.blueprint) if (q.x == px && q.y == py) return;
        for (BuildPlan q : add) if (q.x == px && q.y == py) return;
        if (add.size + escalPlans < MAX_ESCAL) add.add(new BuildPlan(px, py, 0, Blocks.copperWall));
    }

    // ---------------- rebuild priority ----------------
    static int prio(Block b) {
        if (b == Blocks.combustionGenerator) return 0;
        if (b == Blocks.powerNode) return 1;
        if (b instanceof Turret) return 2;
        if (b == Blocks.mender) return 3;
        if (b == Blocks.copperWall || b == Blocks.copperWallLarge) return 4;
        return 5;
    }

    /** Sort Brain's lost-block rebuild queue: keep the grid alive first, DPS next, walls last. */
    public static Seq<BuildPlan> prioritize(Seq<BuildPlan> lost) {
        Seq<BuildPlan> out = lost.copy();
        out.sort(p -> (float)prio(p.block));
        return out;
    }

    // ---------------- decide ----------------
    public static Brain.Goal decide(CoreBlock.CoreBuild core, Unit u, boolean threat) {
        if (!enabled) return null;
        if (core != trackedCore) { trackedCore = core; assets.clear(); builtPrev.clear(); losses.clear(); escalated.clear(); escalPlans = 0; leadLines = 0; }
        Tile ct = core.tile;
        int cu = core.items.get(Items.copper), pb = core.items.get(Items.lead);
        Seq<Building> ts = Brain.turrets(ct);
        if (clock < nextPlan) return null;

        // 1. repair own lost assets (power chain, menders) - even under threat, with reserves
        for (BuildPlan p : assets) {
            if (!Agent.built(p) && Build.validPlace(p.block, player.team(), p.x, p.y, p.rotation) && !badSites.contains(key(p.x, p.y))) {
                Seq<BuildPlan> one = Seq.with(p);
                if (afford(one, core)) return goal("repair " + p.block.name, one);
            }
        }

        // 2a. lead line: baseline Brain only drills copper; gens/menders need lead income
        int drills = Brain.count(Blocks.mechanicalDrill, ct);
        int duos = 0; for (Building b : ts) if (b.block == Blocks.duo) duos++;
        if (drills >= 2 && duos >= 4 && core.health >= core.maxHealth * 0.9f && pb < 130 && leadDrills(ct) == 0 && leadLines < 1 && cu >= 300 && (!threat || cu >= 400)) {
            Tile spot = Agent.bestDrillSpot(Items.lead, ct, 30);
            if (spot != null) {
                Seq<BuildPlan> path = Agent.conveyorPath(spot, ct);
                if (path != null) {
                    Seq<BuildPlan> p = new Seq<>(); p.add(new BuildPlan(spot.x, spot.y, 0, Blocks.mechanicalDrill)); p.addAll(path);
                    if (afford(p, core)) return goal("lead line", p);
                }
            }
        }

        // 2. power bootstrap: up to 2 combustion generators (human checkpoint min 4-6)
        int gens = Brain.count(Blocks.combustionGenerator, ct);
        if (gens < 2 && (gens == 0 || Brain.count(Blocks.mender, ct) > 0)
                && drills >= 2 && duos >= 4 && core.health >= core.maxHealth * 0.9f && Brain.total >= 60 * 120
                && cu >= 300 && pb >= 70 && (!threat || cu >= 400)) {
            Seq<BuildPlan> p = generatorPlan(core, gens == 0 ? 2 : 1);
            if (p != null && afford(p, core)) return goal("coal power x" + countBlocks(p, Blocks.combustionGenerator), p);
        }

        // 3. menders near turret clusters and the core (need a live power source)
        Building power = powerSource(core);
        if (power != null && cu >= 60 && pb >= 40 && (!threat || cu >= 120)) {
            int have = Brain.count(Blocks.mender, ct);
            // cluster menders, max 2
            Seq<Seq<Building>> clusters = clusters(ts);
            int placed = 0;
            for (Seq<Building> cl : clusters) {
                if (placed >= 2) break;
                Tile c = centroid(cl);
                if (covered(c, 6)) continue;
                if (have + placed >= 3) break;
                Seq<BuildPlan> p = menderPlan(core, c);
                if (p != null && affordLight(p, core)) return goal("mender cluster@" + c.x + "," + c.y, p);
                placed++;
            }
            // core mender
            if (have < 3 && !covered(core.tile, 8)) {
                Seq<BuildPlan> p = menderPlan(core, core.tile);
                if (p != null && affordLight(p, core)) return goal("mender core", p);
            }
        }

        // 4. wall layers: thicken lines that already exist (between waves only)
        if (!threat && core.health >= core.maxHealth * 0.9f && cu >= 260 + 25 * ts.size && ts.any()) {
            Seq<BuildPlan> p = wallLayers(core, ts);
            if (p.any()) {
                Brain.blueprint.addAll(p); // Brain builds, tracks and rebuilds them
                Brain.ev("sustain wall layers +" + p.size);
                return new Brain.BuildGoal("wall layers x" + p.size, p);
            }
        }
        return null;
    }

    /** own mechanical drills sitting on lead ore */
    static int leadDrills(Tile ct) {
        int n = 0;
        for (int x = ct.x - 45; x <= ct.x + 45; x++) for (int y = ct.y - 45; y <= ct.y + 45; y++) {
            Tile t = world.tile(x, y);
            if (t == null || t.build == null || t.build.tile != t || t.block() != Blocks.mechanicalDrill || t.team() != player.team()) continue;
            for (Tile q : t.getLinkedTilesAs(Blocks.mechanicalDrill, new Seq<>())) if (q.drop() == Items.lead) { n++; break; }
        }
        return n;
    }

    static int countBlocks(Seq<BuildPlan> plans, Block b) {
        int n = 0; for (BuildPlan p : plans) if (p.block == b) n++;
        return n;
    }

    // ---------------- power ----------------
    static Building powerSource(CoreBlock.CoreBuild core) {
        for (Building b : Groups.build)
            if (b.team == player.team() && (b.block == Blocks.combustionGenerator || b.block == Blocks.solarPanel || b.block == Blocks.steamGenerator) && b.dst(core) < 45 * tilesize) return b;
        return null;
    }

    /** up to `want` generators, each next to its own coal drill, >=6 tiles from core (they explode) */
    static Seq<BuildPlan> generatorPlan(CoreBlock.CoreBuild core, int want) {
        Seq<Tile> coals = drillSites(core.tile, Items.coal);
        Seq<BuildPlan> out = null;
        int planned = 0;
        for (Tile coal : coals) {
            if (planned >= want) break;
            boolean done = false;
            for (int x = coal.x - 3; x <= coal.x + 3 && !done; x++) for (int y = coal.y - 3; y <= coal.y + 3 && !done; y++) {
                Tile t = world.tile(x, y);
                if (t == null || t.dst(core.tile) < 6 * tilesize || badSites.contains(key(x, y))) continue;
                if (!Build.validPlace(Blocks.combustionGenerator, player.team(), x, y, 0)) continue;
                Seq<Tile> foot = t.getLinkedTilesAs(Blocks.combustionGenerator, new Seq<>());
                Seq<BuildPlan> conv = Agent.conveyorPath(coal, foot);
                if (conv == null) continue;
                boolean clash = false;
                for (BuildPlan q : conv) for (Tile pt : foot) if (pt.x == q.x && pt.y == q.y) clash = true;
                if (out != null) {
                    for (BuildPlan q : conv) for (BuildPlan o : out) if (o.x == q.x && o.y == q.y) clash = true;
                    for (Tile pt : foot) for (BuildPlan o : out) if (o.x == pt.x && o.y == pt.y) clash = true;
                }
                if (clash) continue;
                if (out == null) out = new Seq<>();
                out.add(new BuildPlan(x, y, 0, Blocks.combustionGenerator));
                out.add(new BuildPlan(coal.x, coal.y, 0, Blocks.mechanicalDrill));
                out.addAll(conv);
                planned++;
                done = true;
            }
        }
        return out;
    }

    /** coal drill spots: >=3 pure coal tiles, no mixed ore, nearest first */
    static Seq<Tile> drillSites(Tile core, Item item) {
        Seq<Tile> out = new Seq<>();
        for (int x = core.x - 30; x <= core.x + 30; x++) for (int y = core.y - 30; y <= core.y + 30; y++) {
            Tile t = world.tile(x, y);
            if (t == null || !Build.validPlace(Blocks.mechanicalDrill, player.team(), x, y, 0)) continue;
            int n = 0; boolean mixed = false;
            for (Tile q : t.getLinkedTilesAs(Blocks.mechanicalDrill, new Seq<>())) {
                if (q.block() != Blocks.air) { mixed = true; break; }
                if (q.drop() == item) n++; else if (q.drop() != null) mixed = true;
            }
            if (n >= 3 && !mixed) out.add(t);
        }
        out.sort(t -> t.dst(core));
        return out;
    }

    // ---------------- menders ----------------
    static Seq<Seq<Building>> clusters(Seq<Building> ts) {
        Seq<Building> rest = ts.copy();
        Seq<Seq<Building>> out = new Seq<>();
        while (rest.any()) {
            Seq<Building> cl = Seq.with(rest.pop());
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Building b : rest.copy()) if (nearAny(b, cl, 8 * tilesize)) { cl.add(b); rest.remove(b, true); grew = true; }
            }
            out.add(cl);
        }
        out.sort(c -> -c.size);
        return out;
    }
    static boolean nearAny(Building b, Seq<Building> cl, float d) {
        for (Building o : cl) if (b.dst(o) < d) return true;
        return false;
    }
    static Tile centroid(Seq<Building> cl) {
        float sx = 0, sy = 0;
        for (Building b : cl) { sx += b.tileX(); sy += b.tileY(); }
        return world.tile(Math.round(sx / cl.size), Math.round(sy / cl.size));
    }
    /** own mender already covering this tile? */
    static boolean covered(Tile t, int rangeTiles) {
        if (t == null) return true;
        for (Building b : Groups.build)
            if (b.team == player.team() && b.block == Blocks.mender && b.dst(t.worldx(), t.worldy()) < rangeTiles * tilesize) return true;
        return false;
    }

    static Seq<BuildPlan> menderPlan(CoreBlock.CoreBuild core, Tile near) {
        Seq<Tile> sites = new Seq<>();
        for (int x = near.x - 6; x <= near.x + 6; x++) for (int y = near.y - 6; y <= near.y + 6; y++) {
            Tile t = world.tile(x, y);
            if (t == null || badSites.contains(key(x, y)) || !Build.validPlace(Blocks.mender, player.team(), x, y, 0)) continue;
            if (covered(t, 5)) continue;
            sites.add(t);
        }
        sites.sort(t -> t.dst(near));
        for (Tile t : sites) {
            Planner p = new Planner(core.tile);
            if (p.add(t, Blocks.mender) && p.connect(t)) return p.plans;
        }
        return null;
    }

    // ---------------- wall layers ----------------
    static Seq<BuildPlan> wallLayers(CoreBlock.CoreBuild core, Seq<Building> ts) {
        Seq<BuildPlan> out = new Seq<>();
        Tile threat = Brain.threatTile(core.tile);
        int done = 0;
        for (Building b : ts) {
            if (done++ >= 4 || out.size >= 9) break;
            float ddx = threat.x - b.tileX(), ddy = threat.y - b.tileY(), l = (float)Math.hypot(ddx, ddy);
            if (l < 1) continue;
            for (int d = 4; d <= 4; d++) {
                int wx = Math.round(b.tileX() + ddx / l * d), wy = Math.round(b.tileY() + ddy / l * d);
                int ix = Math.round(b.tileX() + ddx / l * (d - 1)), iy = Math.round(b.tileY() + ddy / l * (d - 1));
                if (!ownWallNear(ix, iy)) continue; // only layer where an inner row stands
                for (int k = -1; k <= 1; k++) {
                    int px = wx + (Math.abs(ddx) > Math.abs(ddy) ? 0 : k), py = wy + (Math.abs(ddx) > Math.abs(ddy) ? k : 0);
                    Tile t = world.tile(px, py);
                    if (t == null || !Build.validPlace(Blocks.copperWall, player.team(), px, py, 0)) continue;
                    boolean dup = false;
                    for (BuildPlan q : Brain.blueprint) if (q.x == px && q.y == py) dup = true;
                    for (BuildPlan q : out) if (q.x == px && q.y == py) dup = true;
                    if (!dup) out.add(new BuildPlan(px, py, 0, Blocks.copperWall));
                    if (out.size >= 9) break;
                }
            }
        }
        return out;
    }
    static boolean ownWallNear(int x, int y) {
        for (int ax = x - 1; ax <= x + 1; ax++) for (int ay = y - 1; ay <= y + 1; ay++) {
            Building b = world.build(ax, ay);
            if (b != null && b.team == player.team() && (b.block == Blocks.copperWall || b.block == Blocks.copperWallLarge)) return true;
        }
        return false;
    }

    // ---------------- shared plumbing ----------------
    static boolean afford(Seq<BuildPlan> plans, CoreBlock.CoreBuild core) {
        ObjectIntMap<Item> costs = new ObjectIntMap<>();
        for (BuildPlan p : plans) if (!Agent.built(p)) for (ItemStack s : p.block.requirements) costs.increment(s.item, Math.round(s.amount * state.rules.buildCostMultiplier));
        for (var e : costs) {
            int reserve = e.key == Items.copper ? Math.max(160, Brain.turrets(core.tile).size * 30) : e.key == Items.lead ? 50 : 0;
            if (core.items.get(e.key) < e.value + reserve) return false;
        }
        return true;
    }

    /** light reserves for cheap, high-value mender plans: they save more than they cost */
    static boolean affordLight(Seq<BuildPlan> plans, CoreBlock.CoreBuild core) {
        ObjectIntMap<Item> costs = new ObjectIntMap<>();
        for (BuildPlan p : plans) if (!Agent.built(p)) for (ItemStack s : p.block.requirements) costs.increment(s.item, Math.round(s.amount * state.rules.buildCostMultiplier));
        for (var e : costs) {
            int reserve = e.key == Items.copper ? 60 : e.key == Items.lead ? 30 : 0;
            if (core.items.get(e.key) < e.value + reserve) return false;
        }
        return true;
    }

    static Brain.Goal goal(String name, Seq<BuildPlan> p) {
        if (name.startsWith("lead line")) leadLines++;
        for (BuildPlan q : p) if (!assets.contains(a -> a.x == q.x && a.y == q.y)) assets.add(q);
        nextPlan = clock + 120; // replan at most every 2s
        Brain.ev("sustain plan: " + name + " blocks=" + p.size);
        return new SustainBuild(name, p);
    }

    /** Bounded build: stall 15s / total 90s -> drop missing from assets, blacklist site, never churn. */
    static class SustainBuild implements Brain.Goal {
        final String name; final Seq<BuildPlan> plans; float time, stall; int lastMissing = -1;
        SustainBuild(String n, Seq<BuildPlan> p) { name = n; plans = p.copy(); }
        public String name() { return "sustain " + name; }
        public boolean step(float dt, CoreBlock.CoreBuild core, Unit unit) {
            time += dt; stall += dt;
            Seq<BuildPlan> missing = plans.select(p -> !Agent.built(p));
            if (missing.isEmpty()) { Agent.plans.clear(); repairsDone++; Brain.ev("sustain complete: " + name); return true; }
            if (lastMissing != missing.size) { lastMissing = missing.size; stall = 0; }
            if (stall > 60 * 15 || time > 60 * 90) {
                Brain.ev("sustain build stopped: " + name + " missing=" + missing.size);
                for (BuildPlan p : missing) { assets.remove(p, true); badSites.add(key(p.x, p.y)); }
                Agent.plans.clear();
                return true;
            }
            BuildPlan first = missing.first();
            Tile t = world.tile(first.x, first.y);
            if (t == null || (t.build != null && t.block() != Blocks.air && !(t.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild) && !Build.validPlace(first.block, player.team(), first.x, first.y, first.rotation))) {
                assets.removeAll(p -> !Agent.built(p));
                Agent.plans.clear();
                return true;
            }
            Agent.mineTarget = null; unit.mineTile = null;
            if (unit.dst(first.x * tilesize, first.y * tilesize) > 90) Agent.moveTo(first.x * tilesize, first.y * tilesize, 55); else unit.vel.setZero();
            Agent.plans.clear();
            for (BuildPlan p : missing) Agent.plans.addLast(p);
            return false;
        }
    }

    /** mender + power-node chain back to the grid (pattern proven in AdvancedEconomy.Planner). */
    static class Planner {
        final Tile core; final Seq<BuildPlan> plans = new Seq<>(); final IntSet occupied = new IntSet();
        Planner(Tile c) { core = c; }
        boolean add(Tile t, Block b) {
            if (t == null || !Build.validPlace(b, player.team(), t.x, t.y, 0)) return false;
            for (Tile q : t.getLinkedTilesAs(b, new Seq<>())) if (occupied.contains(q.pos()) || q.block() != Blocks.air) return false;
            for (Tile q : t.getLinkedTilesAs(b, new Seq<>())) occupied.add(q.pos());
            plans.add(new BuildPlan(t.x, t.y, 0, b));
            return true;
        }
        boolean connect(Tile target) {
            Seq<Tile> anchors = new Seq<>();
            for (Building b : Groups.build) if (b.team == player.team() && b.power != null && b.dst(core.worldx(), core.worldy()) < 45 * tilesize) anchors.add(b.tile);
            for (BuildPlan p : plans) if (p.block.hasPower && (p.x != target.x || p.y != target.y)) anchors.add(world.tile(p.x, p.y));
            if (anchors.isEmpty()) return false;
            anchors.sort(t -> t.dst(target));
            Tile goalT = anchors.first(), current = target;
            for (int step = 0; step < 20; step++) {
                if (current.dst(goalT) <= tilesize * 1.01f) return true; // adjacent blocks conduct
                Tile best = null; float score = Float.MAX_VALUE;
                for (int x = current.x - 5; x <= current.x + 5; x++) for (int y = current.y - 5; y <= current.y + 5; y++) {
                    Tile t = world.tile(x, y);
                    if (t == null || t.dst(current) > 5 * tilesize || t.dst(goalT) >= current.dst(goalT) - tilesize * .25f || PowerNode.insulated(current, t)) continue;
                    if (occupied.contains(t.pos()) || t.block() != Blocks.air || !Build.validPlace(Blocks.powerNode, player.team(), x, y, 0)) continue;
                    float s = t.dst(goalT);
                    if (s < score) { score = s; best = t; }
                }
                if (best == null) return false;
                if (!add(best, Blocks.powerNode)) return false;
                if (best.dst(goalT) < 5 * tilesize && !PowerNode.insulated(best, goalT)) return true;
                current = best;
            }
            return false;
        }
    }
}
