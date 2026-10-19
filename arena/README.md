# Mindustry arena (local, official server v160.7)
Same map (Veins), same scripted waves (wave k = k+2 daggers, k/2 maces, k+1 flares, every 60s, first at 150s), one bot per fresh server, sequential.
Run:  ./arena.sh <botname|all> [waves=20] [map=Veins]   -> results/<bot>.json, results/scoreboard.md
Score is measured by the server console, not by the bot: waves survived, core hp (final/min), building count. score = waves*1000 + final core hp.
Add a bot: mkdir bots/<name>; put run.sh there (must exec a client that joins HOST:PORT env, localhost:6567, name $NAME, runs until killed; cwd is a scratch dir; optional $STREAM = port for a live view, $MAXSEC); optional bots/<name>/env with BOTOPTS=...
Bot code zip: unpack under bots/<name>/, write run.sh. Needs the bot to be a headless client of v160.7 (Java or other).

## Launcher for a hosted room
./launch.sh <claj-link|local> bot1 bot2 ...   (names <bot>_<n>, staggered 8s, own dir each, streams :8201+)
./stop.sh   (kills all launched bots)

## PvP free-for-all
./pvp.sh <map=glacier> <minutes> bot1 bot2 [bot3...]  -> results/pvp_scoreboard.md (last team standing wins; else ranked by core hp, with destroy times)
Maps with prebuilt cores per team in v160.7: glacier (2 teams, 150x250), passage/canyon (500x120). Team assignment is automatic by the server.
