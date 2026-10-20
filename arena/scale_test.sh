#!/bin/bash
# usage: scale_test.sh <scale> <bot> <minutes>   server-side time scale proof: sets Time delta provider via console js, samples game time vs wall time and bot progress
source "$(cd "$(dirname "$0")/.." && pwd)/env.sh"
A=$MB/arena; cd $A; mkdir -p results
S=${1:-1}; BOT=${2:-ours_v3}; MIN=${3:-4}; C=srvdir/cmd
pkill -f arena-srv; sleep 1; rm -rf srvdir; mkdir srvdir; mkfifo $C; : > srvdir/server.log
(cd srvdir; setsid nohup bash -c "sleep 100000 > cmd & $J17 -Darena-srv=1 -jar $MB/server.jar < cmd > server.log 2>&1" >/dev/null 2>&1 &)
sleep 6; echo "host glacier pvp" > $C; sleep 4; echo "rules add waves false" > $C
echo 'js "PROBE delta="+Time.delta+" time="+Time.time+" gfxdt="+Core.graphics.getDeltaTime()' > $C; sleep 1
if [ "$S" != "1" ]; then
  echo "js Time.setDeltaProvider(()=>Math.min(Core.graphics.getDeltaTime()*60,6)*$S)" > $C; sleep 1
  echo 'js "AFTER delta="+Time.delta' > $C
fi
./launch.sh local $BOT >/dev/null
END=$(( $(date +%s) + MIN*60 )); : > results/scale_samples.log
while [ $(date +%s) -lt $END ]; do
  echo 'js var t=Vars.state.teams.get(Team.sharded); var c2=Vars.state.teams.get(Team.crux); "SCALE wall="+Math.round(Time.millis()/1000)+" time="+Math.round(Time.time)+" tick="+Math.round(Vars.state.tick)+" delta="+Time.delta+" bldsA="+t.buildings.size+" bldsB="+c2.buildings.size+" players="+Groups.player.size()' > $C
  sleep 10
done
sed 's/\x1b\[[0-9;]*m//g' srvdir/server.log | grep -a -E "PROBE|AFTER|SCALE " | sed -E 's/^.*(PROBE|AFTER|SCALE )/\1/' > results/scale_samples.log
./stop.sh; pkill -f arena-srv; pkill -f "sleep 100000"
python3 - <<'PY'
import re
r=[l for l in open('results/scale_samples.log') if l.startswith('SCALE')]
def g(l,k): return float(re.search(k+r'=(-?[\d.]+)',l).group(1))
if len(r)>2:
    w0,t0=g(r[0],'wall'),g(r[0],'time'); w1,t1=g(r[-1],'wall'),g(r[-1],'time')
    print("game seconds per wall second: %.2f (Time.time ticks/60 / wall)"%(((t1-t0)/60)/(w1-w0)))
PY
