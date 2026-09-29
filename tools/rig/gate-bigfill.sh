#!/usr/bin/env bash
# 大 fill 残留门：**整 chunk 足迹的大 fill（每 chunk 上千块 → 足以触发 bulkRelight 路径）放上去再清掉，
# 光必须逐格回到基线**。存在的理由：用户 2026-09-29 在 dev 客户端截图报告——11088 块的平坦 /fill 清掉后
# 地面仍留一整片方块光；此前两个门（gate-residue 405 块、gate-cycle 小形状）都没覆盖这个规模。
# $1 = engine knobs（走 Gradle -PslArgs）
# 注意：客户端开着时 run/saves 里的 session.lock 被占用，cp 会报一行错但世界数据照抄（无害）。
set -uo pipefail
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
cd /e/LuciStarlin/LuciStarlink-LS-V2
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
export SLARGS="${1:-}"
LOG=/tmp/gate-bigfill.log; : > "$LOG"
SRC="run/saves/新的世界"
rm -rf run-diag/world; cp -r "$SRC" run-diag/world
DP=run-diag/world/datapacks/bigfilltest
rm -rf "$DP"; mkdir -p $DP/data/bigfilltest/function $DP/data/minecraft/tags/function
printf '{"pack": {"pack_format": 48, "description": "big fill residue gate"}}\n' > $DP/pack.mcmeta
printf '{"values": ["bigfilltest:load"]}\n' > $DP/data/minecraft/tags/function/load.json

# 探针：slab 下方一格（块光从上方来）、slab 旁边、以及 slab 自身足迹的格（清掉后应变回空气）
PROBES="lucistarlink light 4 -38 -8
lucistarlink light 12 -38 -8
lucistarlink light 20 -38 -8
lucistarlink light 4 -36 -8
lucistarlink light 12 -36 -8
lucistarlink light 20 -36 -8
lucistarlink light -1 -38 -8
lucistarlink light 32 -38 -8
lucistarlink light 4 -38 -20
lucistarlink light 12 -38 0
lucistarlink light 12 -48 -8
lucistarlink light 12 -30 -8"

mk_read() { local extra=""; [ "$1" = read_cleared ] && extra="execute positioned 12 -34 -8 run lucistarlink layerdump 2"; { echo "$PROBES"; echo "$extra"; echo "say BF-$1"; [ -n "${2:-}" ] && echo "schedule function bigfilltest:$2 3s"; } \
  > $DP/data/bigfilltest/function/$1.mcfunction; }

mk_read read_base big_place
mk_read read_placed big_clear
mk_read read_cleared read_relight0
mk_read read_relight done
printf 'say BF-DONE\nstop\n' > $DP/data/bigfilltest/function/done.mcfunction
# relight 之后再读一次，用来判断哪一边是真值
cat > $DP/data/bigfilltest/function/read_relight0.mcfunction <<'EOF'
lucistarlink relight 2
schedule function bigfilltest:read_relight 5s
EOF

cat > $DP/data/bigfilltest/function/load.mcfunction <<'EOF'
say BF-START
schedule function bigfilltest:read_base 40s
EOF

# 大 fill：x=0..31（2 个 chunk）、z=-16..-1（1 个 chunk）、y=-37..-32（6 层）→ 每 chunk 1536 块
cat > $DP/data/bigfilltest/function/big_place.mcfunction <<'EOF'
say BF-PLACING
fill 0 -37 -16 31 -32 -1 glowstone
say BF-PLACED
schedule function bigfilltest:read_placed 3s
EOF
cat > $DP/data/bigfilltest/function/big_clear.mcfunction <<'EOF'
say BF-CLEARING
fill 0 -37 -16 31 -32 -1 air
say BF-CLEARED
schedule function bigfilltest:read_cleared 4s
EOF

timeout 320 ./gradlew runServerDiag ${SLARGS:+-PslArgs="$SLARGS"} --console=plain > "$LOG" 2>&1
echo "engine knobs: ${SLARGS:-<defaults>}"
grep -a "LuciStarlink light " "$LOG" | sed 's/.*LuciStarlink //' > /tmp/bf-all.txt
n=12; total=$(wc -l < /tmp/bf-all.txt)
echo "readings total=$total (expect $((n*4)) = 4 x $n: base / placed / cleared / relight)"
if [ "$total" -lt $((n*4)) ]; then echo "INCOMPLETE RUN ($total)"; exit 1; fi
head -$n /tmp/bf-all.txt > /tmp/bf-base.txt
sed -n "$((n+1)),$((2*n))p" /tmp/bf-all.txt > /tmp/bf-placed.txt
sed -n "$((2*n+1)),$((3*n))p" /tmp/bf-all.txt > /tmp/bf-cleared.txt
tail -n +$((3*n+1)) /tmp/bf-all.txt > /tmp/bf-relight.txt
echo "=== 放上去是否真的亮了（base vs placed）==="
diff -q /tmp/bf-base.txt /tmp/bf-placed.txt >/dev/null && echo "!! 放上去没变化，判据空跑" || echo "ok: 放上去改变读数"
echo "=== 清掉后 vs 基线（必须逐格相同 = 无残留）==="
if diff -q /tmp/bf-base.txt /tmp/bf-cleared.txt >/dev/null; then echo "NO-RESIDUE: cleared == base"; else
  echo "RESIDUE:"; diff /tmp/bf-base.txt /tmp/bf-cleared.txt | grep -E "^[<>]" | cut -c1-120 | head -10; fi
echo "=== 清掉后 vs relight（判断哪边是真值）==="
diff /tmp/bf-cleared.txt /tmp/bf-relight.txt | grep -cE "^[<>]" | awk '{print "  与 relight 不同的行数: "$1}'
diff /tmp/bf-base.txt /tmp/bf-relight.txt | grep -cE "^[<>]" | awk '{print "  基线与 relight 不同的行数: "$1}'
