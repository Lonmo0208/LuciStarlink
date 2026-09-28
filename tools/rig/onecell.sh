#!/usr/bin/env bash
# One benchmark cell, one side, with the engine's phase table on. Usage:
#   onecell.sh <workload> <label> "<extra -D flags>" [round]
# Prints minPass + the harness's player-wall line + the SLPROF phase line for the last profiler window.
set -uo pipefail
ROOT=/e/LuciStarlin/LuciStarlink
MODS="$ROOT/run-benchmark-scalablelux/mods"
OUT=/e/LuciStarlin/sl-jar/onecell; mkdir -p "$OUT"
JAR=/e/LuciStarlin/sl-jar/ls2-skysource-rig.jar
WL=${1:?workload}
LABEL=${2:?label}
EXTRA=${3:-}
REP=${4:-1}
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
P='-Dlucistarlink.benchmark.prepareRing=8 -Dlucistarlink.benchmark.quiesceSettleMs=1000 -Dlucistarlink.benchmark.globalEngineBarrier=false -Dscalablelux.profile=true'

kill_strays() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'gameDir|fml.modFolders|run-benchmark' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
  for port in 25665 25666 25667 25668; do
    for pid in $(netstat -ano 2>/dev/null | grep -E ":$port .*LISTENING" | awk '{print $5}' | sort -u); do cmd //c "taskkill /F /PID $pid" >/dev/null 2>&1; done
  done
  sleep 3
}
stage() { local want; want=$(md5sum "$JAR" | cut -d' ' -f1)
  for a in 1 2 3 4 5 6; do
    rm -f "$MODS"/*.jar 2>/dev/null; cp -f "$JAR" "$MODS/" 2>/dev/null
    [ "$(md5sum "$MODS/$(basename "$JAR")" 2>/dev/null | cut -d' ' -f1)" = "$want" ] && return 0
    kill_strays
  done
  echo STAGE-FAILED >&2; return 1; }

kill_strays; rm -rf "$ROOT/run-benchmark-scalablelux/world"* 2>/dev/null
stage || exit 1
tag="$LABEL-$WL-r$REP"; out="$OUT/$tag.jsonl"; log="$OUT/$tag.log"
( cd "$ROOT" && timeout 420 ./gradlew runBenchmarkScalableLuxServer \
    -PbenchmarkWorkload="$WL" -PbenchmarkPasses=3 -PbenchmarkWarmupPasses=2 \
    -PbenchmarkOutput="$out" -PbenchmarkAllowScalableLux=true -PbenchmarkAllowLucis=true \
    -PbenchmarkExpectedMod=lucistarlinkrig "-PslArgs=$P $EXTRA" \
    -x prepareBenchmarkScalableLuxMods --console=plain ) > "$log" 2>&1
rc=$?
mp=$(tail -1 "$out" 2>/dev/null | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/')
wall=$(grep -a "bench.pass_wall_actual" "$log" | tail -1 | sed 's/.*total_ms=//')
echo "== $tag minPass=$(awk -v t="$mp" 'BEGIN{if(t)printf "%.2f",t/1e6}')ms wall=$wall rc=$rc"
echo "--- phase table (last window) ---"
grep -a "SLPROF" "$log" | tail -1 | tr ' ' '\n' | grep -E "matzNanos|extNanos|bfsNanos|packNanos|laneSettleNanos|laneCaptureNanos|laneRegionCreat|lanePops|recomputeNanos|settleNanos|blkDecNanos|blkNanos|skyNanos|publishSections|laneSettleRuns|ownEditNanos|matzSecs" | tr '\n' ' '
echo
