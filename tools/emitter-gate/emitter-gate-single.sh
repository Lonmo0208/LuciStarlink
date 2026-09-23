#!/usr/bin/env bash
# 确定性发光方块测试：世界生成完毕后再放 → 读光 → 自己停。
set -uo pipefail
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
cd /e/LuciStarlin/LuciStarlink-LS-V2
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT -Dscalablelux.editDebug=true ${1:-}"
LOG=/tmp/emit.log
rm -rf run-diag/world
# 第一次启动：只为生成世界
( sleep 55; printf 'stop\n'; sleep 10 ) | timeout 200 ./gradlew runServerDiag --console=plain > "$LOG" 2>&1
# 装数据包：加载后 45s 才动作（等出生点生成完）
DP=run-diag/world/datapacks/emittertest
mkdir -p $DP/data/emittertest/function $DP/data/minecraft/tags/function
printf '{"pack": {"pack_format": 48, "description": "emitter test"}}\n' > $DP/pack.mcmeta
cat > $DP/data/emittertest/function/load.mcfunction <<'EOF'
say EMITTERTEST-SCHEDULED
schedule function emittertest:place 45s
EOF
cat > $DP/data/emittertest/function/place.mcfunction <<'EOF'
say EMITTERTEST-PLACE
setblock 0 150 0 minecraft:glowstone
lucistarlink light 0 150 0
lucistarlink stats
schedule function emittertest:read 5s
EOF
cat > $DP/data/emittertest/function/read.mcfunction <<'EOF'
lucistarlink light 0 150 0
lucistarlink stats
say EMITTERTEST-READ
schedule function emittertest:finish 2s
EOF
cat > $DP/data/emittertest/function/finish.mcfunction <<'EOF'
say EMITTERTEST-DONE
stop
EOF
printf '{"values": ["emittertest:load"]}\n' > $DP/data/minecraft/tags/function/load.json
# 第二次启动：数据包自己跑完并停服
timeout 260 ./gradlew runServerDiag --console=plain > "$LOG" 2>&1
echo "=== 结果 ==="
grep -aE "EMITTERTEST|LuciStarlink light|LuciStarlink tasks=" "$LOG" | sed 's/.*\[LuciStarlink\/\]: //' | sed 's/.*\[minecraft\/MinecraftServer\]: //' | cut -c1-185
echo "=== 该区块当时的可见性/改动是否进引擎 ==="
grep -aE "SETUPDBG skip chunk 0,0|EDITDBG blockChange BlockPos\{x=0, y=150|EMITDBG checkBlock 0,150" "$LOG" | head -5
