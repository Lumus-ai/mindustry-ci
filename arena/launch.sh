#!/bin/bash
source "$(cd "$(dirname "$0")/.." && pwd)/env.sh"
# usage: ./launch.sh <claj-link|local> <bot1> [bot2 ...]   (a bot may repeat: ours_v2 ours_v2)
# Starts each bot in its own dir with a distinct name (<bot>-<n>), staggered 8s. Stop: ./stop.sh
cd $MB/arena; LINK=$1; shift; mkdir -p live; : > live/pids
n=0
for b in "$@"; do
  n=$((n+1)); name="${b}_$n"; W=live/$name; rm -rf $W; mkdir -p $W
  BOTOPTS=""; [ -f bots/$b/env ] && source bots/$b/env
  CJ=""; [ "$LINK" != local ] && CJ=$LINK
  (cd $W; export BOTOPTS HOST=localhost PORT=6567 NAME=$name STREAM=$((8200+n)) MAXSEC=36000 CLAJ=$CJ; setsid nohup $MB/arena/bots/$b/run.sh > bot.log 2>&1 & echo $! >> $MB/arena/live/pids)
  echo "started $name (stream :$((8200+n)))"; sleep 8
done
