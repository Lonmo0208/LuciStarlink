#!/usr/bin/env bash
# border only, four runs, interleaved: our 2.0.8 candidate vs stock ScalableLux, two rounds. Nothing else may run.
set -uo pipefail
ROOT=/e/LuciStarlin/LuciStarlink
MODS="$ROOT/run-benchmark-scalablelux/mods"
OUT=/e/LuciStarlin/sl-jar/border2; mkdir -p "$OUT"
US=/e/LuciStarlin/sl-jar/ls2-dispatch-rig.jar
SL=/e/LuciStarlin/ScalableLux-neoforge-build/ScalableLux-Master/build/libs/ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar
WL=block_toggle_border
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
BASE='-Dlucistarlink.benchmark.prepareRing=8 -Dlucistarlink.benchmark.quiesceSettleMs=1000 -Dlucistarlink.benchmark.globalEngineBarrier=false'
one() {
  local label="$1" jar="$2" mod="$3"
  for pid in $(netstat -ano | grep -E ":25666 .*LISTENING" | awk '{print $5}' | sort -u); do cmd //c "taskkill /F /PID $pid" >/dev/null 2>&1; done
  sleep 1; rm -rf "$ROOT/run-benchmark-scalablelux"/world* 2>/dev/null
  rm -f "$MODS"/*.jar; cp -f "$jar" "$MODS/"
  ( cd "$ROOT" && timeout 400 ./gradlew runBenchmarkScalableLuxServer -PbenchmarkWorkload="$WL" \
      -PbenchmarkPasses=3 -PbenchmarkWarmupPasses=2 -PbenchmarkOutput="$OUT/$label.jsonl" \
      -PbenchmarkAllowScalableLux=true -PbenchmarkAllowLucis=true -PbenchmarkExpectedMod="$mod" \
      "-PslArgs=$BASE" -x prepareBenchmarkScalableLuxMods --console=plain ) > "$OUT/$label.log" 2>&1
  local j=$(tail -1 "$OUT/$label.jsonl" 2>/dev/null)
  printf "%-8s min=%-8s mean=%-8s apply=%-7s wait=%-7s max=%-9s\n" "$label" \
    "$(echo "$j" | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/')" \
    "$(echo "$j" | sed 's/.*"msPerMeasuredPass":\([0-9.]*\).*/\1/')" \
    "$(echo "$j" | sed 's/.*"applyMillis":\([0-9.]*\).*/\1/')" \
    "$(echo "$j" | sed 's/.*"waitMillis":\([0-9.]*\).*/\1/')" \
    "$(echo "$j" | sed 's/.*"maxPassNanos":\([0-9]*\).*/\1/')"
}
printf "%-8s %-8s %-8s %-7s %-7s %-9s\n" config minPass meanMs applyMs waitMs maxPass
one us1 "$US" lucistarlinkrig
one sl1 "$SL" scalablelux
one sl2 "$SL" scalablelux
one us2 "$US" lucistarlinkrig
