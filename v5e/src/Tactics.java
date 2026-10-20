import arc.math.geom.Vec2;
import mindustry.gen.*;
import mindustry.game.Team;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.storage.CoreBlock;
import static mindustry.Vars.*;

/** Tactical layer for the player's unit only. Does not change economic build quotas. */
public final class Tactics {
    public static final boolean enabled = "1".equals(System.getProperty("tactics", "0"));
    private static float clock, nextScan, lastThreat, lastScout = -100000;
    private static int teamId = -1, corePos = -1;
    public static Snapshot snapshot = new Snapshot();
    public static String status = "initializing";
    public static class Snapshot {
        public int air, ground, enemyTurrets, friendlyArmed;
        public float hostilePower, friendlyPower;
        public Unit closest;
        public CoreBlock.CoreBuild enemyCore;
        public boolean threatened() { return air + ground > 0; }
    }
    public static boolean hostile(Team team) {
        return team != null && team != Team.derelict && team != player.team();
    }
    static boolean visible(float x, float y) {
        return !state.rules.fog || (fogControl != null && fogControl.isVisible(player.team(), x, y));
    }
    static boolean enemy(Unit e) {
        return hostile(e.team) && !e.dead && e.isValid() && e.targetable(player.team()) && visible(e.x,e.y);
    }
    public static void reset() {
        clock = nextScan = lastThreat = 0; lastScout = -100000;
        snapshot = new Snapshot(); Agent.aim = null;
    }
    public static void update(float dt, CoreBlock.CoreBuild core, Unit u) {
        if (!enabled) return;
        if (teamId != player.team().id || corePos != core.pos()) { reset(); teamId = player.team().id; corePos = core.pos(); }
        clock += dt;
        if (clock >= nextScan) {
            nextScan = clock + 30;
            Snapshot s = new Snapshot();
            float nearest = Float.MAX_VALUE;
            for (Unit e : Groups.unit) {
                if (enemy(e) && e.dst(core) < 60 * tilesize) {
                    if (e.isFlying()) s.air++; else s.ground++;
                    s.hostilePower += power(e);
                    if (e.dst(core) < nearest) { nearest = e.dst(core); s.closest = e; }
                } else if (e.team == player.team() && !e.dead && e.dst(core) < 60 * tilesize) s.friendlyPower += power(e);
            }
            for (Building b : Groups.build) {
                if (!b.isValid()) continue;
                if (b.team == player.team() && b.dst(core) < 30 * tilesize && b instanceof Turret.TurretBuild tb && tb.totalAmmo > 0) s.friendlyArmed++;
                if (hostile(b.team) && visible(b.x,b.y) && b.block instanceof Turret) s.enemyTurrets++;
            }
            // Core positions are map intel, not a claim that its surroundings are currently visible.
            s.enemyCore = state.teams.closestEnemyCore(core.x, core.y, player.team());
            snapshot = s;
            if (s.threatened()) lastThreat = clock;
            status = "air=" + s.air + " ground=" + s.ground + " armed=" + s.friendlyArmed + " enemyTurretsVisible=" + s.enemyTurrets;
        }
        // Fire while mining/building/feeding without stealing movement from those goals.
        Healthc target = selectTarget(core, u, true);
        if (!(Brain.goal instanceof Raid)) Agent.aim = target == null || !u.canShoot() ? null : new Vec2(((Posc)target).getX(), ((Posc)target).getY());
    }
    static float power(Unit u) { return Math.max(1, u.type.estimateDps()) * Math.max(.15f, u.healthf()); }
    static boolean canHit(Unit u, Unit e) {
        if (!(e.isFlying() ? u.type.targetAir : u.type.targetGround)) return false;
        for (mindustry.type.Weapon w : u.type.weapons) if (w.bullet != null && (e.isFlying() ? w.bullet.collidesAir : w.bullet.collidesGround)) return true;
        return false;
    }
    static boolean safe(Unit u, float x, float y, float margin) {
        for (Building b : Groups.build) if (hostile(b.team) && b.isValid() && visible(b.x,b.y) && b.block instanceof Turret t && (u.isFlying() ? t.targetAir : t.targetGround)) {
            if (b.dst(x,y) < t.range + b.block.size * tilesize / 2f + margin) return false;
        }
        return true;
    }
    static Healthc selectTarget(CoreBlock.CoreBuild core, Unit u, boolean localOnly) {
        Healthc best = null; float score = -Float.MAX_VALUE;
        float radius = localOnly ? u.range() : 50 * tilesize;
        for (Unit e : Groups.unit) if (enemy(e) && canHit(u,e) && e.dst(u) <= radius) {
            float p = 500 - e.dst(u) + (e.dst(core) < 30 * tilesize ? 400 : 0) + (1-e.healthf()) * 80 + Math.min(100,power(e));
            if (p > score) { score = p; best = e; }
        }
        if (!u.type.targetGround) return best;
        for (Building b : Groups.build) if (hostile(b.team) && b.isValid() && visible(b.x,b.y) && b.dst(u) <= radius) {
            // Do not trade a respawnable builder into an armed turret's envelope.
            if (!safe(u,b.x,b.y,24)) continue;
            float p = (b instanceof CoreBlock.CoreBuild ? 180 : (b.block instanceof mindustry.world.blocks.production.Drill || b.block instanceof mindustry.world.blocks.power.PowerGenerator || b.block instanceof mindustry.world.blocks.units.UnitFactory) ? 260 : 100) - b.dst(u) + (1-b.healthf()) * 50;
            if (p > score) { score = p; best = b; }
        }
        return best;
    }
    public static boolean shouldScout(CoreBlock.CoreBuild core, Unit u) {
        return !snapshot.threatened() && clock-lastThreat > 15*60 && clock-lastScout > 90*60 && u.healthf() > .85f;
    }
    public static boolean shouldRaid(CoreBlock.CoreBuild core, Unit u) {
        // Explicit raid flag remains mandatory. Quotas alone are not enough.
        return System.getProperty("raid") != null && !snapshot.threatened() && clock-lastThreat > 20*60
            && snapshot.friendlyArmed >= 2 && u.healthf() > .85f && snapshot.enemyCore != null && snapshot.friendlyPower >= snapshot.hostilePower * 1.3f;
    }
    public static class Scout implements Brain.Goal {
        float time; boolean returning; Vec2 approach;
        public String name() { return returning ? "tactics scout: return" : "tactics scout: perimeter"; }
        public boolean step(float dt, CoreBlock.CoreBuild core, Unit u) {
            time += dt;
            if (snapshot.threatened() || u.healthf() < .8f || !safe(u,u.x,u.y,40) || time > 60*35) returning = true;
            CoreBlock.CoreBuild ec = snapshot.enemyCore;
            if (ec == null) { Brain.scoutReport = "no enemy core in map intel"; lastScout=clock; Brain.scouted=true; return true; }
            if (!returning && approach == null) {
                // Approach from our side, stop 45 tiles from the core; enlarge if defended.
                for (int radius=45;radius<=85;radius+=10) {
                    Vec2 v = new Vec2(core.x-ec.x,core.y-ec.y).setLength(radius*tilesize).add(ec.x,ec.y);
                    if (v.x>=0 && v.y>=0 && v.x<world.width()*tilesize && v.y<world.height()*tilesize && safe(u,v.x,v.y,60)) { approach=v; break; }
                }
                if (approach == null) returning=true;
            }
            if (!returning && !safe(u,u.x + Math.signum(approach.x-u.x)*24,u.y + Math.signum(approach.y-u.y)*24,60)) returning=true;
            if (!returning && Agent.moveTo(approach.x,approach.y,20)) {
                int turrets=0,units=0;
                for (Building b:Groups.build) if(hostile(b.team)&&b.isValid()&&visible(b.x,b.y)&&b.dst(ec)<50*tilesize&&b.block instanceof Turret) turrets++;
                for(Unit e:Groups.unit) if(enemy(e)&&e.dst(ec)<50*tilesize) units++;
                Brain.scoutReport="core map intel=("+ec.tileX()+","+ec.tileY()+") visible nearby turrets="+turrets+" units="+units+"; "+status;
                Brain.ev("tactics scout: "+Brain.scoutReport); Brain.scouted=true; lastScout=clock; returning=true;
            }
            if(returning) { Agent.aim=null; boolean done=Agent.moveTo(core.x,core.y,60)||time>60*65; if(done){lastScout=clock; if(!Brain.scouted) Brain.scoutReport="scout aborted: unsafe approach or timeout"; Brain.scouted=true;} return done; }
            return false;
        }
    }
    public static class Raid implements Brain.Goal {
        float time; boolean returning;
        public String name() { return returning ? "tactics raid: retreat" : "tactics raid: soft targets"; }
        public boolean step(float dt, CoreBlock.CoreBuild core, Unit u) {
            time += dt;
            if(snapshot.threatened()||u.healthf()<.65f||!safe(u,u.x,u.y,24)||time>60*45) returning=true;
            if(returning) { Agent.aim=null; return Agent.moveTo(core.x,core.y,60)||time>60*75; }
            Healthc target=selectTarget(core,u,false);
            if(target!=null) {
                Posc p=(Posc)target;
                float range=Math.max(20,u.range()*.88f);
                Vec2 pos=new Vec2(u.x-p.getX(),u.y-p.getY()).setLength(range).add(p.getX(),p.getY());
                if(!safe(u,pos.x,pos.y,30)) { returning=true; Agent.aim=null; return false; }
                Agent.moveTo(pos.x,pos.y,8);
                Agent.aim=u.canShoot()&&u.dst(p)<=u.range()?new Vec2(p.getX(),p.getY()):null;
            } else {
                CoreBlock.CoreBuild ec=snapshot.enemyCore;
                if(ec==null) return true;
                Vec2 pos=new Vec2(core.x-ec.x,core.y-ec.y).setLength(40*tilesize).add(ec.x,ec.y);
                if(!safe(u,pos.x,pos.y,40)||Agent.moveTo(pos.x,pos.y,24))returning=true;
                Agent.aim=null;
            }
            return false;
        }
    }
}
