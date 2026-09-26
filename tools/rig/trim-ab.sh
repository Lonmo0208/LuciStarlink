#!/usr/bin/env bash
# Does the sky-decrease fix (settleSkyWindow, commit 3543718) cost anything on the four cells? Cross-window
# comparison cannot answer that: the pre-fix table and the post-fix table were taken in different windows and SL
# itself moved 4.63 -> 5.54 between them. This interleaves the two BUILDS in one window instead.
#   pre  = the rig jar built from 5a2a30c (worktree /e/LuciStarlin/LS-V2-prefix)
#   post = the rig jar staged by threeway-materialplanes.sh from the current tree
# Both are -Pmod_id=lucistarlinkrig builds of the same engine, so the harness accepts either.
set -uo pipefail
ROOT=/e/LuciStarlin/LuciStarlink
MODS="$ROOT/run-benchmark-scalablelux/mods"
OUT=/e/LuciStarlin/sl-jar/trim-ab; mkdir -p "$OUT"
PRE_JAR=/e/LuciStarlin/sl-jar/ls2-imagelane-rig.jar
POST_JAR=/e/LuciStarlin/sl-jar/ls2-trim-rig.jar
P='-Dlucistarlink.benchmark.prepareRing=8 -Dlucistarlink.benchmark.quiesceSettleMs=1000 -Dlucistarlink.benchmark.globalEngineBarrier=false -Dscalablelux.imageLane=true'
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
RUN_STARTED=$(date +%s); echo "$RUN_STARTED" > "$OUT/run-started"
fresh() { [ -f "$1" ] && [ "$(stat -c %Y "$1" 2>/dev/null)" -ge "$RUN_STARTED" ]; }

kill_strays() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'fml.modFolders|gameDir|run-benchmark' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
  for port in 25665 25666 25667 25668; do
    for pid in $(netstat -ano 2>/dev/null | grep -E ":$port .*LISTENING" | awk '{print $5}' | sort -u); do cmd //c "taskkill /F /PID $pid" >/dev/null 2>&1; done
  done
  sleep 3
}
stage() { # $1 = jar
  local want; want=$(md5sum "$1" | cut -d' ' -f1)
  for attempt in 1 2 3 4 5 6; do
    rm -f "$MODS"/*.jar 2>/dev/null; cp -f "$1" "$MODS/" 2>/dev/null
    [ "$(md5sum "$MODS/$(basename "$1")" 2>/dev/null | cut -d' ' -f1)" = "$want" ] && return 0
    kill_strays
  done
  echo "STAGE-FAILED $1" >&2; return 1
}
run_one() { # $1 = side (pre|post), $2 = workload, $3 = round
  local side=$1 wl=$2 rep=$3 jar
  case "$side" in pre) jar="$PRE_JAR";; post) jar="$POST_JAR";; esac
  kill_strays; rm -rf "$ROOT/run-benchmark-scalablelux/world"* 2>/dev/null
  stage "$jar" || return 1
  local tag; tag="$side-$wl-r$rep"
  local out="$OUT/$tag.jsonl"; local log="$OUT/$tag.log"; local fp=""
  [ "$wl" = structure_cube ] && fp='-PbenchmarkLightFingerprint=-40,32,-40,55,96,55'
  ( cd "$ROOT" && timeout 420 ./gradlew runBenchmarkScalableLuxServer \
      -PbenchmarkWorkload="$wl" -PbenchmarkPasses=3 -PbenchmarkWarmupPasses=2 \
      -PbenchmarkOutput="$out" -PbenchmarkAllowScalableLux=true -PbenchmarkAllowLucis=true \
      -PbenchmarkExpectedMod=lucistarlinkrig $fp "-PslArgs=$P" \
      -x prepareBenchmarkScalableLuxMods --console=plain ) > "$log" 2>&1
  local mp; mp=$(fresh "$out" && tail -1 "$out" | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/')
  printf "  %-4s %-20s r%s minPass=%-9s rc=%s\n" "$side" "$wl" "$rep" "${mp:-NONE}" "$?"
}
for rep in 1 2; do
  for wl in block_toggle_border structure_cube dense_chunk_patch sky_hole; do
    if [ $(( rep % 2 )) -eq 1 ]; then run_one pre "$wl" "$rep"; run_one post "$wl" "$rep"; else run_one post "$wl" "$rep"; run_one pre "$wl" "$rep"; fi
  done
done
echo ""
echo "=== 同窗口交错（minPass 取 min-of-2；括号内是 applyOnly 每改动 ns 的 min-of-2，那才是这次要动的相位）==="
printf "  %-22s %24s %24s %10s\n" workload pre post pre/post
for wl in block_toggle_border structure_cube dense_chunk_patch sky_hole; do
  line=$(printf "  %-22s" "$wl"); meds=""
  for side in pre post; do
    vals=""; for rep in 1 2; do f="$OUT/$side-$wl-r$rep.jsonl"; if fresh "$f"; then v=$(tail -1 "$f" | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/'); [ -n "$v" ] && vals="$vals $v"; fi; done
    med=$(echo $vals | tr ' ' '\n' | grep -v '^$' | sort -n | awk '{a[NR]=$1} END{if(NR==0){print "0"}else{printf "%.2f",a[int((NR+1)/2)]/1e6}}')
    avals=""; for rep in 1 2; do f="$OUT/$side-$wl-r$rep.jsonl"; if fresh "$f"; then v=$(tail -1 "$f" | sed 's/.*"nsPerChangeApplyOnly":\([0-9.]*\).*/\1/'); [ -n "$v" ] && avals="$avals $v"; fi; done
    amed=$(echo $avals | tr ' ' '\n' | grep -v '^$' | sort -n | awk '{a[NR]=$1} END{if(NR==0){print "?"}else{printf "%.0f",a[int((NR+1)/2)]}}')
    line+=$(printf "%24s" "$med (applyOnly $amed)"); meds="$meds $med"
  done
  ratio=$(echo $meds | awk '{if ($2 > 0) printf "%.3f", $1/$2; else print "?"}')
  echo "$line$(printf "%10s" "$ratio")"
done
echo ""; echo "=== 新鲜度审计（16 应有）==="
n=0; for side in pre post; do for wl in block_toggle_border structure_cube dense_chunk_patch sky_hole; do for rep in 1 2; do fresh "$OUT/$side-$wl-r$rep.jsonl" && n=$((n+1)); done; done; done
echo "  $n/16"
