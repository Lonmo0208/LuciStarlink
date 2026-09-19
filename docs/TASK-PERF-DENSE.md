# dense_chunk_patch 诊断与修复（1.1）

> 这份文档记录 dense 档「我们比 ScalableLux 慢」这个**从未被诊断过**的差距是怎么查出来的，以及修完之后的
> 验证状态。天空档的记录在 `docs/TASK-PERF-SKY.md`，两边别混。

## 0. 一句话

**dense 的差距不在光照引擎，在我们自己「改方块」那条路上**：同一个 harness 施加同一批 2048 个 `setBlock`，
我们每次多花约 **256 ns**（`applyUs` 中位 1440 µs vs 对方 888 µs），而真正等引擎的 `waitUs` 我们反而**更短**
（593 vs 620 µs）。1.13 ms 总差距里，0.52 ms 出自这里。

## 1. 第一次归因差点搞错的地方（教训）

仪表打印的 phase 总计里 `job.materializeReach` = 24.4 ms / 62 个作业 = 394 µs/作业，占 `job.runtime`
的 73%，看起来就是元凶。**但那是 phase 总计，不是测量 pass 的增量**：这 24 ms 大部分落在预热/准备阶段，
而 dense 的单轮测量值只有 2.5 ms —— 拿 24 ms 去解释 2.5 ms 的差距是错的。

**规矩（再说一次）：只在「每轮增量」上归因。** 下面所有数字都来自同场交错运行里逐轮的字段。

## 2. 证据（同场交错，2026-09-19 09:45，世界协议 prepareRing=3 + settle 1000 ms）

| 每轮字段 | 我们（4 轮） | ScalableLux（4 轮） |
| --- | --- | --- |
| `elapsedUs`（整轮） | 3093 / 2499 / 2544 / 3117 | 2716 / 1640 / 2272 / 1367 |
| `applyUs`（施加改动） | 1476 / 1404 / 1083 / 1507 | 1179 / 880 / 896 / 856 |
| `waitUs`（等引擎） | 1015 / 593 / 893 / 643 | 1374 / 620 / 1188 / 376 |
| `rtJobUs`（运行期作业） | 有真实计算 | 0（同步引擎，作业为空） |

`applyUs` 是 harness 自己调 `level.setBlock` 的那一段 —— 它本该与引擎无关，**唯一能影响它的就是各引擎挂在
`checkBlock` 上的拦截器**。所以这 0.52 ms 是我们的拦截开销，不是光照计算的开销。

## 3. 根因

`ServerLevel#setBlock` → `checkBlock`，而我们在这个点上原本挂了 **3 段注入**（1 段可取消 + 2 段纯仪表），
每段都可能调一次 `shouldHandleBlockChange`；改方块真正重要的那一条路（`ServerLevelMixin.onBlockStateChange`
→ `enqueueBlockChange`）里还有第 4 次。而这个守卫内部有：

- `runtimeBackpressureActive()` → **`System.nanoTime()`**（Windows 上 ~25 ns）
- `isWorldgenWriteSuppressed()` → **`ThreadLocal.get()`**
- `LuxCompat.isSablePlotChunk(...)` → 先解 `getLevel()` 再查 Sable 存在性（顺序反了）
- 再加上 `LuxBenchmarkSupport.start()` / `recordSince()` 这对纯仪表调用

2~3 次 × 60 ns 上下 + 仪表，正好对上实测的 ~256 ns/方块。

## 4. 修复（全部行为不变）

1. **三段注入合并成一段 HEAD**：一次 `checkBlock` 只问守卫一次，取消与计时都在同一处决定，注入顺序不再有意义。
   原版 `checkBlock` 的计时改挂在本方法与 RETURN 之间（`checkBlockStartedAt` 哨兵）。
2. **背压不看时钟**：`runtimeBackpressureActive()` 先读期限、只在期限非 0 时读时钟；`tickRuntime` 里加
   `refreshRuntimeBackpressure()` 把过期期限清零（过期期限本来就挡不住任何东西，清零只是记账）。
   顺带修掉一个隐患：期限为 0 时旧写法在 `nanoTime()` 为负的平台上会误判为「正在背压」。
3. **世界生成抑制抽出 `WorldgenWriteScope`**：语义仍然是**按线程**（异步区块生成线程各自可能同时处于作用域内），
   但读之前先看一个全局计数快路径 —— 计数为 0 时任何线程的深度都必然是 0，ThreadLocal 就完全不出现在热路径上；
   只有真的开着作用域时才回落去看本线程深度。不成对的退出把计数钳在 0，否则之后真正的作用域会被它减到 0
   而在仍然开着的时候解除压制。
4. **Sable 先查存在性**：没装 Sable 时（绝大多数玩家）用缓存布尔值直接回答，不再解 getter。

## 5. 验证状态

- **单测**：`WorldgenWriteScopeTest` 5 条（作用域内压制、嵌套、不成对退出、别人作用域不影响本线程、
  别人退出不结束本线程作用域）✓。全量 **29 个测试 0 失败 0 错误**（原 24 个 + 新 5 个）。
  ← 最后两条专盯「把 volatile 快路径写成朴素布尔」会犯的错。
- **验收门 `verify.sh`**：构建 + 测试通过、rig jar 元数据正确、halo/externalRefresh 证据在、我方日志 0 错误 ✓。
  （该轮的性能数字不可用：环境出现 14~15.6 ms 的调度饥饿，`waitUs` 13.7 ms 而 `applyUs` 仅 106 µs ——
  正好说明修复没问题、是机器的问题。见 §6。）
- **dense 修复效果**：待 5 轮交错链（见 `docs/HANDOVER.md` 的当前状态）填数。

## 6. 环境退化的判读（一次记录，别再来回猜）

退化时我们这侧的特征是：`applyUs` 正常（~106 µs）、**`waitUs` 撑满整轮**、`queueLatUs` ≈ 14 ms、
`drainUs` 极小。14~15.6 ms ≈ **Windows 默认调度量子**，说明运行期作业所在的线程整整一个量子没被调度 ——
是调度饥饿，不是我们的计算慢。同一时刻同步引擎（ScalableLux）几乎不受影响，因为它的工作骑在服务器线程上。

顶部累计 CPU 消耗里反复出现的是 `FanControl` / `RadeonSoftware` / `CPUMetricsServer`（风扇控制是安全功能，
**不要杀**）。判据仍是：任一侧 `wall ≳ 20 ms` 就重测。

## 7. 还没动的残留（都记了原因）

- `RuntimeUpdateQueue.enqueue` 每次把 `long regionKey` 装箱成 `Long` 再进 `ConcurrentHashMap`
  （~20-40 ns/方块）。要消掉得加「单槽缓存 + 代次校验」，而队列每轮会移除已排空的 region —— 缓存必须
  带代次否则会**静默吞掉改动**，风险高于收益（估计 30-60 ns/方块）。
- `runtimeBulkScope.get()` 仍是一次 ThreadLocal 读取（~7 ns），批量写入作用域本来就少见，暂不动。
- `ServerLevelMixin` 里每次改动一次 `getChunkNow`（~15 ns），原版自己的光照路径也要拿区块，暂不动。
