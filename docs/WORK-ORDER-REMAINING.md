# 剩余功能工作单（客户端光照接管 / 作业内并行 / 天空档决定）

> 这份写给下一个窗口。当前发布线 `master` = **1.1.4**（`dist/[光源优化模组V1龙师傅特供]lucistarlink-1.21.1-1.1.4.jar`，
> md5 `1b4ff645afcac146ab3347c8a5dd3b61`，26 测试全绿）。性能现状见 `docs/TASK-PERF-SKY.md` §10.12：
> 同场 5 轮交错下 **两个负载赢、两个输**（border 1.78× 快 / structure 1.36× 快但不显著 / dense 1.25× 慢 / sky_hole 1.58× 慢）。

## 1. 客户端光照接管（未开始，工作量最大）

**是什么**：ScalableLux/Starlight 会连**客户端**的光照计算一起替换；我们是纯服务端，客户端仍跑原版光照引擎。
我们的光照数据经正常的 `queueSectionData` + 通知路径送到客户端（客户端用例已通过：边界上放光源，客户端立即变亮 ✓），
所以这不是"客户端没光" ❌，而是**客户端仍要自己算/维护一份光照**。

**能拿到什么**（诚实排序）：
1. 客户端省掉光照计算与内存（对低配机、大视距有实际体感）；
2. 客户端与服务端光照**同源**，消除"两端算法不同导致边缘闪烁"这一类问题；
3. 与前辈模组在"功能覆盖"上对齐 —— 目前这是我们在对比表里唯一明确写着"未做"的维度。

**计划**：mixin 进 `ClientLevel`/`ClientChunkCache` 的光照引擎构造点，用与 server 端相同的替换方式接一个客户端版引擎；
光照数据继续走既有包路径（`ClientboundLightUpdatePacket` / 区块包），客户端引擎只负责**存与查**，不重算。
**风险清单**（必须先写进任务书再动代码）：客户端区块的加载/卸载时序、`LightUpdatePacket` 的部分 section 更新、
每帧预算（不能把渲染线程拖慢）、与光影/优化模组（Sodium/Embeddium 等）的兼容、以及"客户端引擎读不到数据时必须有回退"。
**验收**：① 两客户端在同一区块边界观察，光照一致且立即更新（复用 `mc-smoketest/clientcase.sh` + `clientcase-evidence/` 的方法：
**全分辨率 F2 截图**对比，桌面截图不算判据）；② 服务端四项性能不回归（同场交错 ≥5 轮）；③ 存档往返不变；
④ 与至少一个光影模组共存不崩。

## 2. 作业内并行（已开始：先量，判据已定）

**是什么**：一个区域作业内部按 section 并行计算（FlowSched 式）。**注意前提**：默认 `regionChunks=1` 时单作业只有
1 个区块，并行没有收益 —— 实测也显示我们当前的瓶颈**不在计算量**（`runtimeHaloChunks` 1→0 把计算砍掉一大半，
pass 时间零变化）。它真正的用武之地有两个：
1. 运维命令 `/lucistarlink relight` 大半径刷新（几百个区块，逐区块排队，并行能显著缩短总时长）；
2. 用户把 `regionChunks` 调大（4/8）时的单作业延迟。

**已加的计量（1.2.2 起）**：`relight` 命令自己报告成本分解 —— 总耗时、其中**修复发光方块**的耗时、以及剩余
（扫描 + 采样 + 入队）的耗时，并同时报告修了多少个发光方块。**判据（写在动手之前）**：
- 若**修复**占大头 → **不要并行化**：方块改动必须在服务器线程上做，并行无从下手；
- 若**扫描+入队**占大头 → 并行化扫描（纯读、可切分）才有意义，且只覆盖"算"这一半，发布仍留在光照线程。

（早先按现有数据估整条命令是 1 秒量级、其中修复约 2.5 ms —— 那是**估算**，现在由上面这行实测取代。）

**计划**：把 `LuxRegionExtractor` 的逐 section 物化与传播循环拆成可切分的任务，用**固定大小的分段**交给一个
按作业复用的工作池；**发布仍然只能在光照线程**（数据单写者不变），并行只覆盖"算"这一半。
**风险**：物化阶段读的是引擎数据 → 必须保证与并发发布之间的一致性（沿用现有的环境代次 `externalEpoch` 检查）；
线程数要与世界生成池分开，避免大刷新把小改动饿死。
**验收**：① 四项工作负载（同场交错 ≥5 轮）**零回归**；② 新增一个"大半径刷新"基准，显示总时长确实下降（≥30%）；
③ 至少 3 次相邻成对探针零偏差 + 客户端用例通过。

## 3. 天空档（`sky_hole` 1.58× 落后）—— 建议就此定案为"接受"

证据链已经完整，且**方向已经不剩可行的杠杆**：
- 15+ 个交付侧杠杆全部实测否定（`docs/TASK-PERF-SKY.md` §7–§11）；
- 计算量不在关键路径（光环 2.5~4.5× 的计算差异对 pass 零影响）；
- **V2 全局存储已完整实现并实测中性**（0.815 vs 0.819 ms，p=0.69）—— 它要消灭的那条"异步交付链"并没有消失，
  V2 的**前提被自己的实现否证**，阶段 3 也已取消并记录理由；
- 唯一剩下的路是"在服务器线程上同步算完并提交"（ScalableLux 的做法），那与本模组"不阻塞服务器线程"的核心设计相反，
  并且会拿另外三项的领先去换。

**建议**：对外表述固定为"**两个重负载明显更快、小改动天空慢 1.6×，正确性与稳定性占优**"，不再投入这条线。
若用户要求继续，必须先拿到"同步路径不拖慢 tick 且不丢另外三项"的实测，否则不要动。

## 4. 其它仍未做（低优先，记着就行）

**已做（1.2.3 之后的清理轮）**：`LuxRuntimeManager` 里那处孤立 javadoc（峰值字段上方留着一块属于旧遥测视图的
注释，已并入并说明用途）✓；`dev/lucistarlink/test` 加了 `package-info.java`，说明基准装置为什么留在主源码集、
受什么纪律约束（默认全部关闭、不得改变默认行为）✓。

**待做（下一轮，按此清单执行）**：

1. **删除 `BorderDeltaSupport` + `experimentalBoundaryDeltas`**（默认关的死原型，被否决且已知会振荡）。
   **完整引用图（本轮摸全，比早先那份深一层 —— 照此执行）**：
   - `light/engine/BorderDeltaSupport.java`（162 行，整个删；内含 `BoundaryDeltaSink` 接口、`snapshotBorder`、
     `emitBoundaryDeltas`）；
   - `config/LuxConfig.java`：5 处（第 106/108 行的 key、188 行字段、234 行 `sync()`、258 行 `applyOverrides`、
     275 行把 `LuxFlags.boundaryDeltas` 镜像过去）；`light/LuxFlags.java`：2 处（28–29 行）+ 16 行的注释说明；
   - `light/engine/LuxRelighter.java`：`borderScratch`（34）、`BoundaryDeltaSink` 接口（41）、`sinkWrapper`（168）、
     两个方法签名里的参数字段（390、429）、透传调用（411）、4 组「快照 + 守卫块里的 emit」（450/467、479/498、
     514/527、569）；
   - **深一层（这才是要独立一轮的原因）**：`RuntimeRegionBatch` 带 `boundaryDeltas` 字段（构造参数 + `isEmptyDeltaSet`）、
     `RuntimeUpdateQueue.mergeDeltas`（145）、`LuxRuntimeManager` 的 incoming 合并（547–548）、
     `LuxRelighter.applyIncomingBoundaryDeltas`（577–584）会调
     `skyLightEngine.applyBoundaryDeltas(...)` / `blockLightEngine.applyBoundaryDeltas(...)`
     —— 原型一直穿到**光照引擎计算类**里，那两处也要一并摘掉；
   - `test/.../CrossRegionDifferentialTest.java`：5 处（29 行注释、53 行把 `LuxFlags.boundaryDeltas` 置 true、
     275/291/310/313 行直接调 `BorderDeltaSupport.snapshotBorder/emitBoundaryDeltas`）。
   ⇒ 这是跨「发布层 + 运行时批/队列 + 光照引擎」的删除，**必须独立一轮、改完立即跑 26 测试 + 四项交错**
   （预期零行为变化，因为默认关闭；但管线是这个项目出过两次真 bug 的地方，不许赶工）。
2. **`haloChunks` 与 `runtimeHaloChunks` 的命名歧义**：改善两处配置注释（世界生成 vs 运行期），
   **不要改键名**（改键会让已有配置失效）。
3. **发布光源改成显式参数**（让 worldgen 路径也能跳过未变 section）—— **不是清理**：它改变已测行为，必须单独做 A/B。
4. **`RuntimeUpdateQueue` 的 `Long` 装箱 CHM**（~20–40 ns/方块）—— **不是清理**：单槽缓存必须带代次校验，
   否则会静默吞掉改动；风险高于收益，除非有测量显示它真的在关键路径上。
