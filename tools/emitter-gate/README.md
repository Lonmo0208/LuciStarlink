# The emitter gate

The check that the first defect reported from play would have needed (`docs/BUG-EMITTER-BLOCK-LIGHT.md`): a **light
source** must light up, and must still be lit after a save and a reload.

It exists because the four-workload acceptance could not see this class of bug: its only gated workload is
`structure_cube`, whose changes are all non-emitters (273 block-light cells out of 599,040), so nothing in the gate
ever placed a light.

## How it works, and why in this shape

A dedicated server in its own run directory (`./gradlew runServerDiag`, `run-diag/`, port 25690) plus a **datapack**
that drives the whole protocol: `minecraft:load` schedules the first step 45 seconds out, by which time spawn
generation is finished and every chunk in reach is past `LIGHT`. Chat output inside functions is suppressed, so the
mod's `light` command also logs its reading.

Two lessons are baked in, both learned the hard way:

* **No piped console commands.** Feeding several commands into `./gradlew runServer` over stdin loses commands in the
  middle of a sequence without any error; `schedule` inside a datapack is deterministic.
* **A client and a server cannot share `run/`.** Two live JVMs fight over `run/logs/latest.log` and the second one
  dies at startup with an error that looks like a mod failure. Hence `run-diag/`.

## Running it

```bash
bash tools/emitter-gate/emitter-gate.sh          # place -> save -> stop -> restart -> read (the reported scenario)
bash tools/emitter-gate/emitter-gate-single.sh   # one boot: place, then read after 5 seconds
```

Both scripts generate the datapack themselves, so this directory has no state; the world lives in `run-diag/`, which
is git-ignored.

**Pass criteria**

* single boot: the cell reads `block=15` after the 5-second settle (and `updBlock=15`, the engine's own layer);
* two boots: the same reading after the restart — this is the one that failed in 2.0.1;
* `-Dscalablelux.enabled=false` (vanilla engine) reads `block=15` in both, which is the control that proves the test
  itself is sound.
