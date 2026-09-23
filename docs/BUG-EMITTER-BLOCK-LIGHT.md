# BUG：放下的发光方块没有点亮自己（2.0.1，未定位）

**状态：已复现，未定位，未修。** 记录在案，别让它随窗口一起消失。

## 症状（可复现的最小步骤）

专用服务器（`./gradlew runServer`，dev 构建；客户端构造未单独验过），进服后在控制台/聊天：

```
setblock 0 100 0 minecraft:glowstone
lucistarlink light 0 100 0
```

读数是

```
LuciStarlink light 0, 100, 0 block=0 sky=15 raw=15 lightCorrect=true
```

**问题就在 `block=0`**：萤石自身发光等级 15，那一格（乃至它周围）的方块光必须是 15。等 3 秒乃至更久都不变。
第二次 `setblock ... air` 后读数不变（都是 0），所以不是"延迟落地"。

同一条命令链在**放置非发光方块**（测试台的 `structure_cube`，石头类）上完全正常：光照指纹与 vanilla/ScalableLux
逐位相同。也就是说：**发光方块（有 lightEmission 的方块）这条路径没有把光写出来**。

## 这是怎么被发现的

给 `/lucistarlink` 加了 `bench` 子命令（在游戏里跑测试台的 workload：填一个 16³ 立方体、计时、再还原），
第一次服务端实测里"填满发光方块后中心 block=0"，于是拿 `setblock` 做了最小复现——一样是 0。

`bench` 命令本身是好的（512 格 2 pass 跑通，apply / landed 两个数都打出来了），它只是把这个 bug 暴露出来。

## 为什么现有验收没抓到（这是要点）

发布前的四条验收门槛是：玩家口径不劣化、引擎口径不劣化、**599,040 格验证箱的指纹与 vanilla 逐位相同**、
四阶段无挂死。指纹那一关**只跑了 `structure_cube`**，而那一档的改动全是**不发光**的方块（石头类），
`blockNonZero=273 / blockSum=584` —— 方块光几乎是空的。**发光方块的路径没有被任何一格覆盖。**

⇒ 验收规则要补一条：**至少一格工作负载必须包含发光方块（`dense_chunk_patch` 就是萤石），并且它的方块光指纹
也要与 vanilla/ScalableLux 逐位比。** 现有脚本只对 `structure_cube` 传了 `-PbenchmarkLightFingerprint`。

## 下一步（定位用，按代价从低到高）

1. **先确定在哪一层**：用开关对照跑同一个最小复现（每条约 50 秒，脚本已写在下面）：
   - `-Dscalablelux.enabled=false`（原版引擎）→ 若 `block=15`，说明测试本身没问题，问题在我们这条链上；
   - 三个开关全关（`ownEdit/batchDecrease/recomputeSky=false`）→ 若 `block=15`，问题在 R2/R4-1/R5 之中；
   - 只关 `ownEdit`、只关 `batchDecrease` → 二分。
2. **再看是"没算"还是"没发布"**：`/lucistarlink light` 读的是**可见**层（客户端拿到的也是它）。如果引擎内部
   （`updating`）已经有 15 而可见层是 0，那就是发布/标脏环节漏了——那是玩家同样看得见的 bug，但根因不同。
   判据：同一格用 `/lucistarlink light`（可见）与引擎内部读法（`StarLightInterface` 的 updating nibble）各读一次。
3. **然后才是改**：候选嫌疑按现有代码顺序是——`toBlockPositions` 的"方块光空写过滤"（把某些改动从方块光半边漏掉）、
   `seedBlockChangesOnly` + 合并抽干只处理减光（增光/发光播种是否被跳过）、`ownEdit` 的就地安装只写天空层。

## 复现脚本（一条命令一个用例，顺序执行）

```bash
cd /e/LuciStarlin/LuciStarlink-LS-V2
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
# FLAGS 换成要测的那组 -Dscalablelux.*
export JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT'
rm -rf run/world
( sleep 45; printf 'setblock 0 100 0 minecraft:glowstone\n'; sleep 5; \
  printf 'lucistarlink light 0 100 0\n'; sleep 2; printf 'stop\n'; sleep 10 ) \
  | timeout 220 ./gradlew runServer --console=plain | grep -a "LuciStarlink light"
```

卫生提示：每次运行前 `netstat -ano | grep :25565` 确认端口空着（上一次的 dev 服务器会占着它，症状是下一轮
`BindException` 而 gradle 仍然退出码 0）。

## 与发布的关系

`v2.0.1`（`9eabc8a`）的 jar 里**就有这个 bug**。它是否影响玩家取决于第 1 步的定位结果：如果是 `ownEdit` 或
`batchDecrease` 引入的，那影响所有放下了发光方块的场景（插火把、放萤石、萤石灯——很常见）；如果只在
"控制台 `/setblock`" 这条路径上，影响面就小得多。**在定位清楚之前，不要对外说 2.0.1 没有已知缺陷。**
