import arc.struct.*;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.storage.CoreBlock;
import static mindustry.Vars.*;

/** Optional v4 opening. Normal client build plans only, no server mutations. */
public final class EarlyEconomy {
    public static boolean enabled(){return Boolean.getBoolean("earlyEconomy");}
    static CoreBlock.CoreBuild owner;
    static final ObjectMap<Item,Seq<Tile>> ore = new ObjectMap<>();
    static final Seq<BuildPlan> assets=new Seq<>();
    static final IntSet failed=new IntSet();
    static int cursor, checkpoint, width, height;
    static float clock, nextPlan, nextReport;
    static final int[] dx={1,0,-1,0},dy={0,1,0,-1};
    static final Item[] indexed={Items.copper,Items.lead,Items.coal,Items.sand,Items.titanium,Items.thorium};
    public static void tick(float dt,CoreBlock.CoreBuild core){
        if(!enabled())return;
        if(owner!=core || width!=world.width() || height!=world.height()){
            owner=core;width=world.width();height=world.height();cursor=checkpoint=0;clock=nextPlan=nextReport=0;
            ore.clear();assets.clear();failed.clear();for(Item i:indexed)ore.put(i,new Seq<>());
        }
        clock+=dt;
        // The normal multiplayer world snapshot already exposes terrain. Index all of it,
        // rather than fly the only builder away or repeatedly scan a 30-tile square.
        for(int k=0;k<2048 && cursor<width*height;k++,cursor++){
            Tile t=world.tile(cursor%width,cursor/width);
            if(t!=null && t.block()==Blocks.air && t.drop()!=null && ore.containsKey(t.drop()))ore.get(t.drop()).add(t);
        }
        if(clock>=nextReport){nextReport=clock+1800;
            Brain.ev("early-econ t="+(int)(clock/60)+" indexed="+cursor+"/"+(width*height)+" copperDrills="+count(Items.copper)+" leadDrills="+count(Items.lead));}
        int[] marks={180,300,600,900};
        if(checkpoint<marks.length && clock>=marks[checkpoint]*60){
            Brain.ev("ECON-CHECKPOINT minute="+(marks[checkpoint]/60)+" copper="+core.items.get(Items.copper)+" lead="+core.items.get(Items.lead)+" copperDrills="+count(Items.copper)+" leadDrills="+count(Items.lead)+" graphite="+core.items.get(Items.graphite)+" silicon="+core.items.get(Items.silicon)+" coreHP="+(int)core.health);
            checkpoint++;
        }
    }
    static Item output(Tile t,Block block){
        Drill d=(Drill)block;ObjectIntMap<Item> counts=new ObjectIntMap<>();
        for(Tile q:t.getLinkedTilesAs(block,new Seq<>()))if(d.canMine(q))counts.increment(q.drop());
        // Exact v160.7 Drill.countOre ordering: low-priority sand loses to ore,
        // then more tiles wins, then higher item id breaks ties.
        Item best=null;int n=-1;
        for(var e:counts){
            if(best==null || (best.lowPriority && !e.key.lowPriority) || best.lowPriority==e.key.lowPriority && (e.value>n || e.value==n && e.key.id>best.id)){best=e.key;n=e.value;}
        }return best;
    }
    public static int count(Item item){int n=0;for(Building b:Groups.build)if(b.team==player.team() && b.block instanceof Drill && output(b.tile,b.block)==item)n++;return n;}
    static boolean safe(Tile t){
        for(Tile spawn:spawner.getSpawns())if(t.dst(spawn)<state.rules.dropZoneRadius+3*tilesize)return false;
        for(Unit e:Groups.unit)if(e.team!=player.team() && e.team!=mindustry.game.Team.derelict && !e.dead && e.dst(t.worldx(),t.worldy())<22*tilesize)return false;
        Building ec=state.teams.closestEnemyCore(t.worldx(),t.worldy(),player.team());
        return ec==null || ec.dst(t.worldx(),t.worldy())>55*tilesize;
    }
    public static Brain.Goal decide(CoreBlock.CoreBuild core,Unit unit,boolean threat){
        if(!enabled() || owner!=core || threat || clock<nextPlan)return null;
        nextPlan=clock+120;
        int cu=core.items.get(Items.copper), nc=count(Items.copper), nl=count(Items.lead);
        int turrets=Brain.turrets(core.tile).size;
        // Bootstrap two income sources, then let defense reach its opening floor.
        if(nc>=2 && turrets<Math.min(4,2+nc/4))return null;
        // Expansion is discretionary: require stocked opening guns and leave copper for baseline defense.
        if(nc>=2)for(Building b:Brain.turrets(core.tile))if(b instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild it && it.totalAmmo<20)return null;
        // Leave front-line destroyed routes to a later safe full-route plan, not
        // one-block repair ping-pong. Already-built disconnected drill counts are
        // audited by the integration owner; no destructive cleanup here.
        int wantCopper=clock<180*60?6:clock<300*60?10:clock<600*60?14:18;
        int wantLead=clock<180*60?3:clock<300*60?6:8;
        Item item=nc<2?Items.copper:nl<2?Items.lead:(nl*2<nc&&nl<wantLead)?Items.lead:nc<wantCopper?Items.copper:nl<wantLead?Items.lead:null;
        if(item==null)return null;
        Seq<BuildPlan> plan=plan(core,item);
        // v5n: if the preferred resource has no legal route, expand the other one instead of stalling forever.
        if(plan==null){
            Item alt=item==Items.lead?(nc<wantCopper?Items.copper:null):(nl<wantLead?Items.lead:null);
            if(alt!=null){Seq<BuildPlan> p2=plan(core,alt); if(p2!=null){item=alt;plan=p2;Brain.ev("early-econ fallback "+item.name);}}
        }
        int reserve=nc<2?25:turrets<4?150:250;
        if(plan!=null && afford(plan,core,reserve)){
            nextPlan=clock+600;
            assets.addAll(plan);Brain.ev("early-econ expand "+item.name+" blocks="+plan.size+" reserve="+reserve);
            return new Expand(item.name,plan);
        }
        // Avoid the old 45-second mining goal when investment is only a few copper away.
        // Ordinary Brain mining still fills the bank when there is no legal route.
        if(plan!=null && cu<reserve+cost(plan,Items.copper) && cu<200)return new Brain.MineGoal(Items.copper,80);
        return null;
    }
    static int key(int x,int y){return x+y*width;}
    static int cost(Seq<BuildPlan> p,Item item){int n=0;for(BuildPlan b:p)if(!Agent.built(b))for(ItemStack s:b.block.requirements)if(s.item==item)n+=Math.round(s.amount*state.rules.buildCostMultiplier);return n;}
    static boolean afford(Seq<BuildPlan> p,CoreBlock.CoreBuild c,int reserve){for(Item i:content.items())if(c.items.get(i)<cost(p,i)+(i==Items.copper?reserve:0))return false;return true;}
    static float nextPlanDbg;
    static Seq<BuildPlan> plan(CoreBlock.CoreBuild c,Item item){
        Seq<BuildPlan> p=plan0(c,item,false);
        if(p==null){
            p=plan0(c,item,true);
            if(clock>=nextPlanDbg){nextPlanDbg=clock+60*120;
                Seq<Tile> f=ore.get(item);int n=f==null?0:f.size;float nd=1e9f;if(f!=null)for(Tile q:f)nd=Math.min(nd,q.dst(c.tile)/tilesize);
                Brain.ev("early-econ NOPLAN "+item.name+" oreTiles="+n+" nearest="+(int)nd+" tiles; relaxed plan "+(p==null?"also null":"found blocks="+p.size));}
        }
        return p;
    }
    static Seq<BuildPlan> plan0(CoreBlock.CoreBuild c,Item item,boolean relaxed){
        Seq<Tile> candidates=new Seq<>();IntSet seen=new IntSet();Seq<Tile> found=ore.get(item);
        if(found==null)return null;
        int radius=relaxed?170:clock<180*60?48:clock<300*60?72:110;
        for(Tile q:found)for(int a=-1;a<=0;a++)for(int b=-1;b<=0;b++){
            Tile t=world.tile(q.x+a,q.y+b);if(t==null || !seen.add(t.pos()) || t.dst(c.tile)>radius*tilesize || failed.contains(key(t.x,t.y)))continue;
            if(!Build.validPlace(Blocks.mechanicalDrill,player.team(),t.x,t.y,0) || !safe(t) || output(t,Blocks.mechanicalDrill)!=item)continue;
            int covered=0;for(Tile z:t.getLinkedTilesAs(Blocks.mechanicalDrill,new Seq<>()))if(z.drop()==item)covered++;
            if(covered>=(relaxed?2:3))candidates.add(t);
        }
        candidates.sort(t->t.dst(c.tile));Seq<BuildPlan> best=null;float score=Float.MAX_VALUE;
        IntSet sinks=sinks(c);
        int attempts=0;for(Tile t:candidates){if(attempts++>=(relaxed?80:32))break;
            Seq<BuildPlan> p=route(t,c,sinks,relaxed?320:Math.min(150,radius*2));if(p==null)continue;
            float s=p.size+t.dst(c.tile)/tilesize*.1f;
            if(s<score){score=s;best=p;}
        }return best;
    }
    static IntSet sinks(CoreBlock.CoreBuild c){
        IntSet sinks=new IntSet();for(Tile t:c.tile.getLinkedTilesAs(c.block,new Seq<>()))sinks.add(t.pos());
        // Reuse only belts with a verified directed path into core. No arbitrary belt taps.
        // Bound each trace; loops and destroyed downstream pieces are not sinks.
        for(Building b:Groups.build)if(b.team==player.team() && b.block==Blocks.conveyor){
            Tile t=b.tile;IntSet visited=new IntSet();boolean reaches=false;
            for(int k=0;k<180;k++){
                if(t.build==c){reaches=true;break;}
                if(t.build==null || t.team()!=player.team() || t.block()!=Blocks.conveyor || !visited.add(t.pos()))break;
                int r=t.build.rotation;t=world.tile(t.x+dx[r],t.y+dy[r]);if(t==null)break;
            }
            if(reaches && visited.size<=140)sinks.add(b.pos());
        }
        return sinks;
    }
    static boolean empty(Tile t,IntSet footprint){return t!=null && !footprint.contains(t.pos()) && t.block()==Blocks.air && safe(t) && Build.validPlace(Blocks.conveyor,player.team(),t.x,t.y,0);}
    static Seq<BuildPlan> route(Tile drill,CoreBlock.CoreBuild c,IntSet sinks,int max){
        IntSet foot=new IntSet();for(Tile t:drill.getLinkedTilesAs(Blocks.mechanicalDrill,new Seq<>()))foot.add(t.pos());
        ObjectMap<Tile,Tile> prev=new ObjectMap<>();ObjectIntMap<Tile> dist=new ObjectIntMap<>();Queue<Tile> todo=new Queue<>();
        // Directly touching core or a usable trunk needs no belt.
        for(Tile t:drill.getLinkedTilesAs(Blocks.mechanicalDrill,new Seq<>()))for(int r=0;r<4;r++){
            Tile n=world.tile(t.x+dx[r],t.y+dy[r]);
            if(accept(n,t,sinks))return Seq.with(new BuildPlan(drill.x,drill.y,0,Blocks.mechanicalDrill));
            if(empty(n,foot) && !prev.containsKey(n)){prev.put(n,null);dist.put(n,1);todo.addLast(n);}
        }
        Tile end=null,dest=null;
        while(!todo.isEmpty() && end==null){Tile t=todo.removeFirst();for(int r=0;r<4;r++){
            Tile n=world.tile(t.x+dx[r],t.y+dy[r]);if(n==null)continue;
            if(accept(n,t,sinks)){end=t;dest=n;break;}
            if(dist.get(t,0)<max && empty(n,foot) && !prev.containsKey(n)){prev.put(n,t);dist.put(n,dist.get(t,0)+1);todo.addLast(n);}
        }}
        if(end==null)return null;
        Seq<BuildPlan> out=new Seq<>();Tile next=dest;
        // Build downstream first and drill last: a completed drill always has its output path.
        for(Tile t=end;t!=null;t=prev.get(t)){out.add(new BuildPlan(t.x,t.y,Agent.dirOf(next.x-t.x,next.y-t.y),Blocks.conveyor));next=t;}
        out.add(new BuildPlan(drill.x,drill.y,0,Blocks.mechanicalDrill));return out;
    }
    static boolean accept(Tile sink,Tile source,IntSet sinks){return sink!=null && sinks.contains(sink.pos()) && (sink.block()!=Blocks.conveyor || Agent.dirOf(source.x-sink.x,source.y-sink.y)!=sink.build.rotation);}
    static class Expand implements Brain.Goal{
        final Seq<BuildPlan> plans;final String label;float time,stall;int last=-1;
        Expand(String label,Seq<BuildPlan> p){this.label=label;plans=p.copy();}
        public String name(){return "early-econ "+label;}
        public boolean step(float dt,CoreBlock.CoreBuild c,Unit u){
            time+=dt;stall+=dt;Seq<BuildPlan> missing=plans.select(p->!Agent.built(p));
            if(missing.isEmpty()){Agent.plans.clear();return true;}
            if(last!=missing.size){last=missing.size;stall=0;}
            BuildPlan p=missing.first();
            if(stall>60*15 || time>60*90 || !Build.validPlace(p.block,player.team(),p.x,p.y,p.rotation) && world.build(p.x,p.y)!=null && !(world.build(p.x,p.y) instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild)){
                for(BuildPlan q:missing){failed.add(key(q.x,q.y));assets.remove(q,true);}Agent.plans.clear();Brain.ev("early-econ stopped "+label+" missing="+missing.size);return true;
            }
            Agent.mineTarget=null;u.mineTile=null;Agent.aim=null;
            if(u.dst(p.x*tilesize,p.y*tilesize)>90)Agent.moveTo(p.x*tilesize,p.y*tilesize,55);else u.vel.setZero();
            Agent.plans.clear();for(BuildPlan q:missing)Agent.plans.addLast(q);return false;
        }
    }
}
