#!/bin/bash
source "$(cd "$(dirname "$0")/.." && pwd)/env.sh"
# usage: ./pvp.sh <map=glacier> <minutes=20> <bot1> <bot2> [bot3 ...]   free-for-all, last team standing wins
# Maps with 2 prebuilt cores (glacier, passage, canyon). >2 bots on a 2-core map: extra bots share teams (server assigns).
A=$MB/arena; cd $A; mkdir -p results
MAP=${1:-glacier}; MIN=${2:-20}; shift 2; BOTS="$@"
J17=$J17; C=srvdir/cmd
pkill -f arena-srv; pkill -f "Dpname=.*_[0-9]"; sleep 1; rm -rf srvdir; mkdir srvdir; mkfifo $C; : > srvdir/server.log
(cd srvdir; setsid nohup bash -c "sleep 100000 > cmd & $J17 -Darena-srv=1 -jar $MB/server.jar < cmd > server.log 2>&1" >/dev/null 2>&1 &)
sleep 6; echo "host $MAP pvp" > $C; sleep 4; echo "rules add waves false" > $C
./launch.sh local $BOTS >/dev/null
: > results/pvp_samples.log; END=$(( $(date +%s) + MIN*60 )); sleep 20
while [ $(date +%s) -lt $END ]; do
  echo 'js var s=""; Groups.player.each(p=>{s+=p.name+"="+p.team().name+","}); var t2=""; Vars.state.teams.getActive().each(t=>{t2+=t.team.name+":core="+(t.cores.size>0?Math.round(t.cores.first().health):-1)+":blds="+t.buildings.size+":units="+t.units.size+" "}); "PVP "+Math.round(Time.millis()/1000)+" players="+s+" teams="+t2' > $C
  sleep 5
  grep -a "Game over" srvdir/server.log | tail -1 >> results/pvp_samples.log
  grep -aq "Game over" srvdir/server.log && break
done
sed 's/\x1b\[[0-9;]*m//g' srvdir/server.log | grep -a -E "PVP |Game over" | cut -c22- > results/pvp_samples.log
./stop.sh; pkill -f arena-srv; pkill -f "sleep 100000"
python3 pvp_report.py
