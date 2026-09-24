#!/usr/bin/env bash
# 残留光诊断：同一片区域 → 转储 A → 强制重算 → 转储 B。A 与 B 的差 = 存档里的残留光。
set -uo pipefail
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
cd /e/LuciStarlin/LuciStarlink-LS-V2
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
LOG=/tmp/lightdump.log
rm -rf run-diag/world; cp -r "run/saves/新的世界" run-diag/world
DP=run-diag/world/datapacks/dump; rm -rf "$DP"
mkdir -p $DP/data/pack/function $DP/data/minecraft/tags/function
printf '{"pack": {"pack_format": 48, "description": "light dump"}}\n' > $DP/pack.mcmeta
printf '{"values": ["pack:load"]}\n' > $DP/data/minecraft/tags/function/load.json
printf 'say DUMP-LOADED\nschedule function pack:dump 45s\n' > $DP/data/pack/function/load.mcfunction
gen_dump() { # $1 = function name, $2 = end marker
  local f=$DP/data/pack/function/$1.mcfunction
  : > "$f"
  for y in -61 -60; do
    for x in $(seq -12 2 24); do
      for z in $(seq -40 2 0); do
        printf 'lucistarlink light %s %s %s\n' "$x" "$y" "$z" >> "$f"
      done
    done
  done
  printf 'say %s\n' "$2" >> "$f"
}
gen_dump dump DUMP-A-DONE
printf 'schedule function pack:relight 3s\n' >> $DP/data/pack/function/dump.mcfunction
printf 'lucistarlink relight 2\nsay RELIT\nschedule function pack:dump2 10s\n' > $DP/data/pack/function/relight.mcfunction
gen_dump dump2 DUMP-B-DONE
printf 'stop\n' >> $DP/data/pack/function/dump2.mcfunction
echo "转储行数: $(grep -c 'lucistarlink light' $DP/data/pack/function/dump.mcfunction)"
timeout 400 ./gradlew runServerDiag --console=plain > "$LOG" 2>&1
echo "=== A/B 差异（残留光：重算后变暗的地方）==="
grep -aE "LuciStarlink light" "$LOG" | sed 's/.*LuciStarlink light/LuciStarlink light/' > /tmp/dump-all.txt
awk '/DUMP-A-DONE/{a=1} /DUMP-B-DONE/{b=1} {if (!a) print > "/tmp/dumpA.txt"; else if (a && !b) print > "/tmp/dumpB.txt"}' /tmp/dump-all.txt
echo "A=$(grep -c 'light' /tmp/dumpA.txt)  B=$(grep -c 'light' /tmp/dumpB.txt)"
