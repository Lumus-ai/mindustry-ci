# mindustry-ci

Parallel test matrix for our own Mindustry bots (official server v160.7, downloaded at CI time; no game files in this repo). One bot per job, fresh server per job, scripted waves, per-run artifacts and an aggregate table.

Bots: ours_v2, ours_v3, ours_v4int and four flag-profile variants of v4int (prof_*). Our own code only.

Run from the Actions tab (workflow "arena", Run workflow) or: `scripts/trigger.sh "ours_v2 ours_v4int" 3 12 15`.
Inputs: bots, runs per bot, waves, wave_sec (60 = real-time, 15 = fast), first_wave, map.
Results: job summary table + artifacts (result json, samples.csv, bot.log, server.log, telemetry.jsonl).

Fast mode only shortens the scripted wave interval. The bot gets less game time between waves, so fast results are a stricter test, not equal to real-time results. Compare modes on the same bot before trusting fast numbers.
Local run: see arena/README.md (needs JDK 17 + 25).

## Validity warning
Results before the harness fix (stop on first server `Game over`) are invalid for runs where the core died. See RESULTS_INVALID.md. Primary metric now: wave of death; duos/scatters at each wave start are recorded.
