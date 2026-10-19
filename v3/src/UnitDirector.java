import arc.math.geom.Vec2;
import arc.struct.*;
import arc.util.Log;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.blocks.power.SolarGenerator;
import mindustry.content.*;
import mindustry.entities.Units;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import mindustry.world.blocks.units.UnitFactory.UnitPlan;
import static mindustry.Vars.*;

/** Optional, resource-safe tier-one unit production. All effects go through normal client RPCs. */
public final class UnitDirector {
    static final boolean enabled = "1".equals(System.getProperty("units", "0"));
    static float timer;
    public static int orders, deliveries, configurations;
    static final int COPPER_RESERVE = 240, LEAD_RESERVE = 100, SILICON_RESERVE = 30;

    static Seq<UnitFactoryBuild> factories(CoreBlock.CoreBuild core) {
        Seq<UnitFactoryBuild> out = new Seq<>();
        for (Building b : Groups.build)
            if (b instanceof UnitFactoryBuild f && b.team == core.team && b.dst(core) < 55 * tilesize &&
                (b.block == Blocks.groundFactory || b.block == Blocks.airFactory)) out.add(f);
        return out;
    }
    // Passive power nodes are not present in Groups.build (update=false).
    static Seq<Building> nodes(CoreBlock.CoreBuild core) {
        Seq<Building> out = new Seq<>();
        for (int x = core.tileX()-35; x <= core.tileX()+35; x++)
            for (int y = core.tileY()-35; y <= core.tileY()+35; y++) {
                Tile t = world.tile(x,y);
                if (t != null && t.build != null && t.build.tile == t && t.team() == core.team &&
                    t.block() instanceof PowerNode) out.add(t.build);
            }
        return out;
    }
    static UnitType recipe(UnitFactoryBuild f) { return f.block == Blocks.airFactory ? UnitTypes.flare : UnitTypes.dagger; }
    static UnitPlan plan(UnitFactoryBuild f) {
        if (f.currentPlan >= 0 && f.currentPlan < ((UnitFactory)f.block).plans.size &&
            ((UnitFactory)f.block).plans.get(f.currentPlan).unit != recipe(f)) return null;
        for (UnitPlan p : ((UnitFactory)f.block).plans) if (p.unit == recipe(f)) return p;
        return null;
    }
    static boolean hostile(Unit e, CoreBlock.CoreBuild core) {
        return !e.dead && e.team != core.team && e.team != mindustry.game.Team.derelict;
    }

    /** Call from Brain.update; does not claim the builder or its build queue. */
    public static void update(float dt, CoreBlock.CoreBuild core) {
        if (!enabled) return;
        timer += dt;
        if (timer < 180) return;
        timer = 0;
        Vec2 rally = defensiveRally(core);
        for (UnitFactoryBuild f : factories(core)) {
            UnitPlan p = plan(f); if (p == null) continue;
            int index = ((UnitFactory)f.block).plans.indexOf(p, true);
            if (state.rules.bannedUnits.contains(p.unit)) continue;
            if (f.currentPlan != index) { Call.tileConfig(player, f, index); configurations++; }
            if (f.commandPos == null || f.commandPos.dst(rally) > tilesize * 3)
                Call.commandBuilding(player, new int[]{f.pos()}, rally.cpy());
            // Connect an existing nearby power node, never build speculative power infrastructure.
            if (f.power != null && f.power.status < 0.8f) for (Building b : nodes(core))
                if (b.team == core.team && b.block instanceof PowerNode node && b.power != null &&
                    !b.power.links.contains(f.pos()) && node.linkValid(b, f) &&
                    gridPowered(b, core)) {
                    Call.tileConfig(player, b, f.pos()); break;
                }
        }
        IntSeq ground = new IntSeq(), air = new IntSeq();
        Unit nearGround = null, nearAir = null;
        for (Unit e : Groups.unit) if (hostile(e, core) && e.dst(core) < tilesize * 55) {
            if (nearAir == null || e.dst(core) < nearAir.dst(core)) nearAir = e;
            if (!e.isFlying() && (nearGround == null || e.dst(core) < nearGround.dst(core))) nearGround = e;
        }
        for (Unit u : Groups.unit) if (u.team == core.team && !u.dead && !u.isPlayer()) {
            if (u.type == UnitTypes.dagger) ground.add(u.id);
            if (u.type == UnitTypes.flare) air.add(u.id);
        }
        command(ground, nearGround, false, rally, core);
        command(air, nearAir, true, rally, core);
    }
    // Graph rates are server-local, not a reliable client snapshot. Walk physical and laser links,
    // accepting an active generator or a satisfied active consumer as a liveness signal.
    // This is liveness, not a throughput guarantee; production can still brown out.
    static boolean gridPowered(Building start, CoreBlock.CoreBuild core) {
        Seq<Building> todo = Seq.with(start); IntSet seen = new IntSet();
        for (int i = 0; i < todo.size && i < 256; i++) {
            Building b = todo.get(i);
            if (!seen.add(b.pos()) || b.team != core.team || b.power == null) continue;
            if (b.enabled && b.block instanceof PowerGenerator &&
                (b.getPowerProduction() > 0.01f || b.block instanceof SolarGenerator ||
                (b.items != null && b.items.total() > 0))) return true;
            if (b.block.consumesPower && b.efficiency > 0.5f && b.power.status > 0.9f) return true;
            for (int k = 0; k < b.power.links.size; k++) {
                Building link = world.build(b.power.links.get(k)); if (link != null && !seen.contains(link.pos())) todo.add(link);
            }
            for (Tile edge : b.tile.getLinkedTilesAs(b.block, new Seq<>())) {
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    Building adj = world.build(edge.x+d[0], edge.y+d[1]);
                    if (adj != null && adj.power != null && !seen.contains(adj.pos())) todo.add(adj);
                }
            }
        }
        return false;
    }
    static Vec2 defensiveRally(CoreBlock.CoreBuild core) {
        Tile t = Brain.threatTile(core.tile);
        Vec2 d = new Vec2(t.worldx() - core.x, t.worldy() - core.y);
        if (d.len() < 1) d.set(1, 0);
        return d.setLength(6 * tilesize).add(core.x, core.y);
    }
    static void command(IntSeq ids, Unit enemy, boolean air, Vec2 rally, CoreBlock.CoreBuild core) {
        if (ids.isEmpty()) return;
        if (enemy != null) Call.commandUnits(player, ids.toArray(), null, enemy, null, false, true);
        else if ("1".equals(System.getProperty("unitPressure", "0")) && ids.size >= (air ? 8 : 6)) {
            Building target = state.teams.closestEnemyCore(core.x, core.y, core.team);
            if (target != null) Call.commandUnits(player, ids.toArray(), target, null, null, false, true);
            else Call.commandUnits(player, ids.toArray(), null, null, rally.cpy(), false, true);
        } else Call.commandUnits(player, ids.toArray(), null, null, rally.cpy(), false, true);
        orders++;
    }

    /** Called after baseline economy and defense goals, before scouting/mining. */
    public static Brain.Goal nextGoal(CoreBlock.CoreBuild core, Unit u, boolean threat) {
        if (!enabled) return null;
        Seq<UnitFactoryBuild> fs = factories(core);
        for (UnitFactoryBuild f : fs) {
            UnitPlan p = plan(f);
            if (p == null || state.rules.bannedUnits.contains(p.unit) || f.currentPlan < 0 || f.power == null || !Units.canCreate(core.team, p.unit) || f.payload != null) continue;
            for (ItemStack r : p.requirements) {
                int reserve = r.item == Items.silicon ? SILICON_RESERVE : r.item == Items.lead ? LEAD_RESERVE : COPPER_RESERVE;
                int target = Math.min(f.getMaximumAccepted(r.item), r.amount * 2);
                if (f.items.get(r.item) < r.amount && core.items.get(r.item) > reserve + r.amount)
                    return new SupplyGoal(f, r.item, Math.min(target - f.items.get(r.item), core.items.get(r.item) - reserve));
            }
        }
        if (threat || core.items.get(Items.copper) < COPPER_RESERVE ||
            core.items.get(Items.lead) < LEAD_RESERVE + 80 || core.items.get(Items.silicon) < 100 ||
            Brain.turrets(core.tile).size < 3) return null;
        // One of each, with flares first: they intercept flyers and avoid terrain traps.
        for (Block block : new Block[]{Blocks.airFactory, Blocks.groundFactory}) {
            if (state.rules.bannedBlocks.contains(block) ||
                (block == Blocks.airFactory ? state.rules.bannedUnits.contains(UnitTypes.flare) : state.rules.bannedUnits.contains(UnitTypes.dagger))) continue;
            if (fs.contains(f -> f.block == block)) continue;
            if (!afford(block, core)) continue;
            BuildPlan p = factorySite(core, block);
            if (p != null) {
                Log.info("UNITS: build @ at @,@", block.name, p.x, p.y);
                return new Brain.BuildGoal("unit " + block.name, Seq.with(p));
            }
        }
        return null;
    }
    static boolean afford(Block b, CoreBlock.CoreBuild c) {
        for (ItemStack r : b.requirements) {
            int reserve = r.item == Items.copper ? COPPER_RESERVE : r.item == Items.lead ? LEAD_RESERVE : SILICON_RESERVE;
            if (c.items.get(r.item) < r.amount + reserve) return false;
        }
        return true;
    }
    static BuildPlan factorySite(CoreBlock.CoreBuild core, Block block) {
        Tile threat = Brain.threatTile(core.tile);
        BuildPlan best = null; float score = Float.MAX_VALUE;
        Seq<Building> powerNodes = nodes(core);
        for (int x = core.tileX() - 18; x <= core.tileX() + 18; x++) for (int y = core.tileY() - 18; y <= core.tileY() + 18; y++) {
            if (Brain.badSites.contains(x * 10000 + y) || !Build.validPlace(block, core.team, x, y, 0)) continue;
            Tile t = world.tile(x, y); if (Agent.Mathf2.dst(t.x,t.y,core.tileX(),core.tileY()) < 6 || Agent.Mathf2.dst(t.x,t.y,core.tileX(),core.tileY()) > 18) continue;
            boolean empty = true;
            for (Tile footprint : t.getLinkedTilesAs(block, new Seq<>()))
                if (footprint.block() != Blocks.air || footprint.drop() != null) { empty = false; break; }
            if (!empty) continue; // validPlace permits replacing other factories: never do that.
            // Need a clear exit on the east side of the 3x3 factory.
            Tile exit = world.tile(x + 2, y);
            if (exit == null || exit.block() != Blocks.air || exit.solid() || exit.floor().isDeep()) continue;
            boolean power = false;
            for (Building b : powerNodes) if (b.team == core.team && b.block instanceof PowerNode node && b.power != null &&
                b.power.links.size < node.maxNodes && gridPowered(b, core) &&
                b.dst(x * tilesize, y * tilesize) < (node.laserRange - 1) * tilesize) { power = true; break; }
            if (!power) continue;
            float s = Agent.Mathf2.dst(t.x,t.y,core.tileX(),core.tileY()) - Agent.Mathf2.dst(t.x,t.y,threat.x,threat.y) * 0.15f;
            if (s < score) { score = s; best = new BuildPlan(x, y, 0, block); }
        }
        return best;
    }

    static final class SupplyGoal implements Brain.Goal {
        final UnitFactoryBuild factory; final Item item; final int amount;
        float ticks, rpc; int phase, before;
        SupplyGoal(UnitFactoryBuild f, Item i, int n) { factory = f; item = i; amount = n; }
        public String name() { return "factory supply " + item.name + "@" + factory.tileX() + "," + factory.tileY(); }
        public boolean step(float dt, CoreBlock.CoreBuild core, Unit u) {
            ticks += dt; rpc += dt;
            if (!factory.isValid() || ticks > 60 * 18) return true;
            Agent.mineTarget = null; u.mineTile = null;
            if (phase == 0) {
                if (u.dst(core) > 40) { Agent.moveTo(core.x, core.y, 32); return false; }
                u.vel.setZero();
                if (rpc < 30) return false; rpc = 0;
                if (u.stack.amount > 0 && u.stack.item != item) { Call.transferInventory(player, core); return false; }
                if (u.stack.amount == 0) { Call.requestItem(player, core, item, Math.min(amount, u.type.itemCapacity)); return false; }
                before = u.stack.amount; phase = 1;
            }
            if (u.dst(factory) > 20) { Agent.moveTo(factory.x, factory.y, 14); return false; }
            u.vel.setZero();
            if (u.stack.amount < before) { deliveries++; Log.info("UNITS: supplied @ to @", item.name, factory.block.name); return true; }
            if (!factory.acceptItem(factory, item)) return true;
            if (rpc >= 30) { rpc = 0; Call.transferInventory(player, factory); }
            return false;
        }
    }
}
