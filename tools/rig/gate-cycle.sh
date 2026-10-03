#!/usr/bin/env bash
# 循环残留门：**每个循环结束时世界回到原状，所以每次读数都必须逐格等于基线 read_0**。
# 存在的理由：用户 2026-09-29 报「光源残留 有几率会出现」——确定性的"拆掉不回升"已在 10.40 修掉，
# 剩下的概率性表现只能靠多次循环 + 不同形状/时序去撞。每个循环覆盖一类历史高发形状：
#   R1 单点萤石（chunk 边界列）  R2 同一批里放+拆（同 tick 的净零编辑）  R3 3x3x3 小块
#   R4 火把（非不透明光源：只动方块光）  R5 跨 chunk 长条
# 判据只有一条：read_i == read_0（i=1..10，逐格），任何一格不同即为残留，附坐标与两边的值。
# $1 = engine knobs（走 Gradle -PslArgs）
set -uo pipefail
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
cd /e/LuciStarlin/LuciStarlink-LS-V2
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
export SLARGS="${1:-}"
LOG=/tmp/gate-cycle.log; : > "$LOG"
SRC="run/saves/新的世界"
rm -rf run-diag/world; cp -r "$SRC" run-diag/world
DP=run-diag/world/datapacks/cycletest
rm -rf "$DP"; mkdir -p $DP/data/cycletest/function $DP/data/minecraft/tags/function
printf '{"pack": {"pack_format": 48, "description": "cycle residue gate"}}\n' > $DP/pack.mcmeta
printf '{"values": ["cycletest:load"]}\n' > $DP/data/minecraft/tags/function/load.json

# 探针表：覆盖每个形状自己的格子 + 邻格 + 斜角 + 上下一格
PROBES="lucistarlink light -1 -37 -16
lucistarlink light 0 -37 -16
lucistarlink light 0 -36 -16
lucistarlink light -2 -37 -16
lucistarlink light 9 -37 -14
lucistarlink light 9 -36 -14
lucistarlink light 9 -38 -14
lucistarlink light 3 -36 -7
lucistarlink light 3 -38 -7
lucistarlink light 3 -34 -7
lucistarlink light 1 -36 -7
lucistarlink light 6 -37 -12
lucistarlink light 5 -37 -12
lucistarlink light 2 -37 -16
lucistarlink stats"

# 一个读函数：$1 = 标签（WRITE 到 CYC-<tag>），$2 = 下一跳函数名（空则不跳）
mk_read() {
  { echo "$PROBES"; echo "say CYC-$1"; [ -n "${2:-}" ] && echo "schedule function cycletest:$2 2s"; } \
    > $DP/data/cycletest/function/$1.mcfunction
}

mk_read read_baseline cyc_r1_place
mk_read read_1 cyc_r2_pair
mk_read read_2 cyc_r3_place
mk_read read_3 cyc_r4_place
mk_read read_4 cyc_r5_place
mk_read read_5 cyc_r1_place_b
mk_read read_6 cyc_r2_pair_b
mk_read read_7 cyc_r3_place_b
mk_read read_8 cyc_r4_place_b
mk_read read_9 cyc_r5_place_b
mk_read read_10 done
mk_read read_relight done2

cat > $DP/data/cycletest/function/load.mcfunction <<'EOF'
say CYC-START
schedule function cycletest:read_baseline 40s
EOF

# R1 单点萤石，chunk 边界列（x=-1 在 chunk -1，x=0 在 chunk 0）
cat > $DP/data/cycletest/function/cyc_r1_place.mcfunction <<'EOF'
setblock -1 -37 -16 glowstone
schedule function cycletest:r1_remove 2s
EOF
cat > $DP/data/cycletest/function/r1_remove.mcfunction <<'EOF'
setblock -1 -37 -16 air
schedule function cycletest:read_1 2s
EOF
cat > $DP/data/cycletest/function/cyc_r1_place_b.mcfunction <<'EOF'
setblock -1 -37 -16 glowstone
schedule function cycletest:r1_remove_b 2s
EOF
cat > $DP/data/cycletest/function/r1_remove_b.mcfunction <<'EOF'
setblock -1 -37 -16 air
schedule function cycletest:read_6 2s
EOF

# R2 同一批里放 + 拆（净零）：世界不变，光也不能变
cat > $DP/data/cycletest/function/cyc_r2_pair.mcfunction <<'EOF'
setblock 9 -37 -14 glowstone
setblock 9 -37 -14 air
schedule function cycletest:read_2 2s
EOF
cat > $DP/data/cycletest/function/cyc_r2_pair_b.mcfunction <<'EOF'
setblock 9 -37 -14 glowstone
setblock 9 -37 -14 air
schedule function cycletest:read_7 2s
EOF

# R3 3x3x3 小块
cat > $DP/data/cycletest/function/cyc_r3_place.mcfunction <<'EOF'
fill 2 -37 -8 4 -35 -6 glowstone
schedule function cycletest:r3_remove 2s
EOF
cat > $DP/data/cycletest/function/r3_remove.mcfunction <<'EOF'
fill 2 -37 -8 4 -35 -6 air
schedule function cycletest:read_3 2s
EOF
cat > $DP/data/cycletest/function/cyc_r3_place_b.mcfunction <<'EOF'
fill 2 -37 -8 4 -35 -6 glowstone
schedule function cycletest:r3_remove_b 2s
EOF
cat > $DP/data/cycletest/function/r3_remove_b.mcfunction <<'EOF'
fill 2 -37 -8 4 -35 -6 air
schedule function cycletest:read_8 2s
EOF

# R4 火把：非不透明光源，只该动方块光
cat > $DP/data/cycletest/function/cyc_r4_place.mcfunction <<'EOF'
setblock 6 -37 -12 torch
schedule function cycletest:r4_remove 2s
EOF
cat > $DP/data/cycletest/function/r4_remove.mcfunction <<'EOF'
setblock 6 -37 -12 air
schedule function cycletest:read_4 2s
EOF
cat > $DP/data/cycletest/function/cyc_r4_place_b.mcfunction <<'EOF'
setblock 6 -37 -12 torch
schedule function cycletest:r4_remove_b 2s
EOF
cat > $DP/data/cycletest/function/r4_remove_b.mcfunction <<'EOF'
setblock 6 -37 -12 air
schedule function cycletest:read_9 2s
EOF

# R5 跨 chunk 长条
cat > $DP/data/cycletest/function/cyc_r5_place.mcfunction <<'EOF'
fill -1 -37 -16 2 -37 -16 glowstone
schedule function cycletest:r5_remove 2s
EOF
cat > $DP/data/cycletest/function/r5_remove.mcfunction <<'EOF'
fill -1 -37 -16 2 -37 -16 air
schedule function cycletest:read_5 2s
EOF
cat > $DP/data/cycletest/function/cyc_r5_place_b.mcfunction <<'EOF'
fill -1 -37 -16 2 -37 -16 glowstone
schedule function cycletest:r5_remove_b 2s
EOF
cat > $DP/data/cycletest/function/r5_remove_b.mcfunction <<'EOF'
fill -1 -37 -16 2 -37 -16 air
schedule function cycletest:read_10 2s
EOF
printf 'say CYC-DONE\nlucistarlink relight 2\nschedule function cycletest:read_relight 4s\n' > $DP/data/cycletest/function/done.mcfunction
printf 'say CYC-DONE2\nstop\n' > $DP/data/cycletest/function/done2.mcfunction

timeout 320 ./gradlew runServerDiag ${SLARGS:+-PslArgs="$SLARGS"} --console=plain > "$LOG" 2>&1
echo "engine knobs: ${SLARGS:-<defaults>}"
grep -a "LuciStarlink light " "$LOG" | sed -E 's/.*LuciStarlink //; s/ bSt=[NUHI?]+ sSt=[NUHI?]+//' > /tmp/cyc-all.txt
total=$(wc -l < /tmp/cyc-all.txt); nprobes=14
echo "readings total=$total (expect $((nprobes*12)) = 12 x $nprobes, the 12th being the post-relight read)"
if [ "$total" -lt $((nprobes*12)) ]; then echo "INCOMPLETE RUN ($total) - 不可判"; exit 1; fi
head -$nprobes /tmp/cyc-all.txt > /tmp/cyc-base.txt
bad=0
# read 11 is the post-relight read: it tells us which of the two sides is the truth (a residue of OUR cycles keeps
# the baseline's value; a baseline that was already stale gets REPAIRED by the relight, i.e. it moves away from it)
for i in $(seq 1 11); do
  label="read_$i"; [ "$i" = 11 ] && label="read_relight (post-relight)"
  sed -n "$((nprobes*i+1)),$((nprobes*(i+1)))p" /tmp/cyc-all.txt > /tmp/cyc-i.txt
  if diff -q /tmp/cyc-base.txt /tmp/cyc-i.txt > /dev/null; then
    echo "  read_$i == 基线 ✓"
  else
    bad=$((bad+1)); echo "  $label ** 与基线不同 **："; diff /tmp/cyc-base.txt /tmp/cyc-i.txt | grep -E "^[<>]" | cut -c1-118 | head -4
  fi
done
echo "=== 结论：$bad / 11 次读数与基线不同 ==="
