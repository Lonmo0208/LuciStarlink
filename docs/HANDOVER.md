# 交接：2026-09-24 收工时的状态（换窗口请先读这份）

这份是"换窗口"用的现状快照。**代码与文档都已提交并推送**，所以新窗口从仓库就能接上，不需要这段对话的上下文。
配套材料：`docs/NEW-ENGINE-TEARDOWN.md`（全部测量记录）、`docs/BUG-EMITTER-BLOCK-LIGHT.md`（本轮所有 bug 的根因与验证）、
`docs/ARCHITECTURE.md`（归属与验收规则）、`CHANGELOG.md`（2.0.1 → 2.0.7 逐版）。

## 0. 一句话现状

**2.0.8：`block_toggle_border` 不再输了**（它是唯一用机制解释清楚的失败格：我们把每区块 5 处的小改动放进
就地通道，等于每个区块都同步付一次 setup+drain+publish，而引擎自己的异步线程处理同样的活更便宜；2.0.8 让
≤16 处改动/区块的 burst 走回引擎队列，实测从 6.37/5.62 降到 5.33/5.37，对 ScalableLux 的 5.10/5.64 打平）。
**但对另外三格要改口径**：本机负载窗口下重测两轮，只有 `structure_cube` 是稳定胜利（4.71/5.85 对 5.99/6.32，
光照指纹与原版逐位相同），`dense`、`sky_hole` 是噪声内的互有胜负（同机 2.0.7 窗口里 dense 曾领先 5%，今天落后
2–17%），**所以 2.0.7 那句"四格赢三格"要撤回**。原因可测：两个引擎在四格上的 BFS 弹出数完全一样
（border 101,087 对 101,133），光照传播是同一份代码做同样的工作量，剩下的差别是每次 drain 的固定开销和机器负载。

## 1. 仓库与发布物

| 项 | 值 |
|---|---|
| 工作树 | `E:\LuciStarlin\LuciStarlink-LS-V2`（= `master` = `LS-V2` 分支的 worktree） |
| 当前 tip | **代码发布点 = `v2.0.8`（`32b1524`）**，CI 全绿；其后的提交只有测量插桩（`b65f5d9`：settle 阶段计时，无行为改动），本节以下所有数字都属于 2.0.8 的引擎 |
| 分支 | `master` = `LS-V2`（同名同内容）；`1x-line` = `472d897`（1.x 线 + 测试台，未动） |
| tag | `v2.0.1` … `v2.0.8`（1.x 的 11 个 `v1.2.x` 未动） |
| 本地产物 | `E:\LuciStarlin\sl-jar\lucistarlink-2.0.8-release.jar`，md5 `f46ee515964f32d8da6c196e6cdc1c47`（**发布件不上 GitHub**，只推 tag） |
| 测量用的 rig jar | `sl-jar/ls2-dispatch-rig.jar`（mod id `lucistarlinkrig`）。**已逐类校验 = 2.0.8 的引擎**：92 个 class 里 91 个与发布 jar 逐字节相同，唯一差异是入口类名（`LuciStarlinkRigEntrypoint` / `LuciStarlinkEntrypoint`，由 mod id 决定） |
| 远端的 2.0 线 tag | `v2.0.1`（首版）、`v2.0.2/.3/.4`（光照修复链）、`v2.0.5`（border 拿回）、`v2.0.6`（大批量方块光 NPE）、`v2.0.7`（flush 缓冲 8192）、`v2.0.8`（border 真正修好：小 burst 走回引擎队列） |

## 2. 2.0.8 的成绩（**两轮交替**，本机负载窗口；同一窗口里 2.0.7 的旧数字不可跨窗口比较）

| 档位 | 我们 2.0.8（第1/2轮） | ScalableLux（第1/2轮） | 结论 |
|---|---|---|---|
| `structure_cube` | **4.71 / 5.85 ms** | 5.99 / 6.32 | **两轮都赢**（12–21%），指纹与原版/SL 逐位相同 |
| `dense_chunk_patch` | 5.42 / 4.93 | 4.63 / 4.83 | 本窗口落后 2–17%；2.0.7 窗口里曾领先 5% → **未确立** |
| `sky_hole` | 1.11 / 1.12 | 0.85 / 1.61 | 两轮符号相反 → **未确立** |
| `block_toggle_border` | 5.30 / 5.96 | 5.10 / 5.64 | 打平（**修好前是 6.37/5.62 对 5.06/5.76，即稳定落后 25–35%**） |

`structure_cube` 的规范指纹 **`sky=905931078dfc5ace block=59e2252f732ce67b`**，与原版和 ScalableLux 逐位相同。

### 六轮双引擎对照（2026-09-24 晚）——**两轮结论不可信，这是收紧后的判定**

`bash tools/rig/duel6.sh block_toggle_border dense_chunk_patch`（每个 workload 12 轮服务器，交替 us/SL）：

| 档位 | 指标 | 我们（六轮均值，范围） | ScalableLux | 逐轮胜负 | 判定 |
|---|---|---|---|---|---|
| `block_toggle_border` | minPass | 4.152 ms（3.86–4.40） | **4.026 ms**（3.74–4.32） | 1:5 | **平**（SL 前 3%） |
| | 每轮均值 | **5.197 ms**（4.80–5.75） | 5.583 ms（4.64–6.84） | 5:1 | **平**（我们前 7%） |
| | wall（玩家） | 149.3 ms | 148.5 ms | 3:3 | **平** |
| `dense_chunk_patch` | minPass | **4.011 ms**（3.77–4.36） | 4.098 ms（3.58–4.84） | 4:2 | **平**（我们前 2%） |
| | 每轮均值 | **4.623 ms** | 4.658 ms | 4:2 | **平**（我们前 1%） |
| | wall（玩家） | 151.4 ms | 151.0 ms | 3:3 | **平** |

**关键读数：两个引擎各自的逐轮波动（3.74–4.84 ms、均值 4.6–6.8 ms）比两者之间的差距（1–3%）大得多。**
所以这两格是平局，不是胜负；本会话早先那些"SL 领先 2–17%"或"我们领先 2–5%"的两轮结论全是噪声——**这就是为什么本文件里所有单轮/两轮数字都必须带上轮数**。唯一稳定领先的仍是 `structure_cube`（12–21%，远大于噪声）。

**为什么四格里只有一格稳定赢**：可测，不是玄学——两个引擎在四格上的传播工作量完全相同（border 的 BFS 弹出数
101,087 对 101,133；neighbour 扫描 482k 对 505k），跑的是同一份传播代码，剩下的差别只有每次 drain 的固定开销和
本机负载。所以 `dense`/`sky_hole` 的胜负在 ±10% 内来回翻，要拿"稳赢"只能靠**做更少的工作**，而两条候选路
（方块光窗口重算、scattered burst 攒到收口点再合并成组）都实测不划算，见 §4。

复现：`bash tools/rig/final208.sh`（就是跑出上表的脚本：四格 × {我们, SL} × 两轮，交替）。

## 3. 本轮踩过并修掉/记录下来的东西（别重复踩）

1. **大批量改动的方块光半边从来没有被计算**（2.0.1 起）：`deferSky` 分支把 `null` 当作位置集合传给方块引擎 →
   空指针 → 被 `catch (Throwable)`（只加计数器、不打日志）静默吞掉。修法：集合无条件构建 + catch 打 ERROR
   + `relight` 先清零方块光再重算（它以前修不掉旧残留）。
2. **`block_toggle_border` 曾经的"快 12 倍"是"没干活"的假象**（0.32 ms），任何引用都要带上这句。
3. **光的性能问题要看 harness 的 `applyMillis` / `waitMillis` 分段**，不要只看 `minPassNanos`——本轮的胜负手
   （光计算被挤在 `setBlock` 循环里、每次 256 格刷新都要重走已点亮地带）就是靠这个分段找到的。
4. **验收门槛必须含光源**：只比对 `structure_cube` 的指纹（全石头、599,040 格里只有 273 格有方块光）会漏掉
   上面那一类 bug。规则已写进 `docs/ARCHITECTURE.md` §4，工具在 `tools/emitter-gate/`。
5. **读"发光体自己那格"不算测试**（什么都没传播时它照样读 15）；要读**梯度**（15/14/13…）。
6. **排查残留光要逐格转储**：我先前每 2 格抽样，正好跳过了那支火把（残留中心其实是玩家自己的普通火把，
   发光 14；红石火把上限只有 7）。
7. **调试开关的代价**：`-Dscalablelux.editDebug=true` 是"每个方块改动打一行"，玩家的 `/fill 11088` 让它写出
   **205 MB 日志、3M 行**，游戏直接卡死。要用只能用遥测（`-Dscalablelux.telemetrySeconds=5`）级别。
8. **同一个 `run/` 目录不能同时跑客户端和服务端**（抢 `run/logs/latest.log`，第二个 JVM 加载完模组就死）→
   已加 `runServerDiag`（独立 `run-diag/` + 独立端口 25690）。
9. **往 gradle 控制台管道喂多条命令会丢命令** → 诊断协议改用**数据包 + `schedule`**（脚本在 `/tmp/lightdump.sh`、
   `/tmp/repro-chunk.sh`、`tools/emitter-gate/` 有正式版）。
10. **函数里的 `/fill` 跨区块会静默失败**（单区块内正常）；**函数里的命令失败不会中断函数**（我曾据此误判）。
11. **新世界的区块在出生点准备阶段是 `initialize_light`**，`minecraft:load` 里测等于在测"引擎正确忽略半生成区块"
    → 等 ~45 秒再动作。
12. **`-PbenchmarkExpectedMod` 会被 harness 校验**，所以"把 SL 的 jar 当我们的引擎跑"这种交叉对照做不了。

## 4. `block_toggle_border` —— **已结案**（2.0.8），以及没走通的两条路

**结论：不是单次成本问题，也不是工作量问题，是"路线"问题。** 这一格的 95 处改动是"每区块约 5 处、跨 19 个区块、
一个 tick 内到齐"。就地通道在每个区块切换时同步结算一次（cache setup → seed → drain → publish），一个 pass 就是
17–29 次；引擎自己的异步线程结算同样的活更便宜。把 ≤16 处改动/单区块的 burst 交回引擎队列（`LUCIS_INLINE_MIN_BURST=16`，
2.0.8 起为默认）后：

| 路线（同会话交替，2 轮） | minPass 第1/2轮 | 每 pass 均值 第1/2轮 |
|---|---|---|
| 就地通道（2.0.7 行为） | 6.37 / 5.62 ms | 8.12 / 7.14 ms |
| 就地通道 + 天空逐点 seed | 6.00 / 6.11 | 7.19 / 7.10 |
| **交回引擎队列（2.0.8）** | **5.33 / 5.37** | **6.11 / 6.87** |
| 纯净 ScalableLux | 5.06 / 5.76 | 5.94 / 7.29 |

最终两轮交替对照：我们 5.30/5.96 对 SL 5.10/5.64 —— 打平。

**量具上的教训（比结论更值钱）**：这个规则本来就存在，默认关着，注释里写着"实测关闭"。那些测量是在
**方块光半边根本没被计算**的窗口里做的（2.0.6 修的 null 集合缺陷），所以它比较的"0.43 ms"是**把活跳过去**的价钱。
"把活送走"的规则在活免费的时候看着当然差。同一个错误在这个项目上犯过一次（`bulkRelight`、`pendingFlushSize`），
两个都在 2.0.6 之后重测并翻案。

**没走通的两条路（都实测）**：

1. **方块光窗口重算**（复用天空那套）：`dense` 上实测≈打平（窗口 5.77/6.56 对逐点 seed 5.87/6.33），因为 dense 的
   真正开销不是"重新算格子"而是**阴影处的 decrease 级联**（16×16 的补丁挡住天光，下面就有一整列要走到 0），
   两个引擎都得走。border 上窗口更差（改动的 y 散在 0..15，窗口 256×47 格×18 区块）。
2. **scattered burst 攒到收口点再合并**（`-Dscalablelux.holdScattered=true`，默认关）：block settle 确实便宜了
   15%（5.84 → 4.98 ms，22 个区块合成 12 组），但总量没赢（它仍然背着天空窗口），而且这条路是噪声最大的
   （同一配置两轮 7.86/5.19）。**保留在代码里但默认关**，谁要再试先看它的两轮数据。

**剩下的唯一方向**（未验证、也还没动手）：把"整块区域被一起清掉"这种情况识别出来（一次 tick 内同一区域的
decrease 合并成一次区域清零 + 从剩余光源重建），因为现在的 19 次分区 drain 之间会互相重复 refill。这不是调度
问题，需要新的判据。

**⚠ 2026-09-24 更正：我在这里写过的"同代码同工作量差 3 倍"是我的算错，不是引擎。** harness 的 SLPROF 窗口是
**每个 measured pass 一份**，而 `applyMillis`/`waitMillis` 是**三个 measured pass 的合计**，我把两者直接相除比较了。
加了「decrease / publish 拆分 + 已发布 section 计数」之后，同一个 jar、同一个 workload 两条路线的实测是：
方块 decrease **4.2–5.3 ms（就地通道）对 4.3–5.4 ms（引擎队列）**（glowstone 轮），publish 0.027–0.035 对
0.036–0.043，setup 0.056–0.064 对 0.055–0.063，**发布 section 数 59 对 59–61** —— 工作和价钱完全一样，只差在
`elapsed`（约 1.4–2 ms/轮，就地通道更差），那点余量是就地通道的逐改动记账 + 它把天空延后到窗口（该 workload 上
窗口约 2 ms，而队列路线逐点 seed 只要 0.29 ms）。**所以 border 没有"线程/JIT 之谜"可挖**：2.0.8 的派发规则
（交给引擎队列）就是那里正确的答案，轻载窗口下两引擎打平（minPass 4.04/3.92 对 4.08/4.12；均值 5.48/5.46 对
5.34/5.51；wall 149/155 对 154/143 ms）。**教训：永远不要把"每轮计数"和"三轮合计"放在一起比**——`beginWindow`
给计数划了窗口，`applyMillis` 没有。

## 5. 常用操作（照抄）

**五个测量脚本已经归档进仓库 `tools/rig/`（含 `tools/rig/README.md` 说明各自用途）**——`%TEMP%` 会被清，
上面那些数字只有脚本还在时才可复现。它们硬编码了本机路径，换机器先改开头十行。

```bash
cd /e/LuciStarlin/LuciStarlink-LS-V2
export JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT

./gradlew build -x test                       # 发布构建（mod id: lucistarlink）
./gradlew build -Pmod_id=lucistarlinkrig -x test   # 测量构建（mod id: lucistarlinkrig）
./gradlew runServerDiag                       # 独立诊断服务端（run-diag/，端口 25690）
./gradlew runClient                           # dev 客户端（单人档玩/看）
```

- **四档验收（2.0.8 的表就是它跑的）**：`bash tools/rig/final208.sh` —— 我们 vs ScalableLux，四档 × 两轮交替；
  换引擎改脚本里的 `US`，换输出目录改 `OUT`；整轮约 25 分钟。**同一时刻只许跑一个 rig 脚本**：两个脚本会抢
  `run-benchmark-scalablelux/mods` 和世界目录，结果是两边都写出空结果（我踩过，白白废掉一轮）。
- **只看 border 的快速对照**：`bash tools/rig/border-duel.sh`（四轮交替，约 5 分钟）。
- **要带上 1.x 的三方表**：`bash tools/rig/threeway-engines.sh`（它自带负载采样与残留进程清理；**注意它仍指向 2.0.6 的
  `ls2-npefix-rig.jar`**，用前先把 `US20_JAR` 换成新 rig jar、`OUT` 换目录）。
- **单光源/跨区块梯度门**：`bash tools/rig/gate-gradient.sh`（期望 15/14/14/14/13/10）。
- **批量改动 A/B**：`bash tools/rig/repro-chunk.sh`（8192 格填/清 + 转储）。
- **残留光诊断**：`bash tools/rig/lightdump-residue.sh`（转储 A → 强制重算 → 转储 B，差就是存档里的残留）。
- **端口卫生**：25665 原版 / 25666 scalablelux / 25667 lucistarlink / 25668；跑前 `netstat -ano | grep :25666`，有残留就按 PID `taskkill /F /T`。
- **机器卫生**：收工前 `Stop-Process` 掉 `fabri.dli`（游戏）与 `runServer`；注意 Git Bash 的 `taskkill /F` 会失效，要用 `cmd //c`。

## 6. 2.0 架构现状（一句话版）

引擎主体（存储/增量传播/区块与世界生成管线/原版接口/客户端发包）是 **ScalableLux 的代码**；
**更新回路是我们自己的**：就地编辑通道（**≤16 处改动/单区块的 burst 交回引擎队列**，2.0.8 起）、按区块攒批
（缓冲 8192）、分组结算（±1 区块的区块共用一个 cache 窗口）、**窗口化天空收口**、守卫提升到区块级、方块光空写跳过；
外加遥测/命令/profiler/Sable/配置/存档保证。细节见 `docs/ARCHITECTURE.md`。

## 7. 对外口径（引用数字时一并带上）

- **2.0.8：`structure_cube` 稳定赢 ScalableLux（12–21%，两轮都是）；`dense`/`sky_hole`/`border` 在三格上是同一
  工作量的平局（±10% 随负载翻符号）。2.0.7 的"四格赢三格"已撤回**，因为那是负载较轻窗口里的单轮结果，
  本机负载窗口两轮重测不复现。
- 为什么是平局而不是赢：两引擎的 BFS 工作量相同（border 101,087 对 101,133 次弹出），跑同一份传播代码。
  要稳赢必须"做更少的工作"，不是调度能解决的。
- 对 1.x：引擎口径它领先三格（0.76/2.87/2.36，2.0.7 窗口的数字），玩家口径我们赢三格（49–51 对 67–86 ms）。
- **任何引用旧表的场合都要注明**：2.0.4 之前那些"四档全胜"是在方块光半边不参与的情况下测的。
- 我们的光照与原版/SL **逐位相同**（指纹为证）；1.x 的不是。
- 发布件只在本地 `E:\LuciStarlin\sl-jar\`，GitHub 上只有 tag。贡献者的 `threeway.ps1` **不许改**。

## 8. 三引擎对照（2026-09-24 深夜）——今天最重要的结论

`bash tools/rig/threeway-2.0.8.sh`（3 轮拉丁方轮转，四档，负载均值 35.2%）。**minPass 中位数（ms）**：

| workload | 我们 2.0.8 | ScalableLux | 1.x (1.2.10) |
|---|---|---|---|
| `block_toggle_border` | 4.33 | 4.29 | **0.84**（快 5×） |
| `structure_cube` | 3.78 | 4.39 | **2.75**（指纹 `641356fc41163add`，与原版/我们/SL 都不同） |
| `dense_chunk_patch` | 4.12 | 3.67 | **2.30** |
| `sky_hole` | 0.87 | 0.80 | **0.73** |

**玩家口径（wall，中位数 / 最优轮）**：

| workload | 我们 | ScalableLux | 1.x |
|---|---|---|---|
| `block_toggle_border` | **50 / 48** | 50 / 50 | 81 / 63 |
| `structure_cube` | **50 / 50** | 50 / 50 | 68 / 64 |
| `dense_chunk_patch` | **51 / 50** | 50 / 49 | 60 / 56 |
| `sky_hole` | **51 / 49** | 50 / 48 | 59 / 54 |

**两条线在不同指标上领先，方向完全相反**：1.x 的引擎自持一份扁平的"区域图像"，小改动在自己的数组上算，
所以**引擎口径四格全胜**；代价是**玩家口径四格全输**（59–81 ms，要跨到第 2 个 tick；我们与 SL 都在 1 个 tick 内收尾）。
"全赢"必须同时赢两个指标，而这两种架构恰好在这一点上互相交换——本项目已实测过这条交换的代价
（`docs/OWN-ENGINE.md` / 提交 `d88cac1`：把图像搬进我们的引擎，单 section 提取 185 µs，乐观组合 ≈48 µs
对 ScalableLux 的 ≈34 µs，不划算）。所以"赢 1.x 的引擎口径"= 换成它的存储架构 = 在重负载格上退回去。

## 9. 图像通道（2026-09-25，`docs/IMAGE-LANE-PLAN.md`）——**默认关闭，`-Dscalablelux.imageLane=true` 开启**

把 1.x 的引擎核心（我们自己的 Lucis 代码）移植进了 2.0：`light/image/` 下是逐行照抄的 BFS 内核
（模糊测试 fuzzErrors=0：增量 == 从头重算 oracle，400 随机世界 × 2 轮；20 ns/弹出对 nibble 的 ~41），
加完整的区域机制（采纳/打包/跨区失效/LRU）；`ImageLane` 在 `ServerLevel.onBlockStateChange` 捕获材质
变化（世界生成由 postProcessGeneration 括号挡住），服务器主线程同步结算，脏 section 打包回 nibble
（先 updateVisible 再 onLightUpdate）。梯度门实测走了通道（stats：lane=settles=2 captured=3
packed=20，梯度规范 15/14/14/14/13/10）。

开启后四格实测（与 SL 交替）：
- `structure_cube` **3.54 vs 7.55** ✓（走旧路：4096 > 上限 2048；指纹规范）
- `dense_chunk_patch` **4.68 vs 6.44** ✓（走通道：apply 3.96 ms，正是内核 20 ns/弹出预测的胜利）
- `sky_hole` 0.94 vs 0.80-1.53 平（走旧路：25 < 下限 64）
- `block_toggle_border` 7.3-8.8 vs 4.37-4.69 ✗（**唯一回归**：散布流量每改动付捕获+记账税， grouped nibble 路径一次 setup+drain 批量处理更便宜）

路由规则：只有"某区块 ≥64 处改动且区域总量 ≤2048"的 burst 走通道；不合格区域跳过并记 stale
（下次资格结算前强制全量重采纳）；资格判定按 buffer 大小做 memo（hasUpdates 轮询不重扫）。
默认关闭 = 逐位等价 2.0.8 行为。**未解决问题：border 形状下捕获本身 ~2-3 ms/轮**——需要捕获期的
廉价形状过滤器（IMAGE-LANE-PLAN.md §6 的账目仍有效：dense 已赢，border 与 1.x 的贴脸差距仍在）。

### 图像通道的护城河（2026-09-25 深夜，`4fba329`）——**内核已证、活体有一个未解 bug**

区域四平面外扩一圈（垫圈：opacity 15、light 0），BFS 热循环删掉全部边界检查与坐标解码——
**15 ns/弹出**（原 20），border 形状 0.91 ms。三个证明：fuzzErrors=0（padded 语义下增量==oracle）、
**nibble 往返探针**（采纳→写回→逐位比对，55,296 格 0 错）、crc 与改前完全一致。
扩展梯度门新增 4×4×4 荧石补丁（64 处 = 路由下限）以覆盖通道的集中流量路线——
**它在活体上抓到一个真 bug：通道接管补丁时打包出的光读 1/0/0（应 15）；fallback 路线仍正确**。
采纳/打包映射在隔离往返下逐位精确，故故障在活体时序（区域初始化时机 / 外部重采纳 /
skip-stale 交互）。**通道保持 opt-in（默认关 = 逐位等价 2.0.8）；此 bug 是下一窗口的第一任务。**
调试教训：`gradlew | head` 会 SIGPIPE 杀死构建、cp 进旧 jar（两次幽灵崩溃的根源——先 javap 验 jar）；
自测必须用 `localIndex`（区域局部）而非 `index`（世界坐标，会减 minBlockX）；探针区域高度必须是整 section。

### 独占所有权（2026-09-25，`0b77ef2`）——**活体 bug 修复，三格对 SL 双口径全胜**

relight 前后对照 + 区域转储 + 行转储三层定位：世界状态对、基准重算对、区域对、结算时 nibble 对——
**6 秒后读取错**。覆盖者 = 路由下限派发到基础队列的异步任务（种子早于通道结算、减光波清掉通道的场、
重建只含自己的种子）。修复 = **独占所有权**：通道开启时它捕获的改动全部由它结算（每区块下限删除，
上限保留 → structure 仍走同步 grouped 路线），基础队列永不接触通道已采纳的区域——异步冲突结构性消失。
扩展门实测：通道输出规范、relight 后完全一致（attempts=67 accepted=67 settles=2 captured=67）。

四格（与 SL 交错）：**structure 3.99 vs 4.56 ✓、dense 3.44 vs 4.08 ✓、sky_hole 0.82 vs 1.02 ✓
（三格对 SL 双口径全胜）、border 4.55/4.69 vs 3.90/3.89 ✗（唯一未过线：散布流量的每区域税 ~0.7 ms）**。
下一窗口：border 的区域合并（REGION_CHUNKS 加大使 19 区块塌缩）或减重打包；然后 dense/structure 对 1.x
的 2.30/2.83；玩家口径轮数验证。

### tile 调参（`d615818`）——**4×4 实测过重，默认回到 1×1，阈值全部属性化**

REGION_CHUNKS/HALO_CHUNKS 现在是系统属性。4×4 tile 的实验被测量否决：全高区域 ~3.7M 格 × 4 平面（~15 MB），
init 要采纳 864 个 section + 提取 ~130 万次方块状态（~26 ms），border 直接撞 harness 超时。**border 的正解是
1.x 式的懒式 per-section 物化**（`markSectionsWithinReach`/`materializedLightSections` 已移植未接线）。
另：border2x 内联脚本的 rig jar 被 harness 的 mods 同步清掉（expected-mod 校验失败）——**跑测量用归档脚本原样，
别写内联变体**。

### 懒式物化 + 同质证书（`f0a535f`）——**dense 的 1.x 判据是窗口依赖的，未站稳**

分段计数器（matzNanos 等已保留）点出成本：border 结算 9.2 ms 里 7.6 ms、structure 里 4.4 ms、dense 里 1.8 ms 都是
**逐格材质提取**（~100 µs/section）。两刀：(1) 懒式物化（新区域不预采纳，每次结算只物化"自己改动 ±16 格"内的
section，已物化的靠外部重采纳保鲜）；(2) **同质证书**（1.x `LuxRegionExtractor` 规则）：全空气不填、palette 认证
"位置无关 + 全不透明 + 零发光"的 section 整行填 15——谓词三个子句正是逐格规则成立的条件，形状方块/玻璃会落到
逐格路径，**证书不可能与逐格结果不一致**。提取 ~100 µs → ~1 µs/section。

**单次读数**：dense 2.15 对 1.x 的 2.30（首次过线）、border 回到 4.73 平。**但同窗口 3 轮交错（重负载窗口）**：
dense 我们 3.14/6.21/5.69 对 1.x 2.37/2.30/3.16（输）、border 我们 6.6-10.1 对 SL 4.07-4.25（输）。
拆开看：**我们的引擎部分（minPass − apply）轻载 1.87 ms、重载涨到 ~5 ms，而 1.x 稳定 ~1.0**——通道的同步结算
（物化+采纳+BFS+打包+发布）对负载远更敏感，这才是真正的剩余差距，而不是峰值数字。

判据现状：**引擎 vs 1.x——sky_hole 已赢（3/3，两个窗口）；dense/border 未站稳（窗口依赖）；玩家 vs SL——轻载赢、
重载输**。下一手段（dense 引擎部分的剩下 ~2×）：packDirty 里的发布路径（每脏 section 一次 onLightUpdate）、
每结算的区域生命周期，以及端到端测出"负载敏感"到底来自材质提取的缓存未命中、发布、还是服务器线程竞争。

### tile 扫描（属性扫描，一颗 jar）——**越大越差；物化是结构性成本**

border，与 SL 交错（该窗口 SL=4.08/4.13）：

| tile | minPass | 通道结算 | 物化 |
|---|---|---|---|
| 1×1 | 10.28 / **5.87** | 8.53 / **4.43** | 6.18 / **2.07** |
| 2×2 | 9.43 / 7.22 | 7.86 / 15.15 | 4.61 / 10.30 |
| 4×4 | 15.87 | 16.48 | 10.33 |
| SL | **4.08 / 4.13** | — | — |

两个结论：
1. **增大 tile（即使配上懒式物化）无益**——tile 越大，其 reach 集合随区块数增长、每次结算的打包/发布成本随脏 section 数增长，
   4×4 还要付最长的首触提取。**tile 问题就此关闭在 1×1**（eager 下否决、lazy 下同样否决）。
2. **border 的主导成本是材质物化，且是结构性的**：散布改动要物化 ±16 格范围 = 每区域 9 区块 × 1-2 个**混合**
   section（混合 section 无法认证——石头+空气正是没有 palette 捷径的情形），~100 µs/section 逐格走。
   **1.x 完全不付这笔**：它的区域图像**就是**存储，没有"派生"这一步。这就是 border 差距的机制。
   关掉它需要"格粒度的部分物化"——但 reach 盒是 33³ 格 > 16³ 的 section，所以按 section 提取已经是更便宜的粒度，
   格粒度路径必须先赢过 palette 走查（而不是赢过填充）。

### 区块级材质平面（`cb93a8c`）——**border 的物化成本砍 6-17 倍**

结算级 trace（`-Dscalablelux.imageLaneDebug`）推翻了"物化是一次性首触"的读数：**每个 pass 都会新触达新的 y 波段与光环区块**
（~760 section/pass），且**同一 section 会被最多 9 个相邻 1 区块区域各走一遍**。

修法 = `ImageMaterialPlanes`（一个 section 的 opacity/emission 字节，提取一次、跨区域跨 pass 复用；上限 4096 section =
32 MB，超限整体清空——代价是一次重新提取、绝不可能静默出错）+ **精确性靠新 `LevelChunkSectionMixin`**：
`setBlockState` 的两个描述符（§18 的"唯一写入漏斗"）把改动的那一格**直接写进平面**；`recalcBlockCounts` 丢弃平面；
区块重载导致 section 对象更换 → identity 检查失败 → 重建。**平面精确跟随写入，不是近似。**

border 实测（与 SL 交错，该窗口 SL 4.06/4.07）：**matz 6178/2067 → 656/361 µs、结算 8.53/4.43 → 3.92/3.17 ms、
minPass 10.28/5.87 → 5.61/4.88**。tile 扫描复现（1×1 ≈ 2×2、4×4 更差）→ 默认保持 1×1。
**border 仍落后 SL 约 20-38%，但其最大单项已是 BFS（1.4-1.6 ms）——那是 SL 也在跑的同一段 BFS**，
所以剩余差距是我们的每结算打包/发布 + 较慢的 apply，不再有"材质派生"这一项。
调试坑：padded 基址必须用**局部** section 索引（overworld minBuildY=-64 会让世界基址为负 → arraycopy 越界消失）、
perl 处理两行签名会复制行、`gradlew | head` 仍会 SIGPIPE 打断构建。
