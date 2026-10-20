# Invalid results (do not use)

Runs made before commit "harness: stop on game over" are wrong for any test where the core died.
Bug: after the core is destroyed, the server prints `Game over! Team crux is victorious ... map Veins` and loads the next map (Maze, Triad, Mud...). The old arena.sh kept sampling the NEW map's core, which is alive, so dead runs were recorded as "survived 12 waves". The odd core hp values (1100, 3500, 4500, 6000) were the max hp of other maps' cores.

Affected runs (every run in them had a Veins Game over unless noted):
- fast 15s, 12 waves: run 39341599027 (21/21 died)
- 30s hard: runs 39344031462, 39346408753, 39347568715 (all died at wave 3, about 160s after bot connect)
- real-time 60s: run 39341601280 (valid, no Game over: v2 3/3, v4int 3/3 survived); run 39347580782 (v4sus: 2 of 5 actually died in wave 12, runs 2 and 5)
- smoke run 39341253751 (2 waves; valid)

Valid metric now: `core_destroyed` / `death_wave` (first Game over on the test map).
