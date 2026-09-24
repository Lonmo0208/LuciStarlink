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
