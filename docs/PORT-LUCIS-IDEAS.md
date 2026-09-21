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
| **F** | 保存/重启正确性钩子 | 1.1.5（重启截断）、1.2.7（世界生成自然光逐位一致）、1.2.9（跨区去光收敛） | **已有两条**（`force light incorrect before save`、`append unsaved lighting`） | 需逐条核对是否等效 |

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

## 8. 边界与不做的事

* **不动主项目**：`E:\LuciStarlin\LuciStarlink`（1.2.10 发布线）保持原样，本分支是独立产物；测量时才借它当量具。
* **许可证**：SL 是 LGPL-3.0-only，与 Lucis 血统同协议；搬进来的代码逐条记进本文件的"来源/改动"表，发布件同样带 LGPL 声明。
* **不搬我们的异步引擎**：那一套的意义是"绕开 tick 调度"，而 SL 的根本优势恰恰是同步；搬过来等于把它最强的点拆掉。
