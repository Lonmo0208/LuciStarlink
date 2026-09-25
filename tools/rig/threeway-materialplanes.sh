#!/usr/bin/env bash
# The acceptance for the windowed sky settle: does it hold up over rounds, on all four cells, with the canonical
# fingerprint still identical (the settle reproduces the engine's light, so it must not move the hash).
#   us20 = LS-V2 rig with -Dscalablelux.recomputeSky=true (the new settle) + ownEdit + batchDecrease
#   sl   = pristine ScalableLux   |   ls1 = our 1.x line
set -uo pipefail
ROOT=/e/LuciStarlin/LuciStarlink
MODS="$ROOT/run-benchmark-scalablelux/mods"
OUT=/e/LuciStarlin/sl-jar/threeway-criteria; mkdir -p "$OUT"
LOADLOG="$OUT/load-samples.txt"; : > "$LOADLOG"
US20_JAR=/e/LuciStarlin/sl-jar/ls2-imagelane-rig.jar
SL_JAR=/e/LuciStarlin/ScalableLux-neoforge-build/ScalableLux-Master/build/libs/ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar
P='-Dlucistarlink.benchmark.prepareRing=8 -Dlucistarlink.benchmark.quiesceSettleMs=1000 -Dlucistarlink.benchmark.globalEngineBarrier=false'
# --- hardened jar staging (2026-09-26) ---------------------------------------------------------------
# On Windows a leftover java (a benchmark server that has not finished exiting) still holds the jar in
# mods/, and BOTH `rm -f` and `cp -f` then fail SILENTLY: the server starts on the PREVIOUS engine's jar and
# aborts with "Lux benchmark expected mod is not loaded: <mod>", producing an empty result file that looks
# like a slow run. Stage by md5 and retry, killing strays in between.
kill_strays() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'gameDir|fml.modFolders|run-benchmark' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
  for port in 25665 25666 25667 25668; do
    for pid in $(netstat -ano 2>/dev/null | grep -E ":$port .*LISTENING" | awk '{print $5}' | sort -u); do cmd //c "taskkill /F /PID $pid" >/dev/null 2>&1; done
  done
  sleep 3
}
stage_jar() { # $1 = jar to place; $2 = MODS dir
  local want; want=$(md5sum "$1" | cut -d' ' -f1)
  for attempt in 1 2 3 4 5 6; do
    rm -f "$2"/*.jar 2>/dev/null
    cp -f "$1" "$2/" 2>/dev/null
    local got; got=$(md5sum "$2/$(basename "$1")" 2>/dev/null | cut -d' ' -f1)
    if [ "$got" = "$want" ] && [ "$(ls "$2" | wc -l)" = "1" ]; then return 0; fi
    kill_strays
  done
  echo "STAGE-FAILED for $1" >&2
  return 1
}
US20_P="$P -Dscalablelux.imageLane=true"
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
cpu() { powershell -NoProfile -Command "(Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 3).CounterSamples.CookedValue | Measure-Object -Average | Select-Object -ExpandProperty Average" 2>/dev/null | tr -d '\r'; }
kill_strays() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'fml.modFolders|gameDir|user_jvm_args|win_args|run-benchmark' } | Stop-Process -Force" >/dev/null 2>&1
  for port in 25665 25666 25667 25668; do
    for pid in $(netstat -ano | grep -E ":$port .*LISTENING" | awk '{print $5}' | sort -u); do cmd //c "taskkill /F /PID $pid" >/dev/null 2>&1; done
  done
  sleep 2
}
( while true; do echo "$(date '+%H:%M:%S') cpu=$(cpu)%" >> "$LOADLOG"; sleep 120; done ) & SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null' EXIT
run_one() {
  local side=$1 wl=$2 rep=$3 task dir mod jar extra
  case "$side" in
    us20) task=runBenchmarkScalableLuxServer; dir="$ROOT/run-benchmark-scalablelux"; mod=lucistarlinkrig; jar="$US20_JAR"; extra="$US20_P";;
    sl)   task=runBenchmarkScalableLuxServer; dir="$ROOT/run-benchmark-scalablelux"; mod=scalablelux;      jar="$SL_JAR";   extra="$P";;
    ls1)  task=runBenchmarkServer;             dir="$ROOT/run-benchmark-lucistarlink"; mod=lucistarlink;   jar="";          extra="$P";;
  esac
  kill_strays
  rm -rf "$dir"/world* 2>/dev/null
  : # jar staged by stage_jar below
  [ -n "$jar" ] && stage_jar "$jar" "$MODS"
  local tag; tag="$side-$wl-r$rep"
  local out="$OUT/$tag.jsonl"; local log="$OUT/$tag.log"
  local fp=""; [ "$wl" = structure_cube ] && fp='-PbenchmarkLightFingerprint=-40,32,-40,55,96,55'
  ( cd "$ROOT" && timeout 420 ./gradlew "$task" \
      -PbenchmarkWorkload="$wl" -PbenchmarkPasses=3 -PbenchmarkWarmupPasses=2 \
      -PbenchmarkOutput="$out" -PbenchmarkAllowScalableLux=true -PbenchmarkAllowLucis=true \
      -PbenchmarkExpectedMod="$mod" $fp "-PslArgs=$extra" \
      -x prepareBenchmarkScalableLuxMods --console=plain ) > "$log" 2>&1
  local rc=$?; local mp; mp=$(tail -1 "$out" 2>/dev/null | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/')
  local hash=""; [ "$wl" = structure_cube ] && hash=$(grep -a "Lux light fingerprint box=" "$log" | tail -1 | sed 's/.*cells=599040 //' | cut -c1-22)
  printf "  %-4s %-20s r%s minPass=%-9s %s rc=%s\n" "$side" "$wl" "$rep" "${mp:-NONE}" "$hash" "$rc"
}
for rep in 1 2 3; do
  for wl in block_toggle_border structure_cube dense_chunk_patch sky_hole; do
    case $(( (rep - 1) % 3 )) in
      0) order="us20 sl ls1";;
      1) order="sl ls1 us20";;
      2) order="ls1 us20 sl";;
    esac
    for side in $order; do run_one "$side" "$wl" "$rep"; done
  done
done
kill $SAMPLER 2>/dev/null
echo ""; echo "=== 窗口重算验收：minPass 中位数（ms），3 轮 ==="
printf "  %-22s %10s %10s %10s\n" workload us20 sl ls1
for wl in block_toggle_border structure_cube dense_chunk_patch sky_hole; do
  line=$(printf "  %-22s" "$wl")
  for side in us20 sl ls1; do
    vals=""
    for rep in 1 2 3; do v=$(tail -1 "$OUT/$side-$wl-r$rep.jsonl" 2>/dev/null | sed 's/.*"minPassNanos":\([0-9]*\).*/\1/'); [ -n "$v" ] && vals="$vals $v"; done
    line+=$(printf "%10s" "$(echo $vals | tr ' ' '\n' | grep -v '^$' | sort -n | awk '{a[NR]=$1} END{if(NR==0){print "?"}else{printf "%.2f",a[(NR+1)/2]/1e6}}')")
  done
  echo "$line"
done
echo ""; echo "=== 玩家口径过线时间（ms，3 轮中位数 / 最优轮） ==="
printf "  %-22s %14s %14s %14s\n" workload us20 sl ls1
for wl in block_toggle_border structure_cube dense_chunk_patch sky_hole; do
  line=$(printf "  %-22s" "$wl")
  for side in us20 sl ls1; do
    vals=""
    for rep in 1 2 3; do
      w=$(grep -a "bench.pass_wall_actual" "$OUT/$side-$wl-r$rep.log" 2>/dev/null | sed 's/.*total_ms=//; s/ calls=\([0-9]*\).*/ \1/' | head -1)
      if [ -n "$w" ]; then a=$(echo $w|cut -d' ' -f1); b=$(echo $w|cut -d' ' -f2); [ "$b" -gt 0 ] 2>/dev/null && vals="$vals $(awk -v t=$a -v c=$b 'BEGIN{printf "%.0f", t/c}')"; fi
    done
    med=$(echo $vals | tr ' ' '\n' | grep -v '^$' | sort -n | awk '{a[NR]=$1} END{if(NR==0){print "?"}else{printf "%.0f",a[(NR+1)/2]}}')
    mn=$(echo $vals | tr ' ' '\n' | grep -v '^$' | sort -n | head -1)
    line+=$(printf "%14s" "$med / $mn")
  done
  echo "$line"
done
echo ""; echo "=== 负载 ==="; awk '{gsub("cpu=","",$2); gsub("%","",$2); s+=$2; n++; if($2+0>mx)mx=$2+0} END{if(n>0) printf "  平均 %.1f%%，最高 %.1f%%，样本 %d\n", s/n, mx, n}' "$LOADLOG"
