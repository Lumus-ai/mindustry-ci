#!/bin/bash
source "$(cd "$(dirname "$0")/../../.." && pwd)/env.sh"
rm -rf config
exec $J25 -Dbrain=1 -Dstyle=balanced -Ddiff=normal ${CLAJ:+-Dclaj=$CLAJ} -Dport=$PORT -Dpname=$NAME -Dstream=$STREAM -Dmaxsec=$MAXSEC $BOTOPTS -cp $MB/server.jar:$MB/v5k/cls:$MB/claj Main
