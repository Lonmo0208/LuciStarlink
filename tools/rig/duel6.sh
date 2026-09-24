#!/usr/bin/env bash
# Six interleaved rounds of us vs ScalableLux on named workloads. The project's own rule: these cells swing with the
# machine, so a verdict needs several rounds - two rounds have twice told me a cell was won when six say it is a tie.
#   bash duel6.sh block_toggle_border dense_chunk_patch
set -uo pipefail
ROOT=/e/LuciStarlin/LuciStarlink
MODS="$ROOT/run-benchmark-scalablelux/mods"
OUT=/e/LuciStarlin/sl-jar/duel6; mkdir -p "$OUT"
US=${US:-/e/LuciStarlin/sl-jar/ls2-dispatch-rig.jar}   # == the v2.0.8 release engine (class-for-class verified)
SL=${SL:-/e/LuciStarlin/ScalableLux-neoforge-build/ScalableLux-Master/build/libs/ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar}
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
P='-Dlucistarlink.benchmark.prepareRing=8 -Dlucistarlink.benchmark.quiesceSettleMs=1000 -Dlucistarlink.benchmark.globalEngineBarrier=false'
one() {
  local label="$1" jar="$2" mod="$3" wl="$4"
  for pid in $(netstat -ano | grep -E ":25666 .*LISTENING" | awk '{print $5}' | sort -u); do cmd //c "taskkill /F /PID $pid" >/dev/null 2>&1; done
  sleep 1; rm -rf "$ROOT/run-benchmark-scalablelux"/world* 2>/dev/null
  rm -f "$MODS"/*.jar; cp -f "$jar" "$MODS/"
  ( cd "$ROOT" && timeout 400 ./gradlew runBenchmarkScalableLuxServer -PbenchmarkWorkload="$wl" \
      -PbenchmarkPasses=3 -PbenchmarkWarmupPasses=2 -PbenchmarkOutput="$OUT/$label.jsonl" \
      -PbenchmarkAllowScalableLux=true -PbenchmarkAllowLucis=true -PbenchmarkExpectedMod="$mod" \
      "-PslArgs=$P" -x prepareBenchmarkScalableLuxMods --console=plain ) > "$OUT/$label.log" 2>&1
  local j=$(tail -1 "$OUT/$label.jsonl" 2>/dev/null)
  printf "%-20s %-3s min=%-8s mean=%-8s wall=%-8s\n" "$wl" "$label" \
    "$(echo "$j" | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/')" \
    "$(echo "$j" | sed 's/.*"msPerMeasuredPass":\([0-9.]*\).*/\1/')" \
    "$(grep -a 'pass_wall_actual' "$OUT/$label.log" | tail -1 | sed 's/.*total_ms=\([0-9.]*\).*/\1/')"
}
for wl in "$@"; do
  echo "=== $wl ==="
  for r in 1 2 3 4 5 6; do
    one "${wl}-r${r}us" "$US" lucistarlinkrig "$wl"
    one "${wl}-r${r}sl" "$SL" scalablelux "$wl"
  done
done
