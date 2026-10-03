#!/usr/bin/env bash
# 残留门：**装满 → 读 → 拆光 → 读 → relight → 读**。判据 = 拆除后的读数必须等于全量 relight 后的读数
# （即"我们自己的拆除结果 == 规范的拆除结果"，一点残留都不许有），且拆除确实改变了读数（否则判据是空跑的）。
# 存在的理由：用户 2026-09-29 在 dev 客户端里报「有光源残留」，日志显示是一次 11088 方块的 /fill —— 大编辑的
# 半边丢工作在本仓已经有六次前科（2.0.6 就是"大 burst 的方块光半边从来没算过"），而现有 gate 只测"放上去"。
# $1 = engine knobs（走 Gradle -PslArgs，不是 JAVA_TOOL_OPTIONS，理由见 gate-gradient.sh）
set -uo pipefail
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
cd /e/LuciStarlin/LuciStarlink-LS-V2
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
export SLARGS="${1:-}"
LOG=/tmp/gate-residue.log; : > "$LOG"
SRC="run/saves/新的世界"
rm -rf run-diag/world; cp -r "$SRC" run-diag/world
DP=run-diag/world/datapacks/residuetest
rm -rf "$DP"; mkdir -p $DP/data/residuetest/function $DP/data/minecraft/tags/function
printf '{"pack": {"pack_format": 48, "description": "residue gate"}}\n' > $DP/pack.mcmeta
printf '{"values": ["residuetest:load"]}\n' > $DP/data/minecraft/tags/function/load.json
printf 'say RES-START\nschedule function residuetest:place 45s\n' > $DP/data/residuetest/function/load.mcfunction

# 一次大编辑：9x5x9 = 405 块萤石，横跨 x=-1..7（chunk -1 与 0 的交界，正是历史上出事的位置）
cat > $DP/data/residuetest/function/place.mcfunction <<'EOF'
say RES-PLACING
setblock -1 -37 -16 air
setblock -1 -37 -16 glowstone
setblock 20 -37 -10 air
setblock 20 -37 -10 glowstone
fill 0 -37 -16 8 -33 -8 minecraft:glowstone
say RES-PLACED
schedule function residuetest:read1 6s
EOF

# 拆除：全部改回空气
cat > $DP/data/residuetest/function/remove.mcfunction <<'EOF'
say RES-REMOVING
fill 0 -37 -16 8 -33 -8 minecraft:air
setblock -1 -37 -16 minecraft:air
setblock 20 -37 -10 minecraft:air
say RES-REMOVED
schedule function residuetest:read2 6s
EOF

# 三次读数用同一张探针表；READ3 在 relight 之后
for phase in read1 read2; do
cat > $DP/data/residuetest/function/$phase.mcfunction <<'EOF'
lucistarlink light -1 -37 -16
lucistarlink light 4 -37 -16
lucistarlink light 4 -36 -12
lucistarlink light 4 -33 -12
lucistarlink light 4 -32 -12
lucistarlink light 4 -30 -12
lucistarlink light 12 -38 -14
lucistarlink light 12 -36 -12
lucistarlink light 20 -37 -10
lucistarlink light 20 -36 -10
lucistarlink light 3 -37 -16
lucistarlink light 9 -37 -16
lucistarlink light 9 -35 -8
lucistarlink light 0 -34 -16
lucistarlink light -2 -37 -16
lucistarlink light 4 -38 -12
lucistarlink stats
lucistarlink layerdump 1
EOF
done
printf 'say RES-READ1\n' >> $DP/data/residuetest/function/read1.mcfunction
printf 'schedule function residuetest:remove 4s\n' >> $DP/data/residuetest/function/read1.mcfunction

printf 'say RES-READ2\n' >> $DP/data/residuetest/function/read2.mcfunction
printf 'lucistarlink relight 2\nschedule function residuetest:read3 4s\n' >> $DP/data/residuetest/function/read2.mcfunction

sed 's/say RES-READ1/say RES-READ3/; s/lucistarlink relight 2//; s|schedule function residuetest:read3 4s|schedule function residuetest:done 2s|' \
    $DP/data/residuetest/function/read2.mcfunction > $DP/data/residuetest/function/read3.mcfunction
printf 'say RES-DONE\nstop\n' > $DP/data/residuetest/function/done.mcfunction

timeout 320 ./gradlew runServerDiag ${SLARGS:+-PslArgs="$SLARGS"} --console=plain > "$LOG" 2>&1
echo "engine knobs: ${SLARGS:-<defaults>}"
grep -a "LuciStarlink light " "$LOG" | sed 's/.*LuciStarlink //' > /tmp/res-all.txt
total=$(wc -l < /tmp/res-all.txt)
echo "readings total=$total (expect 48 = 3 x 16)"
head -16 /tmp/res-all.txt > /tmp/res-r1.txt
sed -n '17,32p' /tmp/res-all.txt > /tmp/res-r2.txt
tail -n +33 /tmp/res-all.txt > /tmp/res-r3.txt
echo "=== 拆除后 vs relight 后（必须逐格相同）==="
if [ "$total" -lt 48 ]; then echo "INCOMPLETE RUN ($total readings) - 不可判"; exit 1; fi
if diff -q /tmp/res-r2.txt /tmp/res-r3.txt >/dev/null; then echo "RESIDUE-FREE: READ2 == READ3"; else
  echo "RESIDUE FOUND:"; diff -y --width=200 /tmp/res-r2.txt /tmp/res-r3.txt | grep -E "\||<|>" | head -12; fi
echo "=== 拆除是否真的生效（READ1 vs READ2 必须不同）==="
diff -q /tmp/res-r1.txt /tmp/res-r2.txt >/dev/null && echo "READ1 == READ2 !! 拆除没生效，判据空跑" || echo "ok: 拆除改变了读数"
