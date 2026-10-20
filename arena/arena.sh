#!/bin/bash
source "$(cd "$(dirname "$0")/.." && pwd)/env.sh"
# usage: ./arena.sh <bot|all> [waves=20] [map=Veins]   -> results/<bot>.json, results/scoreboard.md
A=$MB/arena; cd $A; mkdir -p results run
WAVES=${2:-20}; MAP=${3:-Veins}; PORT=6570; SPORT=8100
WAVE_SEC=${WAVE_SEC:-60}; FIRST_WAVE=${FIRST_WAVE:-150}; SAMPLES=$((WAVE_SEC/5)); [ $SAMPLES -lt 1 ] && SAMPLES=1
RESDIR=${RESDIR:-results}; mkdir -p $RESDIR
J17=$J17
bots=$1; [ "$1" = all ] && bots=$(ls bots)
q(){ echo "$1" > run/cmd; }
for b in $bots; do
  pkill -f "arena-srv"; pkill -f "Dpname=arena"; sleep 1
  W=run/$b; rm -rf $W srvdir; mkdir -p $W srvdir; mkfifo srvdir/cmd; : > srvdir/server.log
  (cd srvdir; setsid nohup bash -c "sleep 100000 > cmd & $J17 -Darena-srv=1 -jar $MB/server.jar < cmd > server.log 2>&1" >/dev/null 2>&1 &)
  C=srvdir/cmd; sleep 6
  echo "host $MAP survival" > $C; sleep 3; echo "rules add waves false" > $C; echo "rules add pvp false" > $C
  echo "config port $PORT" > $C 2>/dev/null
  BOTOPTS=""; [ -f bots/$b/env ] && source bots/$b/env
  BOTOPTS="$BOTOPTS -Dtelemetry=$PWD/$W/telemetry.jsonl"
  (cd $W; export BOTOPTS HOST=localhost PORT=6567 NAME=arena_$b STREAM=$SPORT MAXSEC=$((WAVES*WAVE_SEC+FIRST_WAVE+300)); setsid nohup $MB/arena/bots/$b/run.sh > bot.log 2>&1 &)
  sleep $FIRST_WAVE
  : > $W/samples.csv; dead=0; survived=0; death_wave=0; death_t=0; T0=$SECONDS
  gover(){ grep -a -q "Game over.*map $MAP" srvdir/server.log; }
  for k in $(seq 1 $WAVES); do
    d=$((2+k)); m=$((k/2)); f=$((1+k))
    echo 'js var tm=Vars.state.teams.get(Team.sharded); var cc=tm.core(); "WSTART '$k' duos="+tm.buildings.count(b=>b.block==Blocks.duo)+" scatters="+tm.buildings.count(b=>b.block==Blocks.scatter)+" core="+(cc==null?-1:Math.round(cc.health))' > $C
    sleep 1
    if gover; then dead=1; death_wave=$k; death_t=$((SECONDS-T0)); break; fi
    if [ "${HARD:-0}" = 1 ]; then
      fo=$((k/3)); ze=$(((k-3)/3)); [ $ze -lt 0 ] && ze=0; ho=$((k/2)); m=$((k/2+1)); d=$((k+3))
      echo "js for(var i=0;i<$d;i++){UnitTypes.dagger.spawn(Team.crux, 640+i*10, 840);} for(var i=0;i<$m;i++){UnitTypes.mace.spawn(Team.crux, 660+i*12, 860);} for(var i=0;i<$fo;i++){UnitTypes.fortress.spawn(Team.crux, 680+i*14, 850);} for(var i=0;i<$ho;i++){UnitTypes.horizon.spawn(Team.crux, 700+i*8, 800);} for(var i=0;i<$ze;i++){UnitTypes.zenith.spawn(Team.crux, 720+i*10, 800);}" > $C
    else
      echo "js for(var i=0;i<$d;i++){UnitTypes.dagger.spawn(Team.crux, 640+i*10, 840);} for(var i=0;i<$m;i++){UnitTypes.mace.spawn(Team.crux, 660+i*12, 860);} for(var i=0;i<$f;i++){UnitTypes.flare.spawn(Team.crux, 700+i*8, 800);}" > $C
    fi
    for t in $(seq 1 $SAMPLES); do
      sleep 5; echo 'js var c=Vars.state.teams.get(Team.sharded).core(); "SCORE core="+(c==null?-1:Math.round(c.health))+" blds="+Vars.state.teams.get(Team.sharded).buildings.size+" enemies="+Groups.unit.count(u=>u.team==Team.crux)' > $C
      if gover; then dead=1; death_wave=$k; death_t=$((SECONDS-T0)); break 2; fi
    done
    sleep 1
    line=$(grep -a SCORE srvdir/server.log | tail -1); echo "$k,$line" >> $W/samples.csv
    survived=$k
  done
  grep -a -h -E 'WSTART|SCORE|Game over' srvdir/server.log | sed 's/\x1b\[[0-9;]*m//g' > $W/events.log
  python3 - $b $WAVES $survived $dead $death_wave $death_t $MAP <<'PY'
import sys,json,re,os
b,W,s,dead,dw,dt,MAP=sys.argv[1],int(sys.argv[2]),int(sys.argv[3]),int(sys.argv[4]),int(sys.argv[5]),int(sys.argv[6]),sys.argv[7]
ev=open(f'run/{b}/events.log').read()
ws=[(int(m.group(1)),int(m.group(2)),int(m.group(3)),int(m.group(4))) for m in re.finditer(r'WSTART (\d+) duos=(\d+) scatters=(\d+) core=(-?\d+)',ev)]
scores=[(int(m.group(1)),int(m.group(2))) for m in re.finditer(r'SCORE core=(-?\d+) blds=(\d+)',ev)]
# only samples before game over count
if dead:
    cut=ev.find('Game over'); scores=[(int(m.group(1)),int(m.group(2))) for m in re.finditer(r'SCORE core=(-?\d+) blds=(\d+)',ev[:cut])]
survived=(dw-1) if dead else s
hps=[c for c,_ in scores if c>=0]
res=dict(bot=b,waves_total=W,waves_survived=survived,core_destroyed=bool(dead),death_wave=(dw if dead else None),death_t=(dt if dead else None),
  core_hp_final=(-1 if dead else (scores[-1][0] if scores else None)),core_hp_min=(-1 if dead else (min(hps) if hps else None)),
  buildings_final=(scores[-1][1] if scores else None),duos_at_wave=[w[1] for w in ws],scatters_at_wave=[w[2] for w in ws])
res['score']=survived*1000+(0 if dead else max(res['core_hp_final'] or 0,0))
json.dump(res,open(f'{os.environ.get("RESDIR","results")}/{b}.json','w'))
print(json.dumps(res))
PY
  pkill -f "Dpname=arena"; pkill -f arena-srv; pkill -f "sleep 100000"
done
python3 - <<'PY'
import json,glob
rs=sorted([json.load(open(f)) for f in glob.glob('results/*.json')],key=lambda r:-r['score'])
o="| bot | waves survived | core destroyed | core hp final/min | buildings | score |\n|---|---|---|---|---|---|\n"
for r in rs:o+=f"| {r['bot']} | {r['waves_survived']}/{r['waves_total']} | {r['core_destroyed']} | {r['core_hp_final']}/{r['core_hp_min']} | {r['buildings_final']} | {r['score']} |\n"
open('results/scoreboard.md','w').write(o);print(o)
PY
