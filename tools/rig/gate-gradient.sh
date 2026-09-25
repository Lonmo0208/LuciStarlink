#!/usr/bin/env bash
# 正确性门：发光体梯度（含跨区块边界）+ 边境多次小改动
set -uo pipefail
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
cd /e/LuciStarlin/LuciStarlink-LS-V2
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT ${1:-}"
LOG=/tmp/gate205.log
SRC="run/saves/新的世界"
rm -rf run-diag/world; cp -r "$SRC" run-diag/world
DP=run-diag/world/datapacks/emittertest
rm -rf "$DP"; mkdir -p $DP/data/emittertest/function $DP/data/minecraft/tags/function
printf '{"pack": {"pack_format": 48, "description": "gradient gate"}}\n' > $DP/pack.mcmeta
printf '{"values": ["emittertest:load"]}\n' > $DP/data/minecraft/tags/function/load.json
printf 'say GATE-START\nschedule function emittertest:place 45s\n' > $DP/data/emittertest/function/load.mcfunction
cat > $DP/data/emittertest/function/place.mcfunction <<'EOF'
say GATE-PLACING
setblock -1 -37 -16 air
setblock -1 -37 -16 glowstone
setblock 2 -37 -16 air
setblock 2 -37 -16 glowstone
setblock 5 -37 -16 air
setblock 5 -37 -16 glowstone
fill 10 -37 -16 13 -34 -13 minecraft:glowstone
say GATE-PLACED
schedule function emittertest:read 6s
EOF
cat > $DP/data/emittertest/function/read.mcfunction <<'EOF'
lucistarlink light -1 -37 -16 
lucistarlink light 0 -37 -16
lucistarlink light 1 -37 -16
lucistarlink light 4 -37 -16
lucistarlink light 7 -37 -16
lucistarlink light 10 -37 -16
lucistarlink light 12 -36 -14
lucistarlink light 14 -36 -14
lucistarlink light 17 -36 -14
lucistarlink stats
say GATE-READ
stop
EOF
timeout 280 ./gradlew runServerDiag --console=plain > "$LOG" 2>&1
echo "=== 三个发光体（x=-1/2/5）与梯度（0,1,4,7,10）==="
grep -aE "LuciStarlink light|GATE-READ" "$LOG" | sed 's/.*\[LuciStarlink\/\]: //;s/.*\[minecraft\/MinecraftServer\]: //' | cut -c1-100
