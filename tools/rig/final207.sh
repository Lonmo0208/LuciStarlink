#!/usr/bin/env bash
set -uo pipefail
ROOT=/e/LuciStarlin/LuciStarlink
MODS="$ROOT/run-benchmark-scalablelux/mods"
OUT=/e/LuciStarlin/sl-jar/final207; mkdir -p "$OUT"
US=/e/LuciStarlin/sl-jar/ls2-varA-rig.jar
SL=/e/LuciStarlin/ScalableLux-neoforge-build/ScalableLux-Master/build/libs/ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
one() {
  for pid in $(netstat -ano | grep -E ":25666 .*LISTENING" | awk '{print $5}' | sort -u); do cmd //c "taskkill /F /PID $pid" >/dev/null 2>&1; done
  sleep 2; rm -rf "$ROOT/run-benchmark-scalablelux"/world* 2>/dev/null
  rm -f "$MODS"/*.jar; cp -f "$2" "$MODS/"
  local P="-Dlucistarlink.benchmark.prepareRing=8 -Dlucistarlink.benchmark.quiesceSettleMs=1000 -Dlucistarlink.benchmark.globalEngineBarrier=false"
  local fp=""; [ "$4" = structure_cube ] && fp='-PbenchmarkLightFingerprint=-40,32,-40,55,96,55'
  ( cd "$ROOT" && timeout 400 ./gradlew runBenchmarkScalableLuxServer -PbenchmarkWorkload="$4" \
      -PbenchmarkPasses=3 -PbenchmarkWarmupPasses=2 -PbenchmarkOutput="$OUT/$1.jsonl" \
      -PbenchmarkAllowScalableLux=true -PbenchmarkAllowLucis=true -PbenchmarkExpectedMod="$3" $fp \
      "-PslArgs=$P" -x prepareBenchmarkScalableLuxMods --console=plain ) > "$OUT/$1.log" 2>&1
  printf "  %-6s %-20s minPass=%-9s %s\n" "$1" "$4" "$(tail -1 "$OUT/$1.jsonl" 2>/dev/null | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/')" "$(grep -a 'fingerprint box=' "$OUT/$1.log" | tail -1 | sed 's/.*cells=//' | cut -c1-40)"
}
for wl in structure_cube dense_chunk_patch sky_hole block_toggle_border; do
  one "us" "$US" lucistarlinkrig "$wl"
  one "sl" "$SL" scalablelux "$wl"
done
