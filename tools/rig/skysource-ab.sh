#!/usr/bin/env bash
# Does sourcing the deferred sky window from the region image (slice 2) pay on the cells we lose to 1.x?
#   base = the V2 rig with -Dscalablelux.imageLane=true only
#   src  = the same jar with -Dscalablelux.imageLaneSky=true added
# Both sides are the SAME jar, so the only difference is the flag: the window routine is identical, only where its
# material and light arrays come from changes. The structure_cube fingerprint must be identical between sides.
set -uo pipefail
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
ROOT=/e/LuciStarlin/LuciStarlink
V2=/e/LuciStarlin/LuciStarlink-LS-V2
MODS="$ROOT/run-benchmark-scalablelux/mods"
OUT=/e/LuciStarlin/sl-jar/skysource-ab; mkdir -p "$OUT"
JAR=/e/LuciStarlin/sl-jar/ls2-skysource-rig.jar
P='-Dlucistarlink.benchmark.prepareRing=8 -Dlucistarlink.benchmark.quiesceSettleMs=1000 -Dlucistarlink.benchmark.globalEngineBarrier=false -Dscalablelux.imageLane=true'

RUN_STARTED=$(date +%s); echo "$RUN_STARTED" > "$OUT/run-started"
fresh() { [ -f "$1" ] && [ "$(stat -c %Y "$1" 2>/dev/null)" -ge "$RUN_STARTED" ]; }

kill_strays() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'fml.modFolders|gameDir|run-benchmark' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
  for port in 25665 25666 25667 25668; do
    for pid in $(netstat -ano 2>/dev/null | grep -E ":$port .*LISTENING" | awk '{print $5}' | sort -u); do cmd //c "taskkill /F /PID $pid" >/dev/null 2>&1; done
  done
  sleep 3
}

stage() {
  local want; want=$(md5sum "$1" | cut -d' ' -f1)
  for attempt in 1 2 3 4 5 6; do
    rm -f "$MODS"/*.jar 2>/dev/null; cp -f "$1" "$MODS/" 2>/dev/null
    [ "$(md5sum "$MODS/$(basename "$1")" 2>/dev/null | cut -d' ' -f1)" = "$want" ] && return 0
    kill_strays
  done
  echo "STAGE-FAILED $1" >&2; return 1
}

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  ( cd "$V2" && timeout 900 ./gradlew build -Pmod_id=lucistarlinkrig -x test --console=plain ) > "$OUT/rig-build.log" 2>&1 \
    || { echo "RIG-BUILD-FAILED, see $OUT/rig-build.log" >&2; exit 1; }
  built=$(ls "$V2"/build/libs/lucistarlink-1.21.1-*-all.jar 2>/dev/null | grep -v -- "-dev.jar" | head -1)
  id=$(unzip -p "$built" META-INF/neoforge.mods.toml 2>/dev/null | grep -m1 '^modId')
  case "$id" in *lucistarlinkrig*) ;; *) echo "RIG-BUILD-WRONG-MOD-ID: $id" >&2; exit 1;; esac
  cp -f "$built" "$JAR"
  echo "rig jar: $JAR md5=$(md5sum "$JAR" | cut -d' ' -f1) ($id)"
fi
[ -f "$JAR" ] || { echo "no jar at $JAR" >&2; exit 1; }

run_one() { # $1 = side, $2 = workload, $3 = round
  local side=$1 wl=$2 rep=$3 extra="$P"
  [ "$side" = src ] && extra="$P -Dscalablelux.imageLaneSky=true"
  kill_strays; rm -rf "$ROOT/run-benchmark-scalablelux/world"* 2>/dev/null
  stage "$JAR" || return 1
  local tag; tag="$side-$wl-r$rep"
  local out="$OUT/$tag.jsonl"; local log="$OUT/$tag.log"; local fp=""
  [ "$wl" = structure_cube ] && fp='-PbenchmarkLightFingerprint=-40,32,-40,55,96,55'
  ( cd "$ROOT" && timeout 420 ./gradlew runBenchmarkScalableLuxServer \
      -PbenchmarkWorkload="$wl" -PbenchmarkPasses=3 -PbenchmarkWarmupPasses=2 \
      -PbenchmarkOutput="$out" -PbenchmarkAllowScalableLux=true -PbenchmarkAllowLucis=true \
      -PbenchmarkExpectedMod=lucistarlinkrig $fp "-PslArgs=$extra" \
      -x prepareBenchmarkScalableLuxMods --console=plain ) > "$log" 2>&1
  local rc=$? mp hash rec
  mp=$(fresh "$out" && tail -1 "$out" 2>/dev/null | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/')
  hash=$(grep -a "Lux light fingerprint box=" "$log" | tail -1 | sed 's/.*cells=[0-9]* //' | cut -c1-24)
  rec=$(grep -ao "recomputes=[0-9]* recomputeNanos=[0-9]*" "$log" | tail -1)
  printf "  %-4s %-20s r%s minPass=%-9s %s %s rc=%s\n" "$side" "$wl" "$rep" "${mp:-NONE}" "${rec:-norec}" "$hash" "$rc"
}

for rep in 1 2 3; do
  for wl in ${WORKLOADS:-block_toggle_border structure_cube dense_chunk_patch}; do
    if [ $(( (rep - 1) % 2 )) -eq 0 ]; then run_one base "$wl" "$rep"; run_one src "$wl" "$rep"
    else run_one src "$wl" "$rep"; run_one base "$wl" "$rep"; fi
  done
done
echo ""
echo "=== 同窗口交错：minPass (ms) / recompute (ms) / 指纹 ==="
for wl in ${WORKLOADS:-block_toggle_border structure_cube dense_chunk_patch}; do
  printf "  %-20s" "$wl"
  for side in base src; do
    vals=$(for r in 1 2 3; do tail -1 "$OUT/$side-$wl-r$r.jsonl" 2>/dev/null | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/'; done | sort -n | head -2 | awk '{s+=$1} END {if (NR>0) printf "%.2f", s/NR/1e6; else printf "NA"}')
    printf " %s=%-8s" "$side" "$vals"
  done
  b=$(for r in 1 2 3; do tail -1 "$OUT/base-$wl-r$r.jsonl" 2>/dev/null | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/'; done | sort -n | head -2 | awk '{s+=$1} END {if (NR>0) printf "%.0f", s/NR}')
  s=$(for r in 1 2 3; do tail -1 "$OUT/src-$wl-r$r.jsonl" 2>/dev/null | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/'; done | sort -n | head -2 | awk '{s+=$1} END {if (NR>0) printf "%.0f", s/NR}')
  [ -n "$b" ] && [ -n "$s" ] && awk -v b="$b" -v s="$s" 'BEGIN{printf " ratio=%.3f", s/b}'
  echo ""
done
