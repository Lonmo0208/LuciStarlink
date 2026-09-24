# The measurement rig, as it was used for the 2.0.7 table

These are the five shell scripts that were actually run from `%TEMP%` during the 2.0.2 → 2.0.7 window. They are archived
here because `%TEMP%` gets cleaned and the numbers in `docs/HANDOVER.md` are only reproducible while the scripts exist.
They are **hard-coded to this machine** (`/e/LuciStarlin/...`, the JDK under `.gradle/jdks`, ports 25665–25668) — read
each one's first ten lines and fix the paths before running it somewhere else.

Nothing here is part of the mod's build. The two engines are run from the rig tree
`E:\LuciStarlin\LuciStarlink` (the benchmark harness), not from this worktree; a jar has to be built here first
(`./gradlew build -Pmod_id=lucistarlinkrig -x test`) and copied into `sl-jar/`.

| script | what it does | when |
|---|---|---|
| `final207.sh` | **The one that produced the 2.0.7 table.** Four workloads × two engines (us / ScalableLux), one after the other: kills strays, wipes the run world, drops one jar into the rig's `mods/`, runs `runBenchmarkScalableLuxServer` with the accepted protocol (`prepareRing=8`, `quiesceSettleMs=1000`, `globalEngineBarrier=false`, 3 passes / 2 warmup), prints `minPass` and the fingerprint | reproducing the four-cell table; `US` is already `ls2-varA-rig.jar` |
| `threeway-engines.sh` | Same shape but three engines (`us20` / `sl` / `ls1`, the last one from the other worktree), plus CPU load sampling per run and a stray-process reaper; writes `load-samples.txt` next to the results | when a table must include the 1.x line; point `US20_JAR` at the new rig jar and change `OUT` (it still points at the 2.0.6 run) |
| `gate-gradient.sh` | Correctness gate on a copy of the player's world: glowstone across a chunk border plus repeated border edits, then a per-cell dump. Expect the gradient `15/14/14/14/13/10`. Runs the diagnostic server (`run-diag/`, port 25690) | after any change to the edit lane, the sky strategy or the flush |
| `repro-chunk.sh` | Fills and clears 8192 blocks in one chunk on a copy of the player's save and dumps A/B — the shape that exposed the null-position-set NPE | when a bulk edit's block light is suspect |
| `lightdump-residue.sh` | Dumps a region, forces a full relight, dumps again, and reports the difference — which is exactly the light that was wrong in the save | when a save is suspected of holding stale light |

Protocol notes that cost real time to learn, all of them encoded in these scripts: the pass must be allowed to settle
(`quiesceSettleMs`) or the engine queue means something else at the moment it is read; the harness validates
`-PbenchmarkExpectedMod`, so a jar can never be measured under another engine's name; the world must be wiped between
engines or the second engine inherits the first one's chunks; and the box criterion
(`globalEngineBarrier=false`) is not the same metric as the global barrier — tables from before it are not comparable.
