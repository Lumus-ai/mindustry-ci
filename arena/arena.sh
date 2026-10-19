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
  : > $W/samples.csv; dead=0; survived=0
  for k in $(seq 1 $WAVES); do
    d=$((2+k)); m=$((k/2)); f=$((1+k))
    echo "js for(var i=0;i<$d;i++){UnitTypes.dagger.spawn(Team.crux, 640+i*10, 840);} for(var i=0;i<$m;i++){UnitTypes.mace.spawn(Team.crux, 660+i*12, 860);} for(var i=0;i<$f;i++){UnitTypes.flare.spawn(Team.crux, 700+i*8, 800);}" > $C
    for t in $(seq 1 $SAMPLES); do
      sleep 5; echo 'js var c=Vars.state.teams.get(Team.sharded).core(); "SCORE core="+(c==null?-1:Math.round(c.health))+" blds="+Vars.state.teams.get(Team.sharded).buildings.size+" enemies="+Groups.unit.count(u=>u.team==Team.crux)' > $C
    done
    sleep 1
    line=$(grep -a SCORE srvdir/server.log | tail -1); echo "$k,$line" >> $W/samples.csv
    hp=$(echo "$line" | sed -n 's/.*core=\(-\?[0-9]*\).*/\1/p')
    if [ -z "$hp" ] || [ "$hp" -le 0 ]; then dead=1; break; fi
    survived=$k
  done
  python3 - $b $WAVES $survived $dead <<'PY'
import sys,json,re
b,W,s,dead=sys.argv[1],int(sys.argv[2]),int(sys.argv[3]),int(sys.argv[4])
rows=[l.strip() for l in open(f'run/{b}/samples.csv')]
last=rows[-1] if rows else ''
g=lambda k:(int(re.search(k+r'=(-?\d+)',last).group(1)) if re.search(k+r'=(-?\d+)',last) else None)
hps=[g2 for g2 in [int(m) for r in rows for m in re.findall(r'core=(-?\d+)',r)]]
res=dict(bot=b,waves_total=W,waves_survived=s,core_destroyed=bool(dead),core_hp_final=g('core'),core_hp_min=min(hps) if hps else None,buildings_final=g('blds'))
res['score']=s*1000+(max(g('core') or 0,0)) 
json.dump(res,open(f'{__import__("os").environ.get("RESDIR","results")}/{b}.json','w'))
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
