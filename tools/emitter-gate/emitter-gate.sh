#!/usr/bin/env bash
# 用户的原话：放光源 -> 退出存档 -> 重新进 -> 还亮吗？
set -uo pipefail
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
cd /e/LuciStarlin/LuciStarlink-LS-V2
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
LOG=/tmp/reload.log
DP=run-diag/world/datapacks/emittertest
rm -rf run-diag/world
boot() { timeout 240 ./gradlew runServerDiag --console=plain > "$LOG" 2>&1; }
# 1) 生成世界
( sleep 50; printf 'stop\n'; sleep 10 ) | timeout 200 ./gradlew runServerDiag --console=plain > "$LOG" 2>&1
mkdir -p $DP/data/emittertest/function $DP/data/minecraft/tags/function
printf '{"pack": {"pack_format": 48, "description": "reload verify"}}\n' > $DP/pack.mcmeta
printf '{"values": ["emittertest:load"]}\n' > $DP/data/minecraft/tags/function/load.json
# 2) 第一次进服：放光源 + 读 + 关服保存
cat > $DP/data/emittertest/function/load.mcfunction <<'EOF'
say RELOADTEST-BOOT-A
schedule function emittertest:place 45s
EOF
cat > $DP/data/emittertest/function/place.mcfunction <<'EOF'
say RELOADTEST-PLACING
setblock 0 150 0 minecraft:glowstone
lucistarlink light 0 150 0
save-all flush
schedule function emittertest:finish 3s
EOF
cat > $DP/data/emittertest/function/finish.mcfunction <<'EOF'
say RELOADTEST-STOPPING-A
stop
EOF
printf 'say unused\n' > $DP/data/emittertest/function/read.mcfunction
echo "=== 第一次进服（放光源、存盘、退出）==="
boot
grep -aE "RELOADTEST|LuciStarlink light" "$LOG" | sed 's/.*\[LuciStarlink\/\]: //;s/.*\[minecraft\/MinecraftServer\]: //' | cut -c1-150
# 3) 第二次进服：只读
cat > $DP/data/emittertest/function/load.mcfunction <<'EOF'
say RELOADTEST-BOOT-B
schedule function emittertest:read 45s
EOF
cat > $DP/data/emittertest/function/read.mcfunction <<'EOF'
lucistarlink light 0 150 0
say RELOADTEST-STOPPING-B
stop
EOF
echo "=== 重新进服（同一个存档，只读那格）==="
boot
grep -aE "RELOADTEST|LuciStarlink light" "$LOG" | sed 's/.*\[LuciStarlink\/\]: //;s/.*\[minecraft\/MinecraftServer\]: //' | cut -c1-150
