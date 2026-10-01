#!/usr/bin/env bash
# 四路同窗口性能对比（2026-09-30）：
#   us   = 当前树（含 2.0.17 的全部修复 + 伙伴合并的工具）的 rig jar
#   prev = 修复前的 2.0 构建（ls2-skysource-rig.jar，md5 120d5a19…）—— 回答"修 bug 有没有把性能改回去"
#   sl   = 原版 ScalableLux 0.3.0-alpha.0.8
#   ls1  = 1.x 线（Lucis）worktree 的 dev 构建
# 4 侧 × 4 格 × 3 轮，顺序每轮轮转；判据：引擎口径 minPassNanos + 玩家口径 bench.pass_wall_actual + 指纹。
set -uo pipefail
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
ROOT=/e/LuciStarlin/LuciStarlink
V2=/e/LuciStarlin/LuciStarlink-LS-V2
MODS="$ROOT/run-benchmark-scalablelux/mods"
OUT=/e/LuciStarlin/sl-jar/fourway; mkdir -p "$OUT"
US_JAR=/e/LuciStarlin/sl-jar/ls2-nightr2-rig.jar
PREV_JAR=/e/LuciStarlin/sl-jar/ls2-premerge-rig.jar
SL_JAR=/e/LuciStarlin/ScalableLux-neoforge-build/ScalableLux-Master/build/libs/ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar
P='-Dlucistarlink.benchmark.prepareRing=8 -Dlucistarlink.benchmark.quiesceSettleMs=1000 -Dlucistarlink.benchmark.globalEngineBarrier=false'
P_US="$P -Dscalablelux.imageLane=true"

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
  for a in 1 2 3 4 5 6; do
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
  # content guard (2026-09-30): a stale jar in build/libs was once staged as the CURRENT build and made a whole
  # four-way column identical to the pre-fix baseline; verify a marker that only the current source produces
  hits=$(unzip -p "$built" ca/spottedleaf/starlight/common/command/LuciStarlinkCommand.class 2>/dev/null | grep -ac layerdump)
  [ "$hits" -ge 1 ] || { echo "RIG-BUILD-STALE: $built has no layerdump marker (not the current source)" >&2; exit 1; }
  cp -f "" ""
  echo "us jar: $(md5sum "$US_JAR" | cut -d' ' -f1) ($id)"
  echo "prev jar: $(md5sum "$PREV_JAR" | cut -d' ' -f1)"
fi

cpu() { powershell -NoProfile -Command "(Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 3).CounterSamples.CookedValue | Measure-Object -Average | Select-Object -ExpandProperty Average" 2>/dev/null | tr -d '\r'; }
( while true; do echo "$(date '+%H:%M:%S') cpu=$(cpu)%" >> "$OUT/load-samples.txt"; sleep 120; done ) & SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null' EXIT

run_one() { # $1 = side, $2 = workload, $3 = round
  local side=$1 wl=$2 rep=$3 task dir mod jar extra
  case "$side" in
    us)   task=runBenchmarkScalableLuxServer; dir="$ROOT/run-benchmark-scalablelux"; mod=lucistarlinkrig; jar="$US_JAR";   extra="$P_US";;
    usoff) task=runBenchmarkScalableLuxServer; dir="$ROOT/run-benchmark-scalablelux"; mod=lucistarlinkrig; jar="$US_JAR"; extra="$P_US -Dscalablelux.recomputeInlineMaxChunks=9999";;
    sl)   task=runBenchmarkScalableLuxServer; dir="$ROOT/run-benchmark-scalablelux"; mod=scalablelux;      jar="$SL_JAR";   extra="$P";;
    ls1)  task=runBenchmarkServer;             dir="$ROOT/run-benchmark-lucistarlink"; mod=lucistarlink;   jar="";          extra="$P";;
  esac
  kill_strays
  rm -rf "$dir"/world* 2>/dev/null
  [ -n "$jar" ] && stage "$jar"
  local tag; tag="$side-$wl-r$rep"
  local out="$OUT/$tag.jsonl"; local log="$OUT/$tag.log"
  local fp=""; [ "$wl" = structure_cube ] && fp='-PbenchmarkLightFingerprint=-40,32,-40,55,96,55'
  local rc=0 mp="" attempt
  # one retry per run: the 2026-09-30 window lost 25 of 48 runs to a loaded machine (rc=-1 / empty result), and a
  # retry is what turns that into a usable table instead of a half-empty one
  for attempt in 1 2; do
  ( cd "$ROOT" && timeout 420 ./gradlew "$task" \
      -PbenchmarkWorkload="$wl" -PbenchmarkPasses=3 -PbenchmarkWarmupPasses=2 \
      -PbenchmarkOutput="$out" -PbenchmarkAllowScalableLux=true -PbenchmarkAllowLucis=true \
      -PbenchmarkExpectedMod="$mod" $fp "-PslArgs=$extra" \
      -x prepareBenchmarkScalableLuxMods --console=plain ) > "$log" 2>&1
  rc=$?
  mp=$(fresh "$out" && tail -1 "$out" 2>/dev/null | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/')
  [ -n "$mp" ] && break
  echo "  (retry $side $wl r$rep: no fresh result, rc=$rc)"
  kill_strays
  done
  mp=$(fresh "$out" && tail -1 "$out" 2>/dev/null | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/')
  hash=$(grep -a "Lux light fingerprint box=" "$log" | tail -1 | sed 's/.*cells=[0-9]* //' | cut -c1-24)
  printf "  %-4s %-20s r%s minPass=%-9s %s rc=%s\n" "$side" "$wl" "$rep" "${mp:-NONE}" "$hash" "$rc"
}

# sides can be overridden, e.g. SIDES_LIST="us usoff" for a same-jar A/B only
SIDES_LIST="${SIDES_LIST:-us usoff sl ls1}"
mapfile -t SIDES < <(printf '%s\n' $SIDES_LIST)
for rep in 1 2 3; do
  for wl in ${WORKLOADS:-block_toggle_border structure_cube dense_chunk_patch sky_hole}; do
    # 每轮轮转顺序（同一轮内四侧连续跑，窗口内交错）
    for i in "${!SIDES[@]}"; do
      idx=$(( (i + rep - 1) % ${#SIDES[@]} ))
      run_one "${SIDES[$idx]}" "$wl" "$rep"
    done
  done
done
kill $SAMPLER 2>/dev/null
echo ""; echo "=== 引擎口径 minPass（3 轮最优轮，ms） ==="
printf "  %-22s %9s %9s %9s %9s\n" workload us usoff sl ls1
for wl in ${WORKLOADS:-block_toggle_border structure_cube dense_chunk_patch sky_hole}; do
  line=$(printf "  %-22s" "$wl")
  for side in us usoff sl ls1; do
    v=""
    for r in 1 2 3; do f="$OUT/$side-$wl-r$r.jsonl"; fresh "$f" && v="$v $(tail -1 "$f" | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/')"; done
    line+=$(printf "%9s" "$(echo $v | tr ' ' '\n' | grep -v '^$' | sort -n | head -1 | awk '{if($1)printf "%.2f",$1/1e6; else printf "?"}')")
  done
  echo "$line"
done
echo ""; echo "=== 玩家口径 wall（每 pass 中位数，ms） ==="
printf "  %-22s %9s %9s %9s %9s\n" workload us usoff sl ls1
for wl in ${WORKLOADS:-block_toggle_border structure_cube dense_chunk_patch sky_hole}; do
  line=$(printf "  %-22s" "$wl")
  for side in us usoff sl ls1; do
    v=""
    for r in 1 2 3; do f="$OUT/$side-$wl-r$r.log"; fresh "$f" || continue
      w=$(grep -a "bench.pass_wall_actual" "$f" 2>/dev/null | sed 's/.*total_ms=//; s/ calls=\([0-9]*\).*/ \1/' | head -1)
      [ -n "$w" ] && v="$v $(echo $w | awk '{if($2>0) printf "%.1f", $1/$2}')"; done
    line+=$(printf "%9s" "$(echo $v | tr ' ' '\n' | grep -v '^$' | sort -n | awk '{a[NR]=$1} END{if(NR)printf "%.0f",a[int((NR+1)/2)]; else printf "?"}')")
  done
  echo "$line"
done
echo ""; echo "=== 负载 ==="; awk '{gsub("cpu=","",$2); gsub("%","",$2); s+=$2; n++; if($2+0>mx)mx=$2+0} END{if(n) printf "  平均 %.1f%%，最高 %.1f%%，样本 %d\n", s/n, mx, n}' "$OUT/load-samples.txt"
echo "=== 新鲜度审计（每侧应有 12）==="
for side in us usoff sl ls1; do n=0; for r in 1 2 3; do for wl in block_toggle_border structure_cube dense_chunk_patch sky_hole; do fresh "$OUT/$side-$wl-r$r.jsonl" && n=$((n+1)); done; done; echo "  $side $n/12"; done
