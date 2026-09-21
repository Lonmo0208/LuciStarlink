# 在 ScalableLux 上叠加 Lucis/LuciStarlink 的优化理念 —— 立项说明与计划

* 分支：`lucis-ideas`（基线 `master` = ScalableLux for NeoForge 1.21.1，`0.3.0-alpha.0.8`，**LGPL-3.0-only**）
* 日期：2026-09-21
* 决定人：用户（「这次我们不再从 Lucis 开始搭建，而是直接从 ScalableLux 上加上 Lucis 的优化理念」）

## 0. 为什么是这个方向（依据本项目自己的三方测量，不是感觉）

社区三方脚本（`E:\LuciStarlin\threeway.ps1`，未改；默认协议；6 轮拉丁方；权重 45/35/15/5）与单档 12+12 复现：

| 档 | 我们 1.2.10 ÷ ScalableLux | 精确 p | 结论 |
| --- | --- | --- | --- |
| `block_toggle_border` | **1.2954** | 0.0173（U=113/31） | SL 明显更快 |
| `dense_chunk_patch` | **1.132** | 0.0087 | SL 更快 |
| `sky_hole` | **1.391** | 0.0087 | SL 更快 |
| `structure_cube` | **0.819** | 0.0022（U=0/36） | 我们更快 |
| 加权 | 0.9339 | — | 由 `structure_cube` 的绝对差值带过去 |

即：**同步引擎的底座更快**（光照结果永远是真值、不吃 tick 调度），而我们那条路线里真正有价值的，不是引擎本身，
而是围绕着它的一批工程理念。于是本分支的目标不是再写引擎，而是：

> **底座用 ScalableLux，把它缺的那些理念一条条加上去，每条都拿数字验收。**

## 1. 候选理念清单（按"有没有数字支撑"排序）

| # | 理念 | 我们那边的证据 | ScalableLux 现状 | 价值 |
| --- | --- | --- | --- | --- |
| **A** | **大批量写入按"批"处理**：一个 tick 内的大批量改动合成一次区域级重算，而不是逐方块传播 | `structure_cube` 我们 0.819（U=0/36，p=0.0022） | 逐 `blockChange` 入队（`StarLightInterface.blockChange` → `LightQueue`） | 唯一一项我们赢 SL 的能力 |
| **B** | **客户端光照流量减半** | 1.2.5 实测 **−51%** | `ThreadedLevelLightEngineVanillaInterface` 按变更节发 `DataLayer` | 玩家侧带宽/帧率 |
| **C** | **内存有界 + 遥测**：字节预算、合并表清理、批量 scope 泄漏回收 | Lucis 内存审计五条（见主项目 `LUCIS-NOTES.md`）| 按区块持 nibble，天然有界；**完全没有遥测** | 长挂服的可见性 |
| **D** | 懒材质 / 懒 halo（初始化 435.3 → 0.87 ms） | 1.x 实测 | 无区域映像 → 天然没有这块 | 不适用（记为"不需要"） |
| **E** | 游戏内自省命令 + 诊断计数 | `/lucistarlink relight`、`dumplight`、内存遥测 | **无任何命令** | 运维/排障 |
| **F** | 保存/重启正确性钩子 | 1.1.5（重启截断）、1.2.7（世界生成自然光逐位一致）、1.2.9（跨区去光收敛） | **已有两条**（`force light incorrect before save`、`append unsaved lighting`） | 见 §10 |

**候选状态（2026-09-21 更新，逐条都有记录）**：**A** 关闭（§8，传播无冗余可合）、**B** 关闭（§9，发包路径已是最小集）、
**D** 不适用（§1）、**F** 逐条核对完成（§10，两条钩子等效或更强；1.2.7 的实证探针未跑，成本已标）、
**C+E** 已实现并验证（§11）。

## 2. 先做哪一条：**A**，因为它对着我们唯一赢的那一档

`structure_cube` 是我们唯一决定性领先的负载 → **"批量一次性重算"在这个负载上确实有效**。
把它搬到 SL 上若能 8.703 → < 7.5 ms，SL 就在四档上全面不劣于我们。这是本分支最有价值的一步。

**但第一步不是改代码，是量**：SL 在一趟 `structure_cube` 的 8.7 ms 里，时间花在哪（入队 / 传播 / 发布 / 发包）？
这个问题现在**能**回答 —— 因为我们可以编译**带计数的 SL**（这正是本分支相对"只拿官方 jar 对比"的最大不同）。

## 3. 测量协议（本分支纪律）

* 被测对象是**同源代码树的两个 jar**（未改 SL vs 改后 SL）；**不用**"官方发布 jar vs 我们的 jar"（版本不同不可比）。
* 运行方式：jar 放进 `LuciStarlink/run-benchmark-scalablelux/mods/`，命令
  `gradlew runBenchmarkScalableLuxServer -PbenchmarkWorkload=... -PbenchmarkOutput=... -x prepareBenchmarkScalableLuxMods`。
  **`-x` 是必须的**：`prepareBenchmarkScalableLuxMods` 是 `Sync`，不排除它会把我们的 jar 删掉换回下载版。
* 量具仍是主项目的 `LuxServerBenchmark`（`lucistarlink.enabled=false`，引擎开关关掉、只有量具在跑）。
* 统计：per-pass 最小值（`minPassNanos`）取中位数；同会话交错 ≥6 轮；精确双侧 Mann-Whitney（n=6+6 → 924 组）。
* 便宜档（border / sky_hole）6 轮 p 落在 0.05~0.35 = **功效不足**，要 ≥12 轮才能下结论（主项目 §17 / §18.5 的教训）。
* 任何"更快"都必须同时给**正确性**证据（沿用主项目的影子差分 / 逐位对比做法）。

## 4. 里程碑

* **M0**（2026-09-21）：分支 + 基线构建 + 自定义 SL jar 的基准通路 + 本文件。
* **M1**：SL 侧计数/剖析 → `structure_cube` / `dense` 的时间分解（入队、传播、发布、发包各占多少）。
* **M2**：按 M1 数据选一条落地（默认候选 A），出 A/B 数字。
* **M3**：汇总四档 + 与主项目 1.2.10 的同场对比（沿用三方脚本口径）。

## 5. M1 结果：SL 的开销在"每改动的光照挂钩"，不在传播（2026-09-21）

**做法**：在 SL 里加了一个**默认关闭**的 profiler（`-Dscalablelux.profile=true`，
`ca.spottedleaf.starlight.common.debug.LuxProfiler`）：计数器全量累加；**计时按 1/16 采样再乘 16**
（一次 `nanoTime` ≈25 ns，4096 次改动/趟若每次都计时会凭空多 ~0.2 ms，那会污染它要解释的东西）。
打印行前缀 `SLPROF`（每 2 s 一行 + 关闭时一行）。配套两件：主项目 `LuciStarlink/build.gradle` 加了
`-PslProfile=true` 透传到 `-Dscalablelux.profile`；rig 脚本 `mc-smoketest/sl-jar.sh` 把自定义 SL jar
放进 SL 运行目录（**必须 `-x prepareBenchmarkScalableLuxMods`**，那是个 `Sync`，否则它会把我们的 jar 删掉换回下载版）。

**结果**（`structure_cube`：一趟 4096 次改动 × 3 趟测量；带计数的 jar，minPass 7.69 / 8.83 ms，
与官方 jar 的 8.70 ms 中位数同级 → 仪器开销可忽略）：

| 计数 | 值 | 折算 |
| --- | --- | --- |
| `checkBlock` 调用 | 17,839（测量窗口 12,288） | — |
| 单次采样计时（×16 还原） | **666 ns/次** | 12,288 次 ≈ 8.2 ms/run ≈ **2.7 ms/趟** |
| 真正的传播 `runLightUpdates` | 57 次 = **2.74 ms** | ≈ **0.9 ms/趟**（~10%） |
| 票据路径分类 | `qAlready=16,384`、`qTicketAdds=9`、`qInline=0`、`qResched=0`、`qNotReady=1,451` | 每个改动都走到"票据已加"早退 |

**读法（M2 的依据）**

1. **每改动的光照挂钩 ≈ 666 ns**：`pos.immutable()` + lambda 分配 + `getAnyChunkNow` + 持久状态判断 +
   `blockChange` 的队列插入 + 票据分支。一趟 4096 次 → **2.7 ms，占一趟 8.7 ms 的约 31%**。
2. **真正的传播只占 ~0.9 ms/趟**：瓶颈不在传播算法，而在**每改动的调度簿记**。
3. `qAlready = 16,384 = 4 趟 × 4096`：票据只在第一批加了一次（`qTicketAdds=9`），其余全是"已加过"早退 ——
   被浪费的时间**几乎全部是可以按批合并的簿记**。
4. 对照：我们自己那侧同一负载 apply 6.7 ms/趟（SL 8.3 ms/趟），差 ≈1.6 ms/趟 —— 正是这个钩子的量级。

**M2 的目标形态（候选 A 的具体化）**：把 `checkBlock` 的记账从"每改动一次"改成"**每批一次**"
（chunk 查找 / 状态判断 / 票据 / ChunkTasks 取用按批合并，只有 `blockChange` 的入队仍逐位置）。
预期 666 → ~200 ns/次，一趟省 ~1.9 ms：**8.7 → ~6.8 ms，低于我们 1.2.10 的 7.13 ms**。

**必须记的诚实点**：量具 jsonl 里的 `applyNanos` / `waitNanos` 相位分解**会漂**（官方 jar 那批 SL 的 wait ≈0.3 ms，
我们自建那批 wait 8.8 ms，而总数相近），所以**相位分解不可跨会话比**；稳的是 profiler 的每次调用计数
和 per-pass 的 `minPassNanos`。

**下一步（M2）**：先做"批次簿记"这一条，代码只动 `ThreadedLevelLightEngineVanillaInterface`（+ 必要时
`StarLightInterface` 的入队入口），然后按 §3 的协议做同会话 A/B，并跑正确性探针（refill / nether / 差分）。

## 6. M2-1：把逐改动的簿记按批合并 —— 机制成立、指标中性（2026-09-21）

**改动**（`ThreadedLevelLightEngineVanillaInterface`；开关 `-Dscalablelux.batchLimit=N`，**默认 1 = 关**）：
`checkBlock` 把位置缓冲到"当前 section"，section 变了或缓冲到 N 就先 flush；flush 时只有**第一个**位置走完整调度
（chunk 查找 / lit 检查 / 票据 / `ChunkTasks`），其余只调 `lightEngine.blockChange(pos)`；顺序保持 FIFO；
所有可能观察或排空队列的方法（`hasLightWork` / `runLightUpdates` / `updateSectionStatus` / `lightChunk` /
`waitForPendingTasks` / `close`）在入口**先 flush** —— 传播只可能发生在 `runLightUpdates`，所以不存在"看见半批"的窗口。
`scalablelux$queueTaskForSection` 改为返回 `ENQUEUED / DROPPED / DEFERRED`，flush 据此决定其余位置怎么入队
（`DEFERRED` 时逐个重走，避免绕过线程检查）。

**测量**（**同一个 jar**，只有 `-Dscalablelux.batchLimit` 不同；`structure_cube`；3+3 交错）：

| 侧 | minPass 中位数 | applyNanos 中位数 | waitNanos 中位数 | `queueTask` 调用 | 每改动钩子耗时 |
| --- | --- | --- | --- | --- | --- |
| 关（1） | 7,915,100 | 14.59 ms | 17.20 ms | 17,924 | 666 ns（旧构建实测） |
| 开（256） | 7,951,600 | **12.98 ms** | 24.27 ms | **204** | **~91 ns** |

**三条结论**

1. **机制完全成立**：`queueTask` 17,924 → **204**（每批只走一次调度），每改动钩子 666 → ~91 ns，
   **apply 相位实测降了约 11%（14.59 → 12.98 ms）**。
2. **指标没动**（minPass 7.92 vs 7.95 ms）：省下的时间在 **wait 相位重新出现**（17.2 → 24.3 ms）——
   说明**瓶颈不是"每改动的簿记"，而是光照引擎为这批改动真正要做的工作**；按批合并入队只是把工作从
   服务端线程挪到了光照线程、挪到了更晚。
3. **默认因此保持关闭**（`batchLimit=1`，与上游行为一致），但开关留着，下一条改进能在**同一个 jar** 上直接 A/B。

**下一条要试的**：不再动"入队"，而动**这批改动在光照线程上要做的工作量** —— 也就是候选 A 的本体：
一批改动**合成一次区域级重算**，而不是每个位置各自触发一次传播/重新点亮。SL 侧对应入口是
`StarLightEngine` / `SkyStarLightEngine` 的传播循环与 section 变脏后的处理（`increaseQueue` / `decreaseQueue`）。
动手前先用 profiler 量出"一趟里每个位置平均被传播触及几次"。

**诚实点（必须一起引用）**：n=3 的 A/B 只能算"未判定"，不是"无差别"（§3 的规矩：≥6 轮，便宜档 ≥12 轮）。
上面第 2 条敢说"指标没动"，是因为差值只有 0.5% —— **远小于这个量具 6 轮噪声带**，不是因为 n=3 有把握。

## 7. 口径：报的 `minPassNanos` 与真实 wall 是两件事（2026-09-21）

量具里除了 `minPassNanos`（**刻意排除 tick 跨越**）还有一个 `bench.pass_wall_actual`（"apply → 第一个通过的
barrier"，代码注释明确写它"包含光照工作必须跨过的服务端 tick 边界"）。同会话交替 3 轮、`structure_cube`：

| 侧 | minPass 中位数 | 真实 wall 每趟 | 折算 tick |
| --- | --- | --- | --- |
| 我们（LuciStarlink 1.2.10） | 3.44 ms | **99.1 ms** | ≈2 |
| ScalableLux（本分支基线，批处理默认关） | 7.29 ms | **66.5 ms** | ≈1.33 |

（wall 三次 spread <0.5%，因为它被 tick 量化；完整记录见主项目 `docs/TASK-PERF-SKY.md` §19。）

**对移植计划的直接影响**

1. **验收必须两个口径都看**：`minPassNanos` 回答"引擎算得快不快"，`pass_wall_actual` 回答"玩家的光照什么时候
   落地"。只优化前者可能在后者上一动不动（M2-1 就是活例子：apply 实测降 11%，指标没动）。
2. **SL 的底座在"落地延迟"这一项上本来就更优**（66.5 vs 99.1 ms）——这是本分支方向的直接支撑，也是候选 A
   必须守住的既有优势：任何"按批处理"都不能让 wall 变差。
3. 候选 A 的验收标准因此写成：**wall 不劣化 + minPass 不劣化 + 正确性探针通过**，而不是只看 minPass。

## 8. M2-2：传播量仪表说"区域级合并重算"没有肉 —— 候选 A 关闭（2026-09-21）

按 §6 的约定，动手改传播循环之前先量"每个改动在传播里被触及几次"。仪表：`StarLightEngine` 池化实例上的
线程封闭计数（tasks / positions / setLightLevel 写入格数 / BFS 队列触达数），随实例回池汇入 `SLPROF` 行。
关闭时全部是 `if (enabled)` 短路，基线路径零开销。构建 md5 `4f1a56e48095fbfde1eddad4802f966f`；
带仪表的 minPass 中位 9.42 ms（structure_cube, n=3）> 无仪表 8.86 ms —— **仪表读数可信，仪表 jar 的性能数字不可用于 A/B**。

**测量窗口的传播量**（SLPROF 首末行差值；世界生成的 188k 次 sky 写入在窗口前，已排除。r1/r2/r3 总额差 <1%）：

| 负载 | 每改动 sky 写入格 | 每改动 sky 队列触达 | 每改动 block 写入格 |
| --- | --- | --- | --- |
| `structure_cube`（4 趟 ×4096 = 16,384 改动） | **2.1** | **3.1** | **1.0** |
| `dense_chunk_patch`（4 趟 ×2048 = 8,192 改动，光源） | **21.1** | **26.6** | **1.4** |

**读法**

1. `structure_cube`：每个改动只让传播多写 2.1 格、多碰 3.1 次 —— **SL 的传播工作集已经是改动量的常数倍**，
   而且它本来就有列级合并（`SkyStarLightEngine` 的 `heightMapBlockChange`：4096 位置先并成 ≤256 列再重算
   光源）。"一批改动 → 一次区域级重算"能省的冗余在这个常数里**不存在**。
2. `dense_chunk_patch`：21 格/改动是光源（荧石）光照按 14 邻域扩散的**物理工作量**，不是浪费 —— 合并算法改不了它。
3. 结合 §5/§6：一趟 `structure_cube` 的成本 = 每改动挂钩 ~666 ns（M1）+ 传播 ~0.1–0.9 ms + 其余在 apply 的
   `setBlock` 本体。M2-1 证明挂钩可以按批砍到 ~91 ns，但 minPass 由**跨 tick 等待**主导（§7），砍了指标不动。

**结论**：候选 A 在 SL 底座上没有剩余的可测收益 —— 它的簿记部分（M2-1）机制成立但被 tick 量化吃掉，
它的传播部分（本轮）本来就没有可合并的冗余。我们 `structure_cube` 赢 SL 的那 0.819 来自**异步架构**
（apply 不等光照落地、发布侧 marker 同线程完成所以 minPass 小），那是与 SL 的同步底座互斥的路线，
按 §8 的边界不搬。**A 记为"已关闭"，后续价值排序变成 B → C → E → F。**

诚实点：仪表 n=3，只用于计数读数的稳定性核对（跨轮 <1%），不是性能结论；本轮对 A 的判决依据是
计数量级（2.1 格/改动）与 §5/§6 的既有定性，不是 n=3 的 minPass。

## 9. 候选 B（客户端光照流量）：底座的发包路径本来就是最小集，−51% 的问题在这边不存在（2026-09-21）

先读代码，把服务端发包的每一步钉死（vanilla 1.21.1 源 + 本树）：

1. `ChunkHolder.sectionLightChanged(layer, y)`：**逐层**记录过滤器（sky 一个 BitSet、block 一个），互不牵连。
2. `ChunkHolder.broadcastChanges`：每个 chunk **每 tick 至多发一个包**，包里只含本 tick 动过的节。
3. `ClientboundLightUpdatePacketData.prepareSectionData`：该层**空**的时候只写 mask 的一位，**不发 2048 字节**；
   只有非空层才带 2048 字节负载。
4. 本树的 `StarLightEngine.updateVisible()` 只对"可见副本真的变了"的节调 `onLightUpdate`（`nibble.updateVisible()`），
   **没有 3×3×3 扇出**；无玩家在线时 `broadcastChanges` 连包都不构造。

也就是说，1.2.5 那 −51% 治的是**我们自己引擎的 27 节扇出**，那个病灶在 SL 底座上不存在。剩下的唯一冗余是
"内容其实没变、只是被写法标脏"的发布（`set()` 会置 `updatingDirty`，即使写的是同一个值）。给它加了内容比对
（`SWMRNibbleArray.updateVisible()`：状态相同且字节相同 → 不发布），只有 **wire 内容逐字节相同**才跳过，按构造等价。

**实测**（本分支 jar `4e211baa`，profiler 开；jar 里的 `SLPROF` 计数）：

| 负载 | 整轮通知节数（sky / block） | 空转通知 idSkip | 占比 |
| --- | --- | --- | --- |
| `structure_cube` | 636 / 656（世界生成占绝大部分） | 4–8 | <1% |
| `dense_chunk_patch` | 1262 / 1402（4 趟测量窗口 +456 / +563） | 22–28 | ≈1% |

**诚实点**：量具里没有客户端（无玩家在线），所以这里量的是"服务端决定要发的节数"，不是线上字节；字节数可以按
第 3 条折算（非空层 2048 B/节）。要拿真实线上流量必须挂一个客户端，那是"客户端快速建造"那一条独立待办的事。

**结论**：候选 B 关闭 —— 底座的通知集已经等于"可见内容真的变了的节"（逐层、按 chunk 每 tick 合并、空层零负载），
没有可砍的一半；残余 ~1% 的空转通知已由上面的内容比对消掉（改动保留，默认生效，按构造等价）。**剩下价值排序 C → E → F。**

## 10. 候选 F（保存/重启正确性）：逐条核对结果 —— 两条钩子等效或更强，不需要移植（2026-09-21）

SL 的两条钩子（历史提交 `11bb0ad` append unsaved lighting、`16826e6` force light incorrect before save）+
`lightChunk` 的语义，对着我们三条正确性保证逐条读：

**SL 的机制（读 `SaveUtil` + `SerializableChunkDataMixin` + `ThreadedLevelLightEngineVanillaInterface.lightChunk`）**

1. **存**：`ChunkSerializer.write` 里先把 `isLightCorrect()` 强制成 false（vanilla 因此不写 `isLightOn`），
   注入我们的 nibble 字节后**再把真值写回** `isLightOn`；失败路径保持 false（vanilla 下次加载重算），
   并且**把区块节列表里没有的节追加进去**（未落盘的光照不丢）。
2. **读**：`read` 返回后先 `setLightCorrect(false)`，解析我们的 nibble，**成功才** `setLightCorrect(lit)`
   （`lit = isLightOn && 状态≥LIGHT`）；解析失败一律从 false 起 —— 要么数据完好、要么重算，不存在"半截数据当好的用"。
3. **加载后**：`lightChunk(chunk, lit)`，`lit` 由 `isLighted(chunk) = 状态≥LIGHT && isLightCorrect()` 得出；
   `lit=true` 走 `forceLoadInChunk`（**只装载、不重算**），`lit=false` 才真正重算。

**逐条对照**

| 我们的保证 | SL 的对应 | 判定 |
| --- | --- | --- |
| **1.1.5** 重启后光照只剩光源自身（`lightChunk` 对"照明已正确"的区块重算并把只有光源的场发布出去） | `lit=true` 的区块**不重算**（只装载）；"照明正确"只在数据解析成功后成立；本树**没有区域映像**，1.1.5 的根因（空 halo）不成立 | **等效或更强（按构造）**——病根不存在，且"已正确的区块不重算"被 `lit` 明确挡住 |
| **1.2.9** 跨区去光不收敛（区域映像的 halo 边界） | 无区域映像；去光在同一同步引擎里跨区块 BFS，另加边缘检查 | **不适用**——缺陷类别不存在。唯一残留：加载时的边缘检查是"尽力而为"（SL 自己的注释：邻居未加载时漏掉的由后续加载补），这是设计取舍不是缺陷 |
| **1.2.7** 世界生成自然光逐位一致 | 是完全不同的世界生成路径（`WorldGenRegionMixin` 明确在区块照明未完成时返回 0 来对齐 vanilla 的蘑菇生成行为）；没有 halo 材质这个量 | **不同机制，需实证**：本树的对应检查是"下界顶棚带 vs vanilla 逐位比较"（我们那份探针的等价物）。读代码只能证明它在**有意对齐 vanilla**，不能证明逐位 —— 这一条要跑探针才算检查过，本轮没跑（见下） |

**诚实点**：这是一次**读代码级**审计，不是实证。1.1.5/1.2.9 两条按构造可判（根因不存在 / 不重算被明确挡住），
1.2.7 只能算"机制不同、结论待测"——若要实证，得跑一次下界顶棚带探针（SL 侧两个服务端启动 ≈2 次启动成本）。
在此之前不要对外说"1.2.7 在 SL 上也成立"。

**结论**：F 关闭 —— 两条钩子在语义上覆盖（且部分强于）我们的保存/重启保证，没有要移植的代码；唯一待实证项
是 1.2.7 的世界生成逐位一致，成本已标在上面。

## 11. 候选 C+E（遥测 + 自省命令）：SL 完全没有的那一块，已实现并验证（2026-09-21）

**C 遥测**：`LuxTelemetry`，每 `-Dscalablelux.telemetrySeconds` 秒（默认 30，0 关闭）一行
`SLTELEM dim=… tasks=… dirty=… poolSky=… poolBlock=…`，由**两条路**驱动：服务器 tick（空闲时也有心跳）+
光照引擎自己的更新入口（`runLightUpdates`，即使 tick 钩子失效也有输出）。数据来自 `StarLightInterface.lucisStats()`
（队列任务数 / 脏位置数 / 两个池化引擎池的大小），全部是"不阻塞引擎的近似读"，对健康线足够。

**E 命令**：`/scalablelux`（需要 op，权限等级 2）
* `stats` —— 与遥测同一行的状态，外加 `batchLimit` 与 profiler 开关；
* `light <pos>` —— 该位置 block/sky/raw 光照 + 所在区块是否自称光照正确（判断"这块黑是引擎的问题还是地图本来如此"）；
* `relight <radius 1..8>` —— 已坏存档的修复路径：把半径内已加载且自称正确的区块标成"光照不正确"，
  交给 `lightChunk(chunk, false)`（**就是 vanilla 生成时的 LIGHT 步骤本身**），完成后回报区块数。
  半径上限 8（=289 区块）防止误输入拖住服务器；修边界要留 2 圈余量，因为边缘光照靠邻居。

**实现期发现的坑（值得记）**：本仓库的 `IEventBus`（bus 8.x）**只有 `register(Object)`，没有 `register(Class)` 重载**，
所以 `NeoForge.EVENT_BUS.register(LuxTelemetry.class)` 会**编译通过**（绑定到 `register(Object)`）却只扫实例方法，
**静默什么都不注册、没有任何报错**——第一轮实现就是这样，SLTELEM 一行不出。改用 `@EventBusSubscriber(modid=…)`
后正常。改这种钩子后一定要看输出里有没有东西，别只看编译过了。

**验证**（jar `01f691d2`，`structure_cube` 跑的基准服，`-Dscalablelux.telemetrySeconds=2`）：
* 启动行出现：`ScalableLux active: telemetry every 2s (0 disables), /scalablelux for stats and relight`；
* `SLTELEM dim=minecraft:overworld tasks=1 dirty=1 poolSky=0 poolBlock=0`、`… tasks=0 dirty=0 poolSky=2 poolBlock=2`
  —— 两次采样一次在跑光、一次已静默，数字与基准进程的状态对得上；
* 注册行出现：`Registered /scalablelux (stats, light, relight)`。

**诚实点（必须一起读）**：注册与遥测是**实测**；三个子命令**没有在真实控制台里执行过**——基准跑的 gradle
不转发 stdin（试了 `help` 也没回显），所以"命令能注册、能建图"已被证明，"点下去会发生什么"只有代码级把握
（`stats` 的数据源就是遥测同款；`light` 只用 `getBrightness`/`isLightCorrect`；`relight` 只用 vanilla 自己的
`lightChunk` 入口）。要坐实得在一个能接控制台的服务器上敲三条命令，成本 = 一次启动。

## 12. 差分正确性探针：SL 与 vanilla 逐位一致，并顺带在我们自己的引擎上量出 80 格差异（2026-09-21）

**工具**（做进量具 `LuxServerBenchmark`，不是做进 mod —— 三种模式都能用，且不进发布件）：
`-PbenchmarkLightFingerprint="x1,y1,z1,x2,y2,z2"` 给一个盒子的光照/方块指纹（含按区块的天空光小计），
`-PbenchmarkLightDump=<path>` 写整盒 sky/block/**states** 三个转储，`-PbenchmarkLightDiff=<ref.sky>` 在运行时对参考
转储做差分（分别报 `skyDiff` / `blockDiff` / `stateDiff` / **`lightDiffWithStateSame`** —— 最后一列才是归因：
方块相同而光照不同 = 引擎差异；方块不同 = 地形/时序噪声）。跑法：`vanilla-dump.sh`（vanilla 参考）+
`sl-jar.sh`（其它引擎），`RTS=0` 冻结随机刻。

**两个量具教训（都花了一轮才看清，值得记）**

1. **"队列空"不等于"引擎算完"**：第一版在趟末 barrier 上直接取指纹，报出"SL 比 vanilla 每区块多 15 级光照"的差异
   —— 而同一轮写下的转储逐字节相同。线程化引擎会把传播交给工作线程，队列先空、光照后落。改成**连续两次全量
   读取一致才算落定**（本树 3 趟稳定，日志会打 `settled after N passes`），差异即消失。
2. **哈希定位不了任何东西**：同一份数据里"先读的摘要"和"后读的转储"都能不一致（都在读移动中的状态）。
   要结论就得有**逐格转储 + 差分**，并且必须带 states 一起比才能归因。

**结果 A（对 SL 有利）**：`structure_cube`、599,040 格盒子（x -40..55, y 32..96, z -40..55，含 harness 的编辑区与
周边世界生成地形），三次引擎各跑一遍、RTS=0、落定后：

| 引擎 | sky | block | skyNonZero / skySum | blockNonZero / blockSum | airCells |
| --- | --- | --- | --- | --- | --- |
| vanilla（参考，两次跑相同） | `905931078dfc5ace` | `59e2252f732ce67b` | 396,436 / 5,178,541 | 273 / 584 | 295,732 |
| **ScalableLux（本分支）** | **`905931078dfc5ace`** | **`59e2252f732ce67b`** | **396,436 / 5,178,541** | **273 / 584** | **295,732** |

即 **SL 的光照与 vanilla 逐位相同**（含世界生成 + 批量编辑后的传播）。这就是候选 F 里 §10 标为"待实证"的 1.2.7
那一条的实证 —— 诚实边界：盒子是**主世界**而不是下界顶棚带，且比的是**光照**（地形本身在两次跑之间有随机刻
/沙水移动带来的噪声，states 哈希不稳定，所以地形一致性不在这条结论里）。

**结果 B（对我们自己不利，必须带回去）**：同一个盒子上，**我们的引擎（主项目 1.2.10）与 vanilla 有 80 格天空光差异，
且 80 格全部落在方块相同的格子上**（`skyDiff=80 blockDiff=0 stateDiff=11 lightDiffWithStateSame=80`），
两次独立运行都是同样的 80 格。分布是两处贴区块边界的竖向暗带（约 (x -32..-28, z -16..-14) 与 (x 32..33, z -16..-7)，
y≥50），vanilla 值 1..5、我们是 0..4（整体偏暗 1~5 级）。**这是主项目的事，不属于本分支**：已记入主项目
`docs/TASK-PERF-SKY.md` §20，待做最小复现后再定性（我们的 bug 还是 vanilla 自身的怪癖）。

## 13. 边界与不做的事

* **不动主项目**：`E:\LuciStarlin\LuciStarlink`（1.2.10 发布线）保持原样，本分支是独立产物；测量时才借它当量具。
* **许可证**：SL 是 LGPL-3.0-only，与 Lucis 血统同协议；搬进来的代码逐条记进本文件的"来源/改动"表，发布件同样带 LGPL 声明。
* **不搬我们的异步引擎**：那一套的意义是"绕开 tick 调度"，而 SL 的根本优势恰恰是同步；搬过来等于把它最强的点拆掉。
