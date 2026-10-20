import arc.struct.*;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.defense.turrets.*;
import static mindustry.Vars.*;

/** Optional, bounded technology spending. Never replaces functioning logistics.
 * Install via decide(core, unit, threat), after baseline defense goals, and tick(dt).
 * -Deconomy=0 disables this module for A/B tests.
 */
public final class AdvancedEconomy {
    static final Seq<BuildPlan> assets = new Seq<>();
    static CoreBlock.CoreBuild trackedCore;
    static float clock, nextPlan, nextLink;
    static int noChain;
    static final int[] dx={1,0,-1,0}, dy={0,1,0,-1};
    static final int RADIUS=32;

    public static void tick(float dt) {
        clock+=dt;
        if(System.getProperty("economy","0").equals("0") || clock<nextLink) return;
        nextLink=clock+120;
        resetForCore(player.core());
        // Server autolinks new nodes. Repair graph splits after damage, with no toggle spam.
        for(BuildPlan p:assets) if(p.block==Blocks.powerNode && Agent.built(p)) {
            Building a=world.build(p.x,p.y);
            PowerNode node=(PowerNode)Blocks.powerNode;
            for(BuildPlan q:assets) if(Agent.built(q)) {
                Building b=world.build(q.x,q.y);
                if(a==b || b.power==null || a.power.graph==b.power.graph || a.power.links.contains(b.pos())) continue;
                if(a.power.links.size<node.maxNodes && node.linkValid(a,b) && !PowerNode.insulated(a.tile,b.tile)) {
                    Call.tileConfig(player,a,Integer.valueOf(b.pos()));
                    break; // wait for authoritative snapshot before another link
                }
            }
        }
    }

    static float nextDbg; static void dbg(String m){ if(clock>=nextDbg){ nextDbg=clock+60*30; Brain.ev("ECONDBG "+m);} }
    public static Brain.Goal decide(CoreBlock.CoreBuild core, Unit unit, boolean threat) {
        if(System.getProperty("economy","0").equals("0")) return null;
        resetForCore(core);
        int copper=core.items.get(Items.copper), lead=core.items.get(Items.lead);
        // Preserve early-game baseline, and never expand while its item turrets are empty.
        if(Brain.turrets(core.tile).size<Integer.getInteger("econMinTurrets",8) || copper<Integer.getInteger("econMinCopper",1200)) { dbg("gate turrets/copper t="+Brain.turrets(core.tile).size+" cu="+copper); return null; }
        for(Building b:Brain.turrets(core.tile)) if(b instanceof ItemTurret.ItemTurretBuild it && (b.block==Blocks.duo || b.block==Blocks.scatter) && it.totalAmmo<20) { dbg("gate ammo<20 "+b.block.name+"@"+b.tileX()+","+b.tileY()); return null; }
        if(clock<nextPlan) return null;
        nextPlan=clock+180; // expensive route search at most once per three seconds
        for(BuildPlan p:assets) if(!Agent.built(p) && Build.validPlace(p.block,player.team(),p.x,p.y,p.rotation)) {
            Seq<BuildPlan> one=Seq.with(p);
            if(afford(one,core)) return goal("repair "+p.block.name,one);
        }
        if(threat) return null; // feeds and rebuilds always win over technology
        if(lead<180) return new Brain.MineGoal(Items.lead,100);

        if(Brain.count(Blocks.combustionGenerator,core.tile)<(core.items.get(Items.titanium)>=30 && core.items.get(Items.silicon)>=60 ? 14 : Brain.count(Blocks.siliconSmelter,core.tile)>0 ? 3 : 1)) {
            Seq<BuildPlan> p=generator(core);
            if(p!=null && afford(p,core)) return goal("coal power",p);
        }
        if(Brain.count(Blocks.combustionGenerator,core.tile)>0 && Brain.count(Blocks.siliconSmelter,core.tile)<1) {
            Seq<BuildPlan> p=silicon(core);
            if(p!=null && afford(p,core)) return goal("silicon chain",p);
            if(++noChain%10==1) Brain.ev("economy: no affordable non-overlapping silicon route; keeping baseline defenses");
        }
        Building source=powerSource(core);
        if(source!=null) {
            if(Brain.count(Blocks.battery,core.tile)<2) {
                Seq<BuildPlan> p=poweredPlacement(core,Blocks.battery,source.tile,10,false);
                if(p!=null && afford(p,core)) return goal("power buffer",p);
            }
            if(Brain.count(Blocks.mender,core.tile)<2) {
                Building hurt=null;
                for(Building b:Brain.turrets(core.tile)) {
                    if(covered(b,Blocks.mender,4)) continue;
                    if(hurt==null || b.health/b.maxHealth<hurt.health/hurt.maxHealth) hurt=b;
                }
                if(hurt!=null) {
                    Seq<BuildPlan> p=poweredPlacement(core,Blocks.mender,hurt.tile,4,true);
                    if(p!=null && afford(p,core)) return goal("mender",p);
                }
            }
            // Lancer costs titanium on this exact version. Do not promise a silicon-only unlock.
            if(core.items.get(Items.titanium)<30 && Brain.count(Blocks.pneumaticDrill,core.tile)<1 && core.items.get(Items.graphite)>=20) {
                Planner p=new Planner(core.tile);
                if(p.feed(Items.titanium,Blocks.pneumaticDrill,foot(core.tile,core.block),65) && afford(p.plans,core)) return goal("titanium drill",p.plans);
            }
            if(source.power.graph.getLastPowerProduced()>=7.2f && Brain.count(Blocks.lancer,core.tile)<1 && core.items.get(Items.titanium)>=30 && core.items.get(Items.silicon)>=60) {
                Tile t=Brain.turretSite(core.tile,Blocks.lancer);
                if(t!=null) {
                    Planner p=new Planner(core.tile);
                    if(p.add(t,Blocks.lancer) && p.connect(t) && afford(p.plans,core)) return goal("lancer",p.plans);
                }
            }
            // Solar is supplemental, never the silicon bootstrap or assumed night power.
            if(core.items.get(Items.silicon)>180 && Brain.count(Blocks.solarPanel,core.tile)<6) {
                Seq<BuildPlan> p=poweredPlacement(core,Blocks.solarPanel,source.tile,6,false);
                if(p!=null && afford(p,core)) return goal("supplemental solar",p);
            }
        }
        // Stockpile spending is bounded; no count = copper/constant feedback loop.
        // A larger turret fleet without ammo logistics is worse than four working duos.
        if(copper>1200 && lead>240 && Brain.count(Blocks.duo,core.tile)<8) {
            Tile t=Brain.turretSite(core.tile,Blocks.duo);
            if(t!=null) { Seq<BuildPlan> p=Seq.with(new BuildPlan(t.x,t.y,0,Blocks.duo));if(afford(p,core))return goal("reserve duo",p); }
        }
        if(copper>1600 && lead>350 && Brain.count(Blocks.scatter,core.tile)<3 && Brain.flyersSeen) {
            Tile t=Brain.turretSite(core.tile,Blocks.scatter);
            if(t!=null) { Seq<BuildPlan> p=Seq.with(new BuildPlan(t.x,t.y,0,Blocks.scatter));if(afford(p,core))return goal("reserve scatter",p); }
        }
        return null;
    }

    static void resetForCore(CoreBlock.CoreBuild core) {
        if(core!=trackedCore) { assets.clear();trackedCore=core;nextPlan=0;noChain=0; }
    }
    static boolean covered(Building target,Block type,int range) {
        for(Building b:Groups.build) if(b.team==player.team() && b.block==type && b.dst(target)<range*tilesize) return true;
        return false;
    }
    static Brain.Goal goal(String name,Seq<BuildPlan> p) {
        for(BuildPlan q:p) if(!assets.contains(a->a.x==q.x && a.y==q.y)) assets.add(q);
        Brain.ev("economy plan: "+name+" blocks="+p.size);
        return new EconomyBuild(name,p);
    }
    static boolean afford(Seq<BuildPlan> plans,CoreBlock.CoreBuild core) {
        ObjectIntMap<Item> costs=new ObjectIntMap<>();
        for(BuildPlan p:plans) if(!Agent.built(p)) for(ItemStack s:p.block.requirements) costs.increment(s.item,Math.round(s.amount*state.rules.buildCostMultiplier));
        for(var e:costs) {
            int reserve=e.key==Items.copper?Math.max(250,Brain.turrets(core.tile).size*35):e.key==Items.lead?80:0;
            if(core.items.get(e.key)<e.value+reserve)return false;
        }
        return true;
    }
    /** Bounded build with live occupancy checks, no marking unrelated baseline sites bad. */
    static class EconomyBuild implements Brain.Goal {
        final String name; final Seq<BuildPlan> plans; float time; int lastMissing=-1;float stall;
        EconomyBuild(String n,Seq<BuildPlan> p){name=n;plans=p.copy();}
        public String name(){return "economy "+name;}
        public boolean step(float dt,CoreBlock.CoreBuild core,Unit unit) {
            time+=dt;stall+=dt;Seq<BuildPlan> missing=plans.select(p->!Agent.built(p));
            if(missing.isEmpty()){Agent.plans.clear();Brain.ev("economy complete: "+name);return true;}
            if(lastMissing!=missing.size){lastMissing=missing.size;stall=0;}
            if(stall>60*15 || time>60*90){
                Brain.ev("economy build stopped: "+name+" missing="+missing.size);
                // Unbuilt pieces are not an infinite rebuild commitment.
                for(BuildPlan p:missing) assets.remove(p,true);
                Agent.plans.clear();return true;
            }
            BuildPlan first=missing.first();
            Tile t=world.tile(first.x,first.y);
            if(t==null || (t.build!=null && t.block()!=Blocks.air && !(t.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild) && !Build.validPlace(first.block,player.team(),first.x,first.y,first.rotation))) {
                assets.removeAll(p->!Agent.built(p));Agent.plans.clear();return true;
            }
            Agent.mineTarget=null;unit.mineTile=null;
            if(unit.dst(first.x*tilesize,first.y*tilesize)>90) Agent.moveTo(first.x*tilesize,first.y*tilesize,55);else unit.vel.setZero();
            Agent.plans.clear();for(BuildPlan p:missing)Agent.plans.addLast(p);
            return false;
        }
    }
    static Seq<Tile> foot(Tile t,Block b){return t.getLinkedTilesAs(b,new Seq<>());}
    static Building powerSource(CoreBlock.CoreBuild core) {
        for(Building b:Groups.build) if(b.team==player.team() && b.block==Blocks.combustionGenerator && b.dst(core)<RADIUS*tilesize)return b;
        return null;
    }
    static Seq<BuildPlan> generator(CoreBlock.CoreBuild core) {
        // Place the generator close to an unclaimed coal drill, keeping explosives away from core.
        for(Tile coal:drillSites(core.tile,Items.coal,Blocks.mechanicalDrill)) {
            for(int x=coal.x-3;x<=coal.x+3;x++)for(int y=coal.y-3;y<=coal.y+3;y++) {
                Tile t=world.tile(x,y);if(t==null || t.dst(core.tile)<6*tilesize)continue;
                Planner p=new Planner(core.tile);
                if(p.add(t,Blocks.combustionGenerator) && p.feedAt(coal,Blocks.mechanicalDrill,foot(t,Blocks.combustionGenerator),12) && (powerSource(core)==null || p.connect(t)))return p.plans;
            }
        }
        return null;
    }
    static Seq<BuildPlan> silicon(CoreBlock.CoreBuild core) {
        // Smelter must dump directly into the core; do not rely on mixed output belts.
        Seq<Tile> coreTiles=foot(core.tile,core.block);
        for(int x=core.tileX()-5;x<=core.tileX()+5;x++)for(int y=core.tileY()-5;y<=core.tileY()+5;y++) {
            Tile t=world.tile(x,y);if(t==null || !touches(foot(t,Blocks.siliconSmelter),coreTiles))continue;
            for(boolean sandFirst:new boolean[]{true,false}) {
                Planner p=new Planner(core.tile);
                if(!p.add(t,Blocks.siliconSmelter))continue;
                Seq<Tile> target=foot(t,Blocks.siliconSmelter);
                if(p.feed(sandFirst?Items.sand:Items.coal,Blocks.mechanicalDrill,target,65) && p.feed(sandFirst?Items.coal:Items.sand,Blocks.mechanicalDrill,target,65) && p.connect(t))return p.plans;
            }
        }
        return null;
    }
    static boolean touches(Seq<Tile>a,Seq<Tile>b) {
        for(Tile t:a)for(int i=0;i<4;i++){Tile n=world.tile(t.x+dx[i],t.y+dy[i]);if(n!=null && b.contains(n,true))return true;}return false;
    }
    static Seq<BuildPlan> poweredPlacement(CoreBlock.CoreBuild core,Block b,Tile center,int radius,boolean avoidCenter) {
        Seq<Tile> sites=new Seq<>();
        for(int x=center.x-radius;x<=center.x+radius;x++)for(int y=center.y-radius;y<=center.y+radius;y++){
            Tile t=world.tile(x,y);if(t!=null && (!avoidCenter || t.dst(center)>tilesize) && t.dst(center)<=radius*tilesize)sites.add(t);
        }
        sites.sort(t->t.dst(center));
        for(Tile t:sites){Planner p=new Planner(core.tile);if(p.add(t,b) && p.connect(t))return p.plans;}
        return null;
    }
    static Seq<Tile> drillSites(Tile core,Item item,Block block) {
        Drill drill=(Drill)block;Seq<Tile> out=new Seq<>();
        for(int x=core.x-RADIUS;x<=core.x+RADIUS;x++)for(int y=core.y-RADIUS;y<=core.y+RADIUS;y++){
            Tile t=world.tile(x,y);if(t==null || !Build.validPlace(block,player.team(),x,y,0))continue;
            int n=0;boolean mixed=false;
            for(Tile q:foot(t,block)) {if(q.block()!=Blocks.air){mixed=true;break;}if(drill.canMine(q)){if(q.drop()==item)n++;else mixed=true;}}
            // Sand is low-priority. Reject all mixed ore footprints rather than silently mine copper.
            if(n>=3 && !mixed)out.add(t);
        }
        out.sort(t->t.dst(core));return out;
    }
    static class Planner {
        final Tile core;final Seq<BuildPlan>plans=new Seq<>();final IntSet occupied=new IntSet();
        Planner(Tile c){core=c;}
        boolean add(Tile t,Block b) {
            if(t==null || !Build.validPlace(b,player.team(),t.x,t.y,0))return false;
            for(Tile q:foot(t,b))if(occupied.contains(q.pos()) || q.block()!=Blocks.air)return false;
            for(Tile q:foot(t,b))occupied.add(q.pos());
            plans.add(new BuildPlan(t.x,t.y,0,b));return true;
        }
        boolean feed(Item item,Block drill,Seq<Tile> target,int max) {
            for(Tile d:drillSites(core,item,drill))if(feedAt(d,drill,target,max))return true;
            return false;
        }
        boolean feedAt(Tile d,Block drill,Seq<Tile>target,int max) {
            for(Tile q:foot(d,drill))if(occupied.contains(q.pos()))return false;
            Seq<BuildPlan> path=route(foot(d,drill),target,max);
            if(path==null || !add(d,drill))return false;
            for(BuildPlan p:path){plans.add(p);occupied.add(world.tile(p.x,p.y).pos());}return true;
        }
        Seq<BuildPlan> route(Seq<Tile> from,Seq<Tile> target,int max) {
            ObjectMap<Tile,Tile>prev=new ObjectMap<>();ObjectIntMap<Tile>dist=new ObjectIntMap<>();Queue<Tile>todo=new Queue<>();
            for(Tile t:from)for(int i=0;i<4;i++){
                Tile n=world.tile(t.x+dx[i],t.y+dy[i]);
                if(n!=null && target.contains(n,true))return new Seq<>();
                if(pass(n,from) && !prev.containsKey(n)){prev.put(n,null);dist.put(n,1);todo.addLast(n);}
            }
            Tile end=null,targetTile=null;
            while(!todo.isEmpty() && end==null){Tile t=todo.removeFirst();for(int i=0;i<4;i++){
                Tile n=world.tile(t.x+dx[i],t.y+dy[i]);if(n==null)continue;
                if(target.contains(n,true)){end=t;targetTile=n;break;}
                if(dist.get(t,0)<max && pass(n,from) && !prev.containsKey(n)){prev.put(n,t);dist.put(n,dist.get(t,0)+1);todo.addLast(n);}
            }}
            if(end==null)return null;
            Seq<Tile>path=new Seq<>();for(Tile t=end;t!=null;t=prev.get(t))path.add(t);path.reverse();
            Seq<BuildPlan>out=new Seq<>();
            for(int i=0;i<path.size;i++){Tile t=path.get(i),n=i+1<path.size?path.get(i+1):targetTile;out.add(new BuildPlan(t.x,t.y,Agent.dirOf(n.x-t.x,n.y-t.y),Blocks.conveyor));}
            return out;
        }
        boolean pass(Tile t,Seq<Tile>from){return t!=null && !from.contains(t,true) && !occupied.contains(t.pos()) && Math.abs(t.x-core.x)<=45 && Math.abs(t.y-core.y)<=45 && t.block()==Blocks.air && Build.validPlace(Blocks.conveyor,player.team(),t.x,t.y,0);}
        boolean connect(Tile target) {
            // Grow a node chain from target to the nearest live/planned powered building.
            Seq<Tile> anchors=new Seq<>();
            for(Building b:Groups.build)if(b.team==player.team() && b.power!=null && b.dst(core.worldx(),core.worldy())<45*tilesize)anchors.add(b.tile);
            for(BuildPlan p:plans)if(p.block.hasPower && (p.x!=target.x || p.y!=target.y))anchors.add(world.tile(p.x,p.y));
            if(anchors.isEmpty())return false;
            anchors.sort(t->t.dst(target));Tile goal=anchors.first();Tile current=target;
            for(int step=0;step<20;step++){
                if(current.dst(goal)<=tilesize*1.01f)return true; // adjacent blocks conduct
                Tile best=null;float score=Float.MAX_VALUE;
                for(int x=current.x-5;x<=current.x+5;x++)for(int y=current.y-5;y<=current.y+5;y++){
                    Tile t=world.tile(x,y);if(t==null || t.dst(current)>5*tilesize || t.dst(goal)>=current.dst(goal)-tilesize*.25f || PowerNode.insulated(current,t))continue;
                    if(occupied.contains(t.pos()) || t.block()!=Blocks.air || !Build.validPlace(Blocks.powerNode,player.team(),x,y,0))continue;
                    float s=t.dst(goal);if(s<score){score=s;best=t;}
                }
                if(best==null)return false;
                if(!add(best,Blocks.powerNode))return false;
                if(best.dst(goal)<5*tilesize && !PowerNode.insulated(best,goal))return true;
                current=best;
            }
            return false;
        }
    }
}
