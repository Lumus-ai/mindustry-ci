#!/bin/bash
# Fetch official Mindustry server v160.7 and compile our bots (v2, v3, v4/int). Needs JDK 17 (server) and JDK 25 (bots).
set -e; cd "$(dirname "$0")"; source ./env.sh
if [ ! -f server.jar ]; then
  curl -fL --retry 3 -o server.jar https://github.com/Anuken/Mindustry/releases/download/v160.7/server-release.jar
fi
echo "server.jar sha256: $(sha256sum server.jar | cut -d' ' -f1)"
for v in v2 v3 v4/int v4/sus v5 v5c v5d v5e v5f v5h v5j v5k v5l v5m v5n v5p v5q; do [ -d $v/src ] || continue
  mkdir -p $v/cls
  $JAVAC25 -nowarn -cp server.jar -d $v/cls $v/src/*.java 2>&1 | tee /tmp/javac-$$.log | grep -E "error" && { cat /tmp/javac-$$.log; exit 1; } || true
  echo "built $v"
done
mkdir -p arena/results arena/run
echo "setup ok"
