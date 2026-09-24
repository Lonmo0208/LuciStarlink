#!/usr/bin/env bash
# Four cells, two interleaved rounds, our 2.0.8 candidate vs stock ScalableLux. Same protocol as the 2.0.7 table.
set -uo pipefail
ROOT=/e/LuciStarlin/LuciStarlink
MODS="$ROOT/run-benchmark-scalablelux/mods"
OUT=/e/LuciStarlin/sl-jar/final208; mkdir -p "$OUT"
US=/e/LuciStarlin/sl-jar/ls2-dispatch-rig.jar
SL=/e/LuciStarlin/ScalableLux-neoforge-build/ScalableLux-Master/build/libs/ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
P='-Dlucistarlink.benchmark.prepareRing=8 -Dlucistarlink.benchmark.quiesceSettleMs=1000 -Dlucistarlink.benchmark.globalEngineBarrier=false'
one() {
  local label="$1" jar="$2" mod="$3" wl="$4"
  for pid in $(netstat -ano | grep -E ":25666 .*LISTENING" | awk '{print $5}' | sort -u); do cmd //c "taskkill /F /PID $pid" >/dev/null 2>&1; done
  sleep 1; rm -rf "$ROOT/run-benchmark-scalablelux"/world* 2>/dev/null
  rm -f "$MODS"/*.jar; cp -f "$jar" "$MODS/"
  local fp=""; [ "$wl" = structure_cube ] && fp='-PbenchmarkLightFingerprint=-40,32,-40,55,96,55'
  ( cd "$ROOT" && timeout 400 ./gradlew runBenchmarkScalableLuxServer -PbenchmarkWorkload="$wl" \
      -PbenchmarkPasses=3 -PbenchmarkWarmupPasses=2 -PbenchmarkOutput="$OUT/$label.jsonl" \
      -PbenchmarkAllowScalableLux=true -PbenchmarkAllowLucis=true -PbenchmarkExpectedMod="$mod" $fp \
      "-PslArgs=$P" -x prepareBenchmarkScalableLuxMods --console=plain ) > "$OUT/$label.log" 2>&1
  local j=$(tail -1 "$OUT/$label.jsonl" 2>/dev/null)
  printf "%-16s min=%-9s mean=%-8s apply=%-8s wait=%-8s %s\n" "$label" \
    "$(echo "$j" | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/')" \
    "$(echo "$j" | sed 's/.*"msPerMeasuredPass":\([0-9.]*\).*/\1/')" \
    "$(echo "$j" | sed 's/.*"applyMillis":\([0-9.]*\).*/\1/')" \
    "$(echo "$j" | sed 's/.*"waitMillis":\([0-9.]*\).*/\1/')" \
    "$(grep -a 'fingerprint box=' "$OUT/$label.log" | tail -1 | sed 's/.*cells=//' | cut -c1-46)"
}
for r in 1 2; do
  for wl in structure_cube dense_chunk_patch sky_hole block_toggle_border; do
    one "r${r}-us-${wl}" "$US" lucistarlinkrig "$wl"
    one "r${r}-sl-${wl}" "$SL" scalablelux "$wl"
  done
done
