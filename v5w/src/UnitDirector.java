import arc.math.geom.Vec2;
import arc.struct.*;
import arc.util.Log;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
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
    static final int COPPER_RESERVE = Integer.getInteger("unitCopperReserve", 35),
        LEAD_RESERVE = Integer.getInteger("unitLeadReserve", 25),
        SILICON_RESERVE = Integer.getInteger("unitSiliconReserve", 10);
    static CoreBlock.CoreBuild trackedCore;
    static final IntMap<String> lastOrders = new IntMap<>();
    static float clock;
    static final IntFloatMap previousProgress=new IntFloatMap();
    static int count(UnitType type, CoreBlock.CoreBuild core) {
        int n=0; for(Unit u:Groups.unit) if(u.team==core.team && !u.dead && u.type==type && !u.isPlayer()) n++;
        return n;
    }
    static int reserve(Item i) {return i==Items.copper?COPPER_RESERVE:i==Items.lead?LEAD_RESERVE:i==Items.silicon?SILICON_RESERVE:0;}
    static boolean allowed(UnitType type, CoreBlock.CoreBuild core) {
        return !state.rules.bannedUnits.contains(type) && type.supportsEnv(state.rules.env) && Units.canCreate(core.team,type);
    }
    static UnitType desired(UnitFactoryBuild f, CoreBlock.CoreBuild core) {
        if(f.block==Blocks.airFactory) {
            if(count(UnitTypes.mono,core)<Integer.getInteger("unitMiners",state.rules.pvp || state.rules.attackMode?0:2) && allowed(UnitTypes.mono,core)) return UnitTypes.mono;
            return UnitTypes.flare;
        }
        // First Nova is a builder/healer, later Novas escort the army. Never select an unsuppliable titanium recipe.
        if(count(UnitTypes.nova,core)<Integer.getInteger("unitSupport",2) &&
            core.items.get(Items.titanium)+f.items.get(Items.titanium)>=Math.ceil(20*state.rules.unitCost(core.team)) && allowed(UnitTypes.nova,core)) return UnitTypes.nova;
        if(Integer.getInteger("unitMix",0)>0 && state.rules.pvp){
            // v5s: fill every unit type up to its own cap, least-filled first (dagger first on ties)
            int cap=Integer.getInteger("unitMixCap",24);
            UnitType best=null; float bf=9;
            UnitType[] opts={UnitTypes.dagger,UnitTypes.crawler};
            for(UnitType t:opts){
                if(!allowed(t,core))continue;
                if(t==UnitTypes.nova && core.items.get(Items.titanium)+f.items.get(Items.titanium)<Math.ceil(20*state.rules.unitCost(core.team)))continue;
                if(t==UnitTypes.crawler && Integer.getInteger("unitMix",0)<2)continue;
                float fill=count(t,core)/(float)cap; if(t==UnitTypes.dagger)fill-=0.01f;
                if(fill<bf){bf=fill;best=t;}
            }
            if(best!=null)return best;
        }
        return UnitTypes.dagger;
    }

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
    static UnitPlan plan(UnitFactoryBuild f) {
        return f.currentPlan>=0 && f.currentPlan<((UnitFactory)f.block).plans.size ? ((UnitFactory)f.block).plans.get(f.currentPlan) : null;
    }
    static boolean hostile(Unit e, CoreBlock.CoreBuild core) {
        return !e.dead && e.team != core.team && e.team != mindustry.game.Team.derelict &&
            (!state.rules.fog || (fogControl!=null && fogControl.isVisible(core.team,e.x,e.y)));
    }

    /** Call from Brain.update; does not claim the builder or its build queue. */
    public static void update(float dt, CoreBlock.CoreBuild core) {
        if (!enabled) return;
        if(core!=trackedCore) { trackedCore=core; timer=180; clock=0; lastOrders.clear(); previousProgress.clear(); }
        clock+=dt; timer += dt;
        if (timer < 180) return;
        timer = 0;
        Vec2 rally = defensiveRally(core);
        for (UnitFactoryBuild f : factories(core)) {
            UnitType want=desired(f,core); UnitPlan active=plan(f);
            // Switching plans resets progress in v160.7. Complete an in-flight recipe first.
            float prior=previousProgress.get(f.pos(),f.progress);
            boolean completed=f.progress+1<prior;
            previousProgress.put(f.pos(),f.progress);
            if((active==null || active.unit!=want) && (f.progress<1 || completed || active==null || !Units.canCreate(core.team,active.unit))) {
                if(allowed(want,core)) {
                    int index=((UnitFactory)f.block).plans.indexOf(p->p.unit==want);
                    if(index>=0) {Call.tileConfig(player,f,index);configurations++;}
                }
            }
            // Do not set a factory rally for Mono: its own default mine command must survive unloading.
            if(want!=UnitTypes.mono && (f.commandPos==null || f.commandPos.dst(rally)>tilesize*3))
                Call.commandBuilding(player,new int[]{f.pos()},rally.cpy());
            if(f.power!=null && f.power.status<0.8f) for(Building b:nodes(core))
                if(b.team==core.team && b.block instanceof PowerNode node && b.power!=null &&
                    !b.power.links.contains(f.pos()) && b.power.links.size<node.maxNodes && node.linkValid(b,f) &&
                    !PowerNode.insulated(b.tile,f.tile) && gridPowered(b,core)) {
                    Call.tileConfig(player,b,f.pos());break;
                }
        }
        Unit nearGround=null,nearAir=null;
        for(Unit e:Groups.unit) if(hostile(e,core) && e.dst(core)<tilesize*45) {
            if(nearAir==null || e.dst(core)<nearAir.dst(core))nearAir=e;
            if(!e.isFlying() && (nearGround==null || e.dst(core)<nearGround.dst(core)))nearGround=e;
        }
        IntSeq ground=new IntSeq(),air=new IntSeq();
        boolean builderAssigned=false;
        for(Unit u:Groups.unit) if(u.team==core.team && !u.dead && !u.isPlayer()) {
            if(u.type==UnitTypes.mono) {mode(u,UnitCommand.mineCommand);continue;}
            if(u.type==UnitTypes.poly) {mode(u,UnitCommand.rebuildCommand);continue;}
            if(u.type!=UnitTypes.dagger && u.type!=UnitTypes.flare && u.type!=UnitTypes.nova && u.type!=UnitTypes.crawler)continue;
            if(u.health<u.maxHealth*0.35f) {move(u,rally,"recover");continue;}
            if(u.type==UnitTypes.nova && nearAir==null && !builderAssigned) {
                mode(u,UnitCommand.assistCommand);builderAssigned=true;continue;
            }
            if(u.type==UnitTypes.flare)air.add(u.id);else ground.add(u.id);
        }
        command(ground,nearGround,false,rally,core);
        command(air,nearAir,true,rally,core);
    }
    static void mode(Unit u,UnitCommand command) {
        if(u.controller() instanceof CommandAI ai && ai.currentCommand()!=command && u.type.commands.contains(command)) {
            Call.setUnitCommand(player,new int[]{u.id},command);lastOrders.remove(u.id);
        }
    }
    static void move(Unit u,Vec2 pos,String label) {
        String key=label+":"+(int)pos.x+":"+(int)pos.y+":"+(int)(clock/600);
        if(!key.equals(lastOrders.get(u.id))) {
            Call.commandUnits(player,new int[]{u.id},null,null,pos.cpy(),false,true);
            lastOrders.put(u.id,key);orders++;
        }
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
                (b.getPowerProduction() > 0.01f || (b.block instanceof SolarGenerator && state.rules.solarMultiplier>0 && state.envAttrs.get(mindustry.world.meta.Attribute.light)>0) ||
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
        if(ids.isEmpty()||(Pvp.enabled && state.rules.pvp))return; // Pvp owns combat commands in PvP
        boolean pressure=state.rules.pvp || state.rules.attackMode || "1".equals(System.getProperty("unitPressure","0"));
        Building target=pressure && ids.size>=Integer.getInteger(air?"unitAirAttackCount":"unitGroundAttackCount",air?6:4)
            ?state.teams.closestEnemyCore(core.x,core.y,core.team):null;
        IntSeq changed=new IntSeq();
        String key=(enemy!=null?"enemy:"+enemy.id:target!=null?"core:"+target.pos():"rally")+":"+(int)(clock/600);
        for(int i=0;i<ids.size;i++)if(!key.equals(lastOrders.get(ids.get(i)))){changed.add(ids.get(i));lastOrders.put(ids.get(i),key);}
        if(changed.isEmpty())return;
        // Target references invoke server CommandAI pathfinding and weapon-range behavior, not player teleportation.
        for(int offset=0;offset<changed.size;offset+=200) {
            int n=Math.min(200,changed.size-offset); int[] batch=new int[n];
            for(int i=0;i<n;i++)batch[i]=changed.get(offset+i);
            Call.commandUnits(player,batch,enemy==null?target:null,enemy,enemy==null && target==null?rally.cpy():null,false,true);
        }
        orders++;
    }

    /** Called after baseline economy and defense goals, before scouting/mining. */
    /** v5w: with silicon in the core, factory build/supply goes before hand-feeding smelters (the builder was fully busy feeding smelters: no factory for 800 s with si=362 locally, silicon 700 idle in CI match 8). */
    public static Brain.Goal nextGoal(CoreBlock.CoreBuild core, Unit u, boolean threat) {
        boolean siOk = core.items.get(Items.silicon) >= Integer.getInteger("unitSiFirst", 60);
        Brain.Goal g = nextGoal2(core, u, threat, siOk);
        if (g != null) return g;
        if (siOk && enabled && !threat) { Brain.Goal sg = AdvancedEconomy.supply(core); if (sg != null) return sg; }
        return null;
    }
    static Brain.Goal nextGoal2(CoreBlock.CoreBuild core, Unit u, boolean threat, boolean skipSmelter) {
        if (!enabled || threat) return null;
        if (!skipSmelter) { Brain.Goal sg = AdvancedEconomy.supply(core); if (sg != null) return sg; }
        Seq<UnitFactoryBuild> fs = factories(core);
        for (UnitFactoryBuild f : fs) {
            UnitPlan p = plan(f);
            if (p == null || state.rules.bannedUnits.contains(p.unit) || f.currentPlan < 0 || f.power == null || f.power.status<0.5f || !Units.canCreate(core.team, p.unit) || f.payload != null) continue;
            for (ItemStack r : p.requirements) {
                int reserve = reserve(r.item);
                int need = Math.max(1,(int)Math.ceil(r.amount*state.rules.unitCost(core.team)));
                int target = Math.min(f.getMaximumAccepted(r.item), need * 2);
                if (f.items.get(r.item) < need && core.items.get(r.item) >= reserve + need)
                    return new SupplyGoal(f, r.item, Math.min(target - f.items.get(r.item), core.items.get(r.item) - reserve));
            }
        }
        // v5w: factory state log every 60 s (payload stuck = blocked exit)
        if (clock >= nextFacDbg) { nextFacDbg = clock + 60 * 60;
            for (UnitFactoryBuild f : fs) { Building fr = f.front(); UnitPlan pl = plan(f);
                Log.info("FACDBG @@,@ payload=@ progress=@ plan=@ pwr=@ items=@ front=@ solid=@", f.block.name, f.tileX(), f.tileY(), f.payload != null, (int)(f.progress*100)/100f, pl == null ? "-" : pl.unit.name, f.power == null ? -1 : (int)(f.power.status*100)/100f, f.items.total(), fr == null ? "-" : fr.block.name, fr == null ? false : fr.tile.solid()); } }
        // v5t: the builder (gamma, mine tier 2) hand-mines coal when the core has none, so smelters never starve.
        if (Brain.count(Blocks.siliconSmelter, core.tile) > 0 && core.items.get(Items.coal) < 30 && core.items.get(Items.sand) >= 40 && core.items.get(Items.silicon) < 400) {
            if (Agent.nearestOre(Items.coal, core.tile, 45) != null) { dbgU("builder mines coal"); return new Brain.MineGoal(Items.coal, 70); }
        }
        if (threat || Brain.turrets(core.tile).size < Integer.getInteger("unitMinTurrets",2)) return null;
        for(Building b:Brain.turrets(core.tile)) if(b instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild it && (b.block==Blocks.duo||b.block==Blocks.scatter) && it.totalAmmo<10) { dbgU("gate ammo<10 "+b.block.name); return null; }
        // Do not expand a brownout into a second starved factory.
        for(UnitFactoryBuild f:fs) if(f.power==null || f.power.status<0.8f) { dbgU("gate brownout"); return null; }
        // One of each, with flares first: they intercept flyers and avoid terrain traps.
        for (Block block : (state.rules.pvp ? new Block[]{Blocks.groundFactory, Blocks.airFactory} : new Block[]{Blocks.airFactory, Blocks.groundFactory})) {
            if (state.rules.bannedBlocks.contains(block) ||
                (block == Blocks.airFactory ? state.rules.bannedUnits.contains(UnitTypes.flare) : state.rules.bannedUnits.contains(UnitTypes.dagger))) continue;
            int wantN = (state.rules.pvp && block == Blocks.groundFactory) ? Integer.getInteger("unitGround",2) : 1;
            if (fs.count(f -> f.block == block) >= wantN) continue;
            if (state.rules.pvp && block == Blocks.airFactory && fs.count(f -> f.block == Blocks.groundFactory) < Integer.getInteger("unitGround",2)) continue; // v5f: silicon goes to the ground factory first
            if(block==Blocks.groundFactory && !(state.rules.pvp || state.rules.attackMode) && count(UnitTypes.mono,core)<1) continue;
            if (!afford(block, core)) { dbgU("cannot afford "+block.name+" si="+core.items.get(Items.silicon)+" pb="+core.items.get(Items.lead)+" cu="+core.items.get(Items.copper)); continue; }
            BuildPlan p = factorySite(core, block);
            if (p != null) {
                Log.info("UNITS: build @ at @,@", block.name, p.x, p.y);
                return new Brain.BuildGoal("unit " + block.name, Seq.with(p));
            }
        }
        return null;
    }
    static float nextFacDbg;
    static float nextDbgU; static void dbgU(String m){ if(clock>=nextDbgU){ nextDbgU=clock+60*30; Log.info("UNITDBG "+m);} }
    static boolean afford(Block b, CoreBlock.CoreBuild c) {
        for (ItemStack r : b.requirements) {
            int extra = r.item==Items.silicon?Integer.getInteger("unitSiMargin",30):r.item==Items.lead?15:0;
            int reserve = reserve(r.item);
            if (c.items.get(r.item) < Math.ceil(r.amount*state.rules.buildCostMultiplier) + reserve + extra) return false;
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
                b.power.links.size < node.maxNodes && gridPowered(b, core) && !PowerNode.insulated(b.tile,t) &&
                b.dst(x * tilesize, y * tilesize) < (node.laserRange - 1) * tilesize) { power = true; break; }
            if (!power) continue;
            float s = Agent.Mathf2.dst(t.x,t.y,core.tileX(),core.tileY()) - Agent.Mathf2.dst(t.x,t.y,threat.x,threat.y) * 0.15f;
            if (s < score) { score = s; best = new BuildPlan(x, y, 0, block); }
        }
        return best;
    }

    static final class SupplyGoal implements Brain.Goal {
        final UnitFactoryBuild factory; final Item item; final int amount, recipeIndex;
        float ticks, rpc; int phase, before;
        SupplyGoal(UnitFactoryBuild f, Item i, int n) { factory = f; item = i; amount = n; recipeIndex=f.currentPlan; }
        public String name() { return "factory supply " + item.name + "@" + factory.tileX() + "," + factory.tileY(); }
        public boolean step(float dt, CoreBlock.CoreBuild core, Unit u) {
            ticks += dt; rpc += dt;
            if (!factory.isValid() || factory.team!=core.team || factory.currentPlan!=recipeIndex || ticks > 60 * 18) return true;
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
