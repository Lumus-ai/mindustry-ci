#!/bin/bash
source "$(cd "$(dirname "$0")/../../.." && pwd)/env.sh"
# contract: env HOST PORT NAME STREAM MAXSEC ; cwd = a fresh scratch dir. Must join as a client and play until killed.
rm -rf config
exec $J25 $BOTOPTS -Dbrain=1 -Dstyle=balanced -Ddiff=normal ${CLAJ:+-Dclaj=$CLAJ} -Dport=$PORT -Dpname=$NAME -Dstream=$STREAM -Dmaxsec=$MAXSEC -cp $MB/server.jar:$MB/v3/cls:$MB/claj Main
