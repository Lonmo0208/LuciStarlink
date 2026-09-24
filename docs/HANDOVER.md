# 交接：2026-09-24 收工时的状态（换窗口请先读这份）

这份是"换窗口"用的现状快照。**代码与文档都已提交并推送**，所以新窗口从仓库就能接上，不需要这段对话的上下文。
配套材料：`docs/NEW-ENGINE-TEARDOWN.md`（全部测量记录）、`docs/BUG-EMITTER-BLOCK-LIGHT.md`（本轮所有 bug 的根因与验证）、
`docs/ARCHITECTURE.md`（归属与验收规则）、`CHANGELOG.md`（2.0.1 → 2.0.7 逐版）。

## 0. 一句话现状

**2.0.7：对 ScalableLux 三胜一负（引擎口径），玩家口径四格全部并列在 1 tick 地板；光照与原版逐位相同。
唯一没赢的是 `block_toggle_border`（5.62 对 4.12），而它是整个项目里唯一一个"同代码同结果却慢 1.5 倍"、
尚未解释的数字。** 对 1.x 线：引擎口径它仍领先三格，玩家口径我们四格 49–51 ms 对它 67–86 ms。

## 1. 仓库与发布物

| 项 | 值 |
|---|---|
| 工作树 | `E:\LuciStarlin\LuciStarlink-LS-V2`（= `master` = `LS-V2` 分支的 worktree） |
| 当前 tip | `56e88c9`（2.0.7），CI 全绿（该 commit 3 个 `build` check-run 全 `success`） |
| 分支 | `master` = `LS-V2` = `56e88c9`；`1x-line` = `472d897`（1.x 线 + 测试台，未动） |
| tag | `v2.0.1` … `v2.0.7`（1.x 的 11 个 `v1.2.x` 未动） |
| 本地产物 | `E:\LuciStarlin\sl-jar\lucistarlink-2.0.7-release.jar`，md5 `b718ccae18dfbfa6aef77a9097918165`（**发布件不上 GitHub**，只推 tag） |
| 测量用的 rig jar | `sl-jar/ls2-varA-rig.jar`（mod id `lucistarlinkrig`）。**已逐类校验 = 2.0.7 的引擎**：92 个 class 里 91 个与发布 jar 逐字节相同，唯一差异是入口类名（`LuciStarlinkRigEntrypoint` / `LuciStarlinkEntrypoint`，由 mod id 决定）。所以下表 2.0.7 的数字就是这个 jar 跑出来的 |
| 远端的 2.0 线 tag | `v2.0.1`（首版）、`v2.0.2/.3/.4`（光照修复链）、`v2.0.5`（border 拿回）、`v2.0.6`（大批量方块光 NPE）、`v2.0.7`（本轮两格） |

## 2. 2.0.7 的成绩（同会话交替，1 轮；窗口负载 CPU 25.7%）

| 档位 | 我们 2.0.7 | ScalableLux | 1.x（另一个窗口） | 结论 |
|---|---|---|---|---|
| `structure_cube` | **3.09 ms** | 4.77 | 2.87 | 赢 SL；对 1.x 平/微负 |
| `dense_chunk_patch` | **3.72 ms** | 3.93 | 2.36 | 赢 SL；对 1.x 负 |
| `sky_hole` | **0.61 ms** | 0.95 | 0.81 | 赢两家 |
| `block_toggle_border` | 5.62 ms | **4.12** | 0.76 | **输**（唯一未解释项） |

玩家口径（`bench.pass_wall_actual`，中位数）：我们与 SL 四格 **48–51 ms**（一个 tick 地板），1.x 三格 67–86 ms。
`structure_cube` 的规范指纹 **`sky=905931078dfc5ace block=59e2252f732ce67b`**，与原版和 ScalableLux 逐位相同
（1.x 的是 `641356fc41163add`，两者都不相同）。

**证据强度要说清楚**：上表是**同会话交替的 1 轮**（不是 threeway 那种 6 轮拉丁方），所以
（a）三胜一负这个**方向**可信（每一格都在同一轮里背靠背跑）；
（b）单格的**倍数**别当定论引用，尤其 `dense` 的 3.72 对 3.93 只在噪声边缘——要对外用倍数就补跑 ≥3 轮；
（c）`block_toggle_border` 的差距（1.36×）远大于轮间波动，它不靠补轮数站得住。
要复现这张表：`bash /c/Users/Administrator/AppData/Local/Temp/zcode-two.sh`，改 `US20_JAR=E:\LuciStarlin\sl-jar\ls2-varA-rig.jar`。

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

## 4. 唯一未解释项：`block_toggle_border` 的 1.5 倍

**事实**：这一格两侧**光照逐位相同**（指纹 `sky=905931078dfc5ace`/`block=59e2252f732ce67b`）、
**世界状态哈希相同**（`states=b04e0824ac598fb9` 那次对照），跑的是上游的传播代码（源码 diff 无语义差异，
编译后字节码规模也相同：`performLightIncrease` 641 vs 662 条指令），而耗时 5.62 对 4.12。

**已逐一实测排除**：

| 嫌疑 | 实验 | 结果 |
|---|---|---|
| 我们加的仪表代码 | 把两个 BFS 里的计数/取样整段删掉重跑 | 无变化（5.84 对 6.03） |
| 我们自己的就地通道 | `-Dscalablelux.ownEdit=false` 走基底队列 | 仍慢（6.50） |
| 天空窗口重算策略 | `-Dscalablelux.recomputeSky=false` | border 反而好些（4.80 对 5.40），但不是全部差距 |
| 攒批缓冲大小 | `pendingFlushSize` 256/4096/8192 | border 不受影响（它的改动跨很多 tick，由 tick 钩子驱动刷新） |
| 批量整块重算 | `-Dscalablelux.bulkRelight=true` | −9%，噪声内 |
| 位置装箱 / 分配 | 改成打包 `long` 传参；JFR 对比 | 分配与 GC 两侧相同（73 vs 67 次 Young GC） |
| mixin 层 | 与上游逐文件 diff | `BlockStateBaseMixin`/`ThreadedLevelLightEngineMixin`/`LevelLightEngineMixin`/`LevelChunkMixin`/`ChunkAccessMixin` 语义完全相同，`ChunkMapMixin` 只多一个构造期开关 |
| 线程池/配置 | `parallelism` 两侧都是 cores/3 | 排除 |

**下一步该做的（按性价比）**：

1. **先拿到 SL 侧的 BFS 计数**：在 `ScalableLux-Master` 那棵树里把 `LuxProfiler` 的四个计数（`bfsPops`/`bfsNeighbours`/
   `bfsLevelSkip`）塞进它的 BFS，建一颗对照 jar，跑 border → 若 SL 的弹出/访问数明显更少，那差距是**工作量**（例如
   我们的刷新结构让它多走了一遍），而不是**单次成本**。这是唯一能把"1.5 倍"劈成两半的实验。
2. 若确认是"单次成本"：查 JIT 层面（两份 jar 的 `-XX:+PrintCompilation`/`-XX:+UnlockDiagnosticVMOptions
   -XX:+PrintInlining` 差异，或把非引擎类整类剔出构建后对照）。
3. 若确认是"工作量"：border 的改动是"每区块 ~5 处、跨 ~19 个区块、跨多个 tick"，所以要动的是**跨 tick 的累积**
   （例如让 tick 钩子对"本轮没有新改动"的区块不重复抽干，或者让抽干只处理本次新增的队列条目而不重走全场）。

**另一条已论证但对 border 无效的路**：把方块光也做成"窗口重算"（复用天空那套）。它对 `dense` 有效（格子密集，
窗口 92k 格 / 2048 改动 ≈ 45 格每改动，约快 2 倍），但 border 的改动稀疏（窗口会到 1.75M 格，反而慢十倍）——
所以 border 只能靠上面 1–3。

## 5. 常用操作（照抄）

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

- **四档三方验收**：`bash /c/Users/Administrator/AppData/Local/Temp/zcode-two.sh`（改脚本里的 `US20_JAR` 指向新 rig jar、`OUT` 换目录；36 轮约 30 分钟；它自带端口清理与负载采样）。
- **单光源/跨区块梯度门**：`bash /tmp/gate205.sh`（= `tools/emitter-gate/` 的变体；期望 15/14/14/14/13/10）。
- **`/tmp/repro-chunk.sh`**：在玩家存档副本上做 8192 格填/清并转储 A/B（本轮抓 NPE 用的就是这个）。
- **端口卫生**：25665 原版 / 25666 scalablelux / 25667 lucistarlink / 25668；跑前 `netstat -ano | grep :25666`，有残留就按 PID `taskkill /F /T`。
- **机器卫生**：收工前 `Stop-Process` 掉 `fabric.dli`（游戏）与 `runServer`；注意 Git Bash 的 `taskkill /F` 会失效，要用 `cmd //c`。

## 6. 2.0 架构现状（一句话版）

引擎主体（存储/增量传播/区块与世界生成管线/原版接口/客户端发包）是 **ScalableLux 的代码**；
**更新回路是我们自己的**：就地编辑通道、按区块攒批（缓冲现在 8192）、合并减光抽干、**窗口化天空收口**、
守卫提升到区块级、方块光空写跳过；外加遥测/命令/profiler/Sable/配置/存档保证。
`structure_cube`/`dense` 的改动走"延迟天空 + 窗口重算"，`border`/`sky_hole` 走就地通道 + 逐区块/分组结算。
细节见 `docs/ARCHITECTURE.md`。

## 7. 对外口径（引用数字时一并带上）

- 2.0.7 = 对 ScalableLux **三胜一负**；`block_toggle_border` 仍输，且是唯一未解释项。
- 对 1.x：引擎口径它领先三格（0.76/2.87/2.36），玩家口径我们赢三格（49–51 对 67–86 ms）。
- **任何引用旧表的场合都要注明**：2.0.4 之前那些"四档全胜"是在方块光半边不参与的情况下测的。
- 我们的光照与原版/SL **逐位相同**（指纹为证）；1.x 的不是。
- 发布件只在本地 `E:\LuciStarlin\sl-jar\`，GitHub 上只有 tag。贡献者的 `threeway.ps1` **不许改**。
