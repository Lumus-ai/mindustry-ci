#!/bin/bash
# One command: trigger a matrix and print the summary when done. Needs the GitHub CLI (gh auth login).
# usage: scripts/trigger.sh "ours_v2 ours_v4int" 3 12 15      # bots, runs per bot, waves, seconds between waves
set -e; R=Lumus-ai/mindustry-ci
BOTS=${1:-"ours_v2 ours_v4int"}; RUNS=${2:-3}; WAVES=${3:-12}; WS=${4:-15}
LABEL=fast; [ "$WS" -ge 60 ] && LABEL=real
gh workflow run arena.yml -R $R -f bots="$BOTS" -f runs=$RUNS -f waves=$WAVES -f wave_sec=$WS -f mode_label=$LABEL
sleep 8; ID=$(gh run list -R $R -w arena.yml -L 1 --json databaseId -q '.[0].databaseId')
gh run watch $ID -R $R --exit-status || true
gh run view $ID -R $R --log 2>/dev/null | grep -A12 'Arena summary' | head -20
gh run download $ID -R $R -n summary -D /tmp/arena-$ID && cat /tmp/arena-$ID/summary.md
echo "https://github.com/$R/actions/runs/$ID"
