#!/usr/bin/env bash
# 残留门：大范围 fill 荧石 -> 空气 循环，停在空气那一步，然后问引擎自己
# "visible 与 updating 一致吗"。
#
# 形状不是自造的，是用户实报的：32x32 铺一层荧石、整层清成空气、保存退出重进会看到残留亮斑。
# 上游的 cycle gate 用的是自造的小形状（能确定性复现），这个门补的是**玩家手操尺寸**那一档。
#
# 判据全部来自引擎自己的仪器，不新增任何生产路径代码：
#   lucistarlink layerdump <r>   走遍半径内已加载区块，打印 visible 与 updating 不一致的 section。
#                                不一致 = 某次 merge 从未发生 = 残留的形态本身。
#   lucistarlink relight <r>     修复路径。之后 layerdump 必须干净，否则说明不一致能被"修"出来
#                                而不是被清掉。
#
# 用法：
#   bash tools/residue-gate/residue-gate.sh                    # 从脚本位置推项目根
#   bash tools/residue-gate/residue-gate.sh /e/LuciStarlin/LuciStarlink-LS-V2
#   LANE_INVARIANT=1 bash tools/residue-gate/residue-gate.sh   # 额外让图像通道逐格自校验
#   KEEP_WORLD=1 bash tools/residue-gate/residue-gate.sh       # 保留世界（默认先删，保证首触干净）
#
# 退出码：0 = 干净（layerdump 无 ⚠ 行，且 relight 前后一致）；1 = 有残留；2 = 环境起不来。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${1:-$(cd "$HERE/../.." && pwd)}"
if [ ! -f "$ROOT/gradlew" ]; then
    echo "找不到 $ROOT/gradlew —— 第一个参数请给项目根" >&2
    exit 2
fi

# Windows 上 JDK 常不在 PATH 里；给了就用，没给就交给环境。
if [ -n "${JAVA_HOME_OVERRIDE:-}" ]; then
    export JAVA_HOME="$JAVA_HOME_OVERRIDE"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

cd "$ROOT" || exit 2

LOG="${LOG:-$(mktemp -t residue-gate.XXXXXX.log)}"
DP="$ROOT/run-diag/world/datapacks/residuegate"
RADIUS="${RADIUS:-1}"          # layerdump/relight 的半径，1 覆盖 3x3 区块
FILL="${FILL:-80 80 111 111}"  # forceload 的 x1 z1 x2 z2，对应 2x2 区块
Y="${Y:-120}"

# 现场决定要不要开不变量自校验。开它 = 慢，但能逐格抓住"映像与世界不一致"。
SL_ARGS=()
if [ "${LANE_INVARIANT:-0}" = "1" ]; then
    SL_ARGS=(-PslArgs="-Dscalablelux.laneInvariant=true")
    echo "== laneInvariant 已开启（逐格自校验，慢） =="
fi

if [ "${KEEP_WORLD:-0}" != "1" ]; then
    rm -rf run-diag/world
fi

mkdir -p "$DP/data/residuegate/function" "$DP/data/minecraft/tags/function"
printf '{"pack": {"pack_format": 48, "description": "bulk-fill residue gate"}}\n' > "$DP/pack.mcmeta"
printf '{"values": ["residuegate:load"]}\n' > "$DP/data/minecraft/tags/function/load.json"

# 45 秒才动手：等 spawn 生成结束、视野内区块都过了 LIGHT。
cat > "$DP/data/residuegate/function/load.mcfunction" <<'EOF'
schedule function residuegate:p1 45s
EOF

cat > "$DP/data/residuegate/function/p1.mcfunction" <<EOF
forceload add $FILL
say GATE-P1-GLOWSTONE
fill $(echo $FILL | awk '{print $1, "'"$Y"'", $2, $3, "'"$Y"'", $4}') minecraft:glowstone
schedule function residuegate:p2 15s
EOF

cat > "$DP/data/residuegate/function/p2.mcfunction" <<EOF
say GATE-P2-AIR
fill $(echo $FILL | awk '{print $1, "'"$Y"'", $2, $3, "'"$Y"'", $4}') minecraft:air
schedule function residuegate:p3 15s
EOF

# 判据 A：清完之后立刻问一次。
cat > "$DP/data/residuegate/function/p3.mcfunction" <<EOF
say GATE-P3-DUMP-A
execute positioned $(( $(echo $FILL | awk '{print $1}') + 16 )) $Y $(( $(echo $FILL | awk '{print $2}') + 16 )) run lucistarlink layerdump $RADIUS
schedule function residuegate:p4 5s
EOF

# 修复路径，作为差分对照。
cat > "$DP/data/residuegate/function/p4.mcfunction" <<EOF
say GATE-P4-RELIGHT
lucistarlink relight $RADIUS
schedule function residuegate:p5 30s
EOF

# 判据 B：relight 之后必须干净。
cat > "$DP/data/residuegate/function/p5.mcfunction" <<EOF
say GATE-P5-DUMP-B
execute positioned $(( $(echo $FILL | awk '{print $1}') + 16 )) $Y $(( $(echo $FILL | awk '{print $2}') + 16 )) run lucistarlink layerdump $RADIUS
say GATE-DONE
schedule function residuegate:bye 3s
EOF

cat > "$DP/data/residuegate/function/bye.mcfunction" <<'EOF'
stop
EOF

echo "== 起服务端（一次启动；日志 $LOG） =="
timeout "${BOOT_TIMEOUT:-420}" ./gradlew runServerDiag --console=plain "${SL_ARGS[@]}" > "$LOG" 2>&1
BOOT=$?

echo
echo "== 阶段 =="
grep -aE "GATE-" "$LOG" | sed 's/.*\[Server\] //;s/.*\] \[minecraft\/MinecraftServer\/\]: //' | cut -c1-160

echo
echo "== layerdump 输出（⚠ 行才是不一致） =="
grep -aE "layerdump|visible|updating" "$LOG" | cut -c1-200

echo
echo "== 判定 =="
if grep -aqE "GATE-DONE" "$LOG"; then
    if grep -aqE "layerdump.*(mismatch|⚠)" "$LOG"; then
        echo "有残留：layerdump 报出 visible 与 updating 不一致的 section（见上）"
        echo "日志：$LOG"
        exit 1
    fi
    echo "干净：layerdump 未报出不一致"
    echo "日志：$LOG"
    exit 0
fi

echo "环境起不来或超时（gradle 退出码 $BOOT），日志：$LOG"
exit 2
