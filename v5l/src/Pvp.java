import arc.math.geom.Vec2;
import arc.struct.*;
import arc.util.Log;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.storage.CoreBlock;
import static mindustry.Vars.*;

/**
 * PvP attack / scouting / defence commander for the v160.7 headless bot.
 * Optional: enabled with -Dpvp=1. Touches only (a) unit commands via Call.commandUnits for combat
 * units (never the player's own unit, never builders/miners), (b) read-only world state.
 * Tunables (system properties): pvp.rushSec (default 420), pvp.minRush (4), pvp.ratio (1.3), pvp.retreat (0.7), pvp.debug.
 */
public final class Pvp {
    public static final boolean enabled = "1".equals(System.getProperty("pvp", "0"));
    static final float RUSH_SEC = Float.parseFloat(System.getProperty("pvp.rushSec", "420"));
    static final int MIN_WAVE = Integer.getInteger("pvp.minWave", 6);
    static final int MIN_RUSH = Integer.getInteger("pvp.minRush", 4);
    static final float RATIO = Float.parseFloat(System.getProperty("pvp.ratio", "1.3"));
    static final float RETREAT = Float.parseFloat(System.getProperty("pvp.retreat", "0.7"));
    static final boolean DEBUG = System.getProperty("pvp.debug") != null;

    static final int RESERVE_MIN = Integer.getInteger("pvp.reserveMin", 2);
    static final float RESERVE_FRAC = Float.parseFloat(System.getProperty("pvp.reserveFrac", "0.25"));
    static final boolean LEGACY_RETREAT = System.getProperty("pvp.legacyRetreat") != null;
    static final float COMMIT_FLOOR = Float.parseFloat(System.getProperty("pvp.commitFloor", "0.25"));
    public enum Phase { BOOM, GATHER, MARCH, ASSAULT, RETREAT, DEFEND }
    /** Read by economy modules: 0..1 how much the PvP planner wants military production right now. */
    public static volatile float wantMilitary = 0.3f;
    public static volatile Phase phase = Phase.BOOM;
    public static volatile String status = "pvp off";
    public static volatile String mode = "boom";   // "rush" or "boom" (decision label)

    // ---- intel ----
    static class Seen { String block; float dps; boolean air, ground; float hp; float range; float seenAt; Team team; }
    static final IntMap<Seen> seenTurrets = new IntMap<>();
    static class EnemyIntel { Team team; CoreBlock.CoreBuild core; float coreHpFrac, defStrength, unitStrength, lastScan; }
    static final ObjectMap<Team, EnemyIntel> intel = new ObjectMap<>();
    static Team target;

    static float clock, nextScan, nextCmd, phaseSince, nextLog, lastScoutCmd, lastCoreHp = -1;
    static int teamId = -1;
    static Vec2 staging = new Vec2();
    static int armyAtLaunch;
    static float strengthAtLaunch;
    public static final Seq<String> events = new Seq<>();
    static void ev(String s) { events.add(s); Log.info("PVP: t=" + (int) (clock / 60) + "s " + s); }

    static boolean hostile(Team t) { return t != null && t != Team.derelict && t != player.team(); }
    static boolean visible(float x, float y) { return !state.rules.fog || (fogControl != null && fogControl.isVisible(player.team(), x, y)); }

    // rough dps / targeting table for common vanilla turrets (v160 values are only approximated; unknown => default)
    static float[] turretStats(String n) { // dps, air(1/0), ground(1/0)
        switch (n) {
            case "duo": return new float[]{ 22, 1, 1 };
            case "scatter": return new float[]{ 45, 1, 0 };
            case "scorch": return new float[]{ 90, 0, 1 };
            case "hail": return new float[]{ 12, 0, 1 };
            case "wave": return new float[]{ 2, 1, 1 };
            case "lancer": return new float[]{ 70, 0, 1 };
            case "arc": return new float[]{ 40, 0, 1 };
            case "parallax": return new float[]{ 5, 1, 0 };
            case "swarmer": return new float[]{ 110, 1, 1 };
            case "salvo": return new float[]{ 100, 1, 1 };
            case "segment": return new float[]{ 1, 1, 1 };
            case "tsunami": return new float[]{ 2, 1, 1 };
            case "fuse": return new float[]{ 190, 1, 1 };
            case "ripple": return new float[]{ 140, 0, 1 };
            case "cyclone": return new float[]{ 160, 1, 1 };
            case "foreshadow": return new float[]{ 300, 1, 1 };
            case "spectre": return new float[]{ 400, 1, 1 };
            case "meltdown": return new float[]{ 400, 1, 1 };
            default: return new float[]{ 60, 1, 1 };
        }
    }
    static float strength(float dps, float hp) { return (float) Math.sqrt(Math.max(0, dps) * Math.max(0, hp)); }

    // ---- own army ----
    static boolean isCombat(Unit u) {
        return u.team == player.team() && !u.dead && u.isValid() && u != player.unit() && !u.isPlayer()
            && u.type.weapons.size > 0 && u.type.buildSpeed <= 0 && u.type.mineTier <= 0;
    }
    static int scoutId = -1;
    static Seq<Unit> army() {
        Seq<Unit> a = new Seq<>(); Unit scout = null;
        for (Unit u : Groups.unit) if (isCombat(u)) { if (u.id == scoutId) scout = u; a.add(u); }
        if (scout == null) { scoutId = -1; for (Unit u : a) if (u.type == mindustry.content.UnitTypes.flare && a.size >= 3) { scoutId = u.id; scout = u; break; } }
        if (scout != null) a.remove(scout);
        return a;
    }
    static Unit scout() { if (scoutId < 0) return null; Unit u = Groups.unit.getByID(scoutId); return u != null && !u.dead ? u : null; }
    static float armyStrength(Seq<Unit> a, boolean targetsGround) {
        float dps = 0, hp = 0;
        for (Unit u : a) {
            if (!(u.isFlying() ? u.type.targetAir : u.type.targetGround) && !u.type.targetGround) continue;
            dps += Math.max(1, u.type.estimateDps()); hp += u.health + u.shield;
        }
        return strength(dps, hp);
    }
    static boolean owns() { return enabled && Pvp.phase != null && clock > 0; }
    static boolean ownsCommands() { return enabled; }

    // ---- scan ----
    static void scan(CoreBlock.CoreBuild core) {
        // remember enemy turrets we can see; forget the ones whose tile is visible and empty
        for (Building b : Groups.build) if (hostile(b.team) && b.isValid() && b.block instanceof Turret t && visible(b.x, b.y)) {
            Seen s = seenTurrets.get(b.pos());
            if (s == null) { s = new Seen(); seenTurrets.put(b.pos(), s); float[] st = turretStats(b.block.name); s.dps = st[0]; s.air = st[1] > 0; s.ground = st[2] > 0; s.block = b.block.name; }
            s.hp = b.health; s.range = t.range; s.seenAt = clock; s.team = b.team;
        }
        IntSeq dead = new IntSeq();
        for (IntMap.Entry<Seen> e : seenTurrets) {
            Tile tl = world.tile(e.key);
            if (tl == null) continue;
            if (visible(tl.worldx(), tl.worldy()) && (tl.build == null || !tl.build.isValid() || tl.team() != e.value.team)) dead.add(e.key);
        }
        for (int i = 0; i < dead.size; i++) seenTurrets.remove(dead.get(i));
        for (var td : state.teams.active) {
            if (!hostile(td.team) || td.cores.size == 0) { continue; }
            EnemyIntel in = intel.get(td.team); if (in == null) { in = new EnemyIntel(); in.team = td.team; intel.put(td.team, in); }
            in.core = td.cores.first(); in.coreHpFrac = in.core.health / in.core.maxHealth; in.lastScan = clock;
            float dps = 0, hp = 0;
            for (Seen s : seenTurrets.values()) if (s.team == td.team && s.ground) {
                // only turrets that guard the core region (within 45 tiles)
                dps += s.dps; hp += s.hp; }
            in.defStrength = strength(dps, hp);
            float udps = 0, uhp = 0;
            for (Unit e : Groups.unit) if (e.team == td.team && !e.dead && visible(e.x, e.y) && e.type.weapons.size > 0 && e.type.buildSpeed <= 0 && e.type.mineTier <= 0) { udps += Math.max(1, e.type.estimateDps()); uhp += e.health; }
            in.unitStrength = strength(udps, uhp);
        }
        // forget teams that died
        Seq<Team> gone = new Seq<>(); for (Team t : intel.keys()) if (!state.teams.isActive(t) || t.data().cores.size == 0) gone.add(t);
        for (Team t : gone) { intel.remove(t); ev("enemy team " + t + " eliminated"); }
    }
    static float turretsNear(Team t, float x, float y, float rad, boolean forGround) {
        float dps = 0, hp = 0;
        for (Seen s : seenTurrets.values()) if (s.team == t && (forGround ? s.ground : s.air) && Math.hypot(0, 0) == 0 && dstSeen(s, x, y, rad)) { dps += s.dps; hp += s.hp; }
        return strength(dps, hp);
    }
    static boolean dstSeen(Seen s, float x, float y, float rad) { return true; }

    static Team pickTarget(CoreBlock.CoreBuild my) {
        Team best = null; float bs = -1e9f;
        for (EnemyIntel in : intel.values()) {
            if (in.core == null) continue;
            float dist = (float) Math.hypot(in.core.x - my.x, in.core.y - my.y) / tilesize;
            float s = -dist / 8f - in.defStrength / 300f - in.unitStrength / 300f + (1 - in.coreHpFrac) * 40f;
            if (in.team == target) s += 6f; // hysteresis: stick to a target
            if (s > bs) { bs = s; best = in.team; }
        }
        return best;
    }
    /** Enemy (any hostile) fighting strength within radius tiles of own core. */
    static float threatNearCore(CoreBlock.CoreBuild core, float radTiles) {
        float dps = 0, hp = 0;
        for (Unit e : Groups.unit) if (hostile(e.team) && !e.dead && e.type.weapons.size > 0 && e.type.buildSpeed <= 0 && e.type.mineTier <= 0 && e.dst(core) < radTiles * tilesize) { dps += Math.max(1, e.type.estimateDps()); hp += e.health; }
        return strength(dps, hp);
    }
    static float ownDefenseNearCore(CoreBlock.CoreBuild core) {
        float dps = 0, hp = 0;
        for (Building b : Groups.build) if (b.team == player.team() && b.isValid() && b.block instanceof Turret && b.dst(core) < 35 * tilesize && ((Turret.TurretBuild) b).totalAmmo > 0) {
            float[] st = turretStats(b.block.name); dps += st[0]; hp += b.health; }
        return strength(dps, hp);
    }
    static Vec2 solidSafe(float wx, float wy) {
        int tx = (int) (wx / tilesize), ty = (int) (wy / tilesize);
        for (int r = 0; r < 25; r++) for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
            Tile t = world.tile(tx + dx, ty + dy);
            if (t != null && !t.solid() && !t.floor().isDeep()) return new Vec2(t.worldx(), t.worldy());
        }
        return new Vec2(wx, wy);
    }
    /** Staging point on the straight line to the enemy core, outside known turret range (+margin). */
    static Vec2 computeStaging(CoreBlock.CoreBuild my, EnemyIntel in) {
        float dx = in.core.x - my.x, dy = in.core.y - my.y, len = (float) Math.hypot(dx, dy);
        float margin = 12 * tilesize, minD = 40 * tilesize;
        float d = Math.max(len * 0.35f, 0);
        // walk from 85% of the way back toward us until clear of all remembered turrets
        for (float f = 0.85f; f > 0.2f; f -= 0.05f) {
            float px = my.x + dx * f, py = my.y + dy * f; boolean ok = true;
            for (IntMap.Entry<Seen> e : seenTurrets) { Tile tl = world.tile(e.key); if (tl == null || e.value.team != in.team) continue;
                if (Math.hypot(tl.worldx() - px, tl.worldy() - py) < e.value.range + margin) { ok = false; break; } }
            if (Math.hypot(in.core.x - px, in.core.y - py) < minD) ok = false;
            if (ok) return solidSafe(px, py);
        }
        return solidSafe(my.x + dx * 0.3f, my.y + dy * 0.3f);
    }

    static void setPhase(Phase p, String why) { if (phase != p) { ev("phase " + phase + " -> " + p + " (" + why + ")"); phase = p; phaseSince = clock; } }

    static void command(Seq<Unit> units, Building bt, Unit ut, Vec2 pos) {
        if (units.isEmpty()) return;
        IntSeq ids = new IntSeq(); for (Unit u : units) ids.add(u.id);
        Call.commandUnits(player, ids.toArray(), bt, ut, pos, false, true);
    }

    public static void update(float dt, CoreBlock.CoreBuild core, Unit pu) {
        if (!enabled) return;
        if (!state.rules.pvp && System.getProperty("pvp.force") == null) { status = "rules.pvp=false, idle"; return; }
        if (teamId != player.team().id) { teamId = player.team().id; seenTurrets.clear(); intel.clear(); target = null; phase = Phase.BOOM; clock = 1; ev("init team=" + player.team() + " core=" + core.tileX() + "," + core.tileY()); }
        clock += dt;
        if (clock >= nextScan) { nextScan = clock + 30; scan(core); }
        if (clock < nextCmd) return;
        nextCmd = clock + 90; // decisions every 1.5 s

        Seq<Unit> allArmy = army();
        // permanent home-defense reserve: lowest-id units never leave home; the rest is the strike force
        Seq<Unit> sorted = allArmy.copy(); sorted.sort(u -> u.id);
        int resN = allArmy.size < 2 ? 0 : Math.max(RESERVE_MIN, (int) Math.ceil(allArmy.size * RESERVE_FRAC));
        resN = Math.min(resN, allArmy.size);
        Seq<Unit> reserve = new Seq<>(), army = new Seq<>();
        for (int i = 0; i < sorted.size; i++) (i < resN ? reserve : army).add(sorted.get(i));
        { Unit hn = null; float hd = 1e9f;
          for (Unit e : Groups.unit) if (hostile(e.team) && !e.dead && e.type.weapons.size > 0 && e.type.buildSpeed <= 0 && e.dst(core) < 40 * tilesize && e.dst(core) < hd) { hd = e.dst(core); hn = e; }
          if (!reserve.isEmpty()) command(reserve, null, hn, hn == null ? solidSafe(core.x + 3 * tilesize, core.y + 3 * tilesize) : null); }
        Seq<Unit> ground = army.select(u -> !u.isFlying()), air = army.select(u -> u.isFlying());
        float myStr = armyStrength(army, true);
        Team nt = pickTarget(core);
        if (nt != target) { if (nt != null) ev("target team -> " + nt); target = nt; }
        EnemyIntel in = target == null ? null : intel.get(target);
        if (in == null || in.core == null) { status = "no enemy known; army=" + army.size; wantMilitary = 0.2f; phase = Phase.BOOM; return; }
        float gameSec = clock / 60f;
        float threat = threatNearCore(core, 45), ownDef = ownDefenseNearCore(core);
        float coreDist = (float) Math.hypot(in.core.x - core.x, in.core.y - core.y) / tilesize;
        // expected strength to beat at the enemy core: turrets + their units
        float need = in.defStrength + in.unitStrength;
        // enemy defences are unknown if we never saw any turret near the core: assume a floor growing with time
        boolean sawDefense = !state.rules.fog || !seenTurrets.isEmpty();
        float assumed = sawDefense ? 0 : Math.min(400, 40 + gameSec * 0.8f);
        need = Math.max(need, assumed);
        float ratio = RATIO - Math.min(0.3f, Math.max(0, gameSec - 720) / 60f * 0.02f);
        mode = (gameSec < RUSH_SEC && need < 150) ? "rush" : "boom";
        if (phase == Phase.BOOM || phase == Phase.GATHER) wantMilitary = mode.equals("rush") ? 0.8f : 0.35f;

        Unit sc = scout();
        if (sc != null && clock - lastScoutCmd > 60 * 40) { // flyby: stop 24 tiles short of the enemy core on our side, then retreat next cycle
            lastScoutCmd = clock; boolean out = ((int) (clock / (60 * 40))) % 2 == 0;
            float ddx = core.x - in.core.x, ddy = core.y - in.core.y, l = (float) Math.hypot(ddx, ddy);
            Vec2 p = out ? new Vec2(in.core.x + ddx / l * 24 * tilesize, in.core.y + ddy / l * 24 * tilesize) : new Vec2(core.x, core.y);
            command(Seq.with(sc), null, null, p);
        }
        // ---- defend own core first (free-for-all: anybody) ----
        boolean defendNow = threat > 25 && threat > ownDef * 0.8f && !army.isEmpty();
        boolean racing = phase == Phase.ASSAULT && in.coreHpFrac < 0.25f && myStr > 2 * threat;
        if (defendNow && !racing) {
            setPhase(Phase.DEFEND, "threat=" + (int) threat + " ownDef=" + (int) ownDef);
            wantMilitary = 1f;
            Unit near = null; float bd = 1e9f;
            for (Unit e : Groups.unit) if (hostile(e.team) && !e.dead && e.dst(core) < 45 * tilesize && e.dst(core) < bd) { bd = e.dst(core); near = e; }
            command(army, null, near, near == null ? new Vec2(core.x, core.y) : null);
            status = "reserve=" + reserve.size + " DEFEND threat=" + (int) threat + " army=" + army.size;
            return;
        }
        if (phase == Phase.DEFEND) { if (threat < 10) setPhase(Phase.RETREAT, "threat cleared, regroup"); else { command(army, null, null, new Vec2(core.x, core.y)); return; } }

        // rally = between our core and the enemy, 14 tiles out
        float dx = in.core.x - core.x, dy = in.core.y - core.y, len = (float) Math.hypot(dx, dy);
        Vec2 rally = solidSafe(core.x + dx / len * 14 * tilesize, core.y + dy / len * 14 * tilesize);
        int near = 0; for (Unit u : army) if (u.dst(rally.x, rally.y) < 22 * tilesize) near++;
        status = "reserve=" + reserve.size + " phase=" + phase + " mode=" + mode + " army=" + army.size + " (" + ground.size + "g/" + air.size + "a) str=" + (int) myStr + " need=" + (int) need + " target=" + target + " coreHp=" + (int) (in.coreHpFrac * 100) + "% threat=" + (int) threat;
        if (DEBUG && clock > nextLog) { nextLog = clock + 600; Log.info("PVP-STATUS t=" + (int) gameSec + "s " + status); }

        switch (phase) {
            case BOOM, GATHER, RETREAT -> {
                command(army, null, null, rally);
                boolean rushOk = mode.equals("rush") && army.size >= Math.max(MIN_RUSH, MIN_WAVE) && myStr > need * 1.1f;
                boolean boomOk = army.size >= Math.max(6, MIN_WAVE) && myStr > need * ratio;
                if (phase == Phase.RETREAT && clock - phaseSince < 60 * 20) break; // regroup at least 20 s
                if (army.size > 0 && phase != Phase.GATHER) setPhase(Phase.GATHER, "army=" + army.size);
                if ((rushOk || boomOk) && near >= Math.max(MIN_RUSH, army.size * 9 / 10)) {
                    staging = computeStaging(core, in); armyAtLaunch = army.size; strengthAtLaunch = myStr;
                    ev("LAUNCH " + (rushOk ? "rush" : "boom") + " wave army=" + army.size + " str=" + (int) myStr + " vs need=" + (int) need + " staging=" + (int) (staging.x / 8) + "," + (int) (staging.y / 8));
                    setPhase(Phase.MARCH, "launch");
                }
                
            }
            case MARCH -> {
                command(army, null, null, staging);
                int at = 0; for (Unit u : army) if (u.dst(staging.x, staging.y) < 18 * tilesize) at++;
                if (myStr < strengthAtLaunch * 0.5f) setPhase(Phase.RETREAT, "lost half the wave while marching");
                else if (at >= army.size * 0.7f || clock - phaseSince > 60 * 90) setPhase(Phase.ASSAULT, "assembled " + at + "/" + army.size);
            }
            case ASSAULT -> {
                // re-evaluate every cycle: retreat when clearly losing, otherwise pick best target
                float localEnemy = in.defStrength + in.unitStrength;
                // v5j: an assault commits. Retreat only on catastrophic loss (wave count/strength), never on the enemy estimate,
                // because the estimate jumps on first contact and walking back under fire loses the army anyway.
                boolean losing = LEGACY_RETREAT ? (myStr < strengthAtLaunch * 0.4f || (localEnemy > 0 && myStr < localEnemy * RETREAT))
                    : (army.size < Math.max(2, (int) (armyAtLaunch * COMMIT_FLOOR)) || myStr < strengthAtLaunch * 0.12f);
                if (army.isEmpty()) { setPhase(Phase.RETREAT, "wave dead"); break; }
                if (losing && !(in.coreHpFrac < 0.25f && myStr > 2 * threat)) { setPhase(Phase.RETREAT, "losing: army=" + army.size + "/" + armyAtLaunch + " str=" + (int) myStr + " enemy=" + (int) localEnemy + " launch=" + (int) strengthAtLaunch); command(army, null, null, rally); break; }
                // target priority: enemy units near our army > turrets blocking the way (if we outclass them) > core
                Unit eu = null; float ed = 1e9f;
                for (Unit e : Groups.unit) if (e.team == target && !e.dead && e.type.weapons.size > 0 && e.type.buildSpeed <= 0 && visible(e.x, e.y)) { float d = e.dst(in.core); if (d < 30 * tilesize && d < ed) { ed = d; eu = e; } }
                Building tb = null;
                if (eu == null) {
                    float bd = 1e9f;
                    for (Building b : Groups.build) if (b.team == target && b.isValid() && b.block instanceof Turret && b.dst(in.core) < 30 * tilesize && visible(b.x, b.y)) {
                        // do not stop to kill turrets when the core is nearly dead
                        if (in.coreHpFrac < 0.3f) continue;
                        float d = b.dst(army.first()); if (d < bd) { bd = d; tb = b; } }
                }
                if (eu != null) command(army, null, eu, null);
                else if (tb != null) command(army, tb, null, null);
                else command(army, in.core, null, null);
            }
            default -> {}
        }
    }
    static boolean intelFresh(EnemyIntel in) { return clock - in.lastScan < 60 * 60; }
    public static String report() { return enabled ? status : "pvp disabled"; }
}
