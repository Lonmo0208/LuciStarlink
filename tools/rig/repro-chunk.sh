#!/usr/bin/env bash
# 每区块一条 fill（2048 格）铺满 4 个区块 ≈ 8192 格：填萤石 → 转储 → 清空 → 转储
set -uo pipefail
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
cd /e/LuciStarlin/LuciStarlink-LS-V2
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT -Dscalablelux.profile=true"
LOG=/tmp/cr.log
rm -rf run-diag/world; cp -r "run/saves/新的世界" run-diag/world
DP=run-diag/world/datapacks/dt; rm -rf "$DP"
mkdir -p $DP/data/pack/function $DP/data/minecraft/tags/function
printf '{"pack": {"pack_format": 48, "description": "chunk-wise repro"}}\n' > $DP/pack.mcmeta
printf '{"values": ["pack:load"]}\n' > $DP/data/minecraft/tags/function/load.json
printf 'say CR-LOADED\nschedule function pack:fill 45s\n' > $DP/data/pack/function/load.mcfunction
gen_fills() { local block=$1 out=$2; : > "$out"
  for cx in 1 2 3 4; do for cz in -3 -2; do
    printf 'fill %s -61 %s %s -54 %s %s\n' $((cx*16)) $((cz*16)) $((cx*16+15)) $((cz*16+15)) "$block" >> "$out"
  done; done; }
gen_fills glowstone /tmp/cf.txt; gen_fills air /tmp/cc.txt
echo "fill 条数=$(grep -c . /tmp/cf.txt)（每条 2048 格，合计 ~$(echo "$(grep -c . /tmp/cf.txt) * 2048" | bc) 格）"
gen() { local f=$DP/data/pack/function/$1.mcfunction; : > "$f"
  for y in -60 -58; do for x in $(seq 20 4 68); do for z in $(seq -44 4 -16); do printf 'lucistarlink light %s %s %s\n' "$x" "$y" "$z" >> "$f"; done; done; done
  printf 'say %s\n' "$2" >> "$f"; }
{ printf 'say CR-FILLING\n'; cat /tmp/cf.txt; printf 'say CR-FILLED\nschedule function pack:read1 12s\n'; } > $DP/data/pack/function/fill.mcfunction
gen read1 READ1-DONE
printf 'schedule function pack:clear 3s\n' >> $DP/data/pack/function/read1.mcfunction
{ printf 'say CR-CLEARING\n'; cat /tmp/cc.txt; printf 'say CR-CLEARED\nschedule function pack:read2 15s\n'; } > $DP/data/pack/function/clear.mcfunction
gen read2 READ2-DONE
printf 'stop\n' >> $DP/data/pack/function/read2.mcfunction
N=$(grep -c 'lucistarlink light' $DP/data/pack/function/read1.mcfunction)
timeout 600 ./gradlew runServerDiag --console=plain > "$LOG" 2>&1
grep -aE "CR-FILLED|CR-CLEARED|READ1-DONE|READ2-DONE" "$LOG" | sed 's/.*MinecraftServer\]: //' | cut -c1-50
grep -aE "LuciStarlink light" "$LOG" | sed 's/.*LuciStarlink light/LuciStarlink light/' > /tmp/cr-all.txt
head -$N /tmp/cr-all.txt > /tmp/crA.txt; tail -n +$((N+1)) /tmp/cr-all.txt > /tmp/crB.txt
echo "填充后 A：萤石格数=$(grep -c 'state=Block{minecraft:glowstone}' /tmp/crA.txt)  非零方块光=$(grep -vc 'block=0 ' /tmp/crA.txt) / $(grep -c . /tmp/crA.txt)"
echo "清空后 B：非零方块光=$(grep -vc 'block=0 ' /tmp/crB.txt) / $(grep -c . /tmp/crB.txt)"
echo "=== B 里残留的非零格子（前 10）==="; grep -v "block=0 " /tmp/crB.txt | sed 's/.*light //' | head -10 | cut -c1-100
