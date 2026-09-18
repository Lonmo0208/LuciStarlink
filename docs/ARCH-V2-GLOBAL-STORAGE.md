# 架构 V2：全局存储 + 区域批处理（给实施窗口的详细设计书）

## ⚠️ 开工前必读（三件事，顺序不能反）

**第 1 件：先把手上的活收尾。**
本文件描述的阶段 0 会改到 `LuxPublishEngine` / `LuxRelighter` / `LuxRuntimeManager` —— 和"worldgen 只发核心区"那一刀**改的是同一批文件**。
所以：**先完成并验证当前改动（含边界/存档/基准证据），提交它，再开始 V2**。否则两处未完成的改动会互相污染，出问题无法定位。

**第 2 件：第一次提交（本仓库目前没有版本控制）。**
```bash
cd /e/LuciStarlin/LuciStarlink
git init && git add -A && git commit -m "baseline: region engine (current work included)"
md5sum build/libs/lucistarlink-1.21.1-0.1.0.jar   # 记入 docs/verify-baseline.txt
cp build/libs/lucistarlink-1.21.1-0.1.0.jar dist/lucistarlink-region-0.1.0.jar   # 成品回退用
```
没有 git，之前的改动只能靠 md5 指纹（`docs/verify-baseline.txt`）猜；有了 git，本文每个阶段就是一个分支/提交，回退精确。

**第 3 件：验收标准先看清楚，再动手。**
本次架构的核心性能目标是 **`sky_hole` min ≤ 3.0 ms**；但任何"靠关掉正确性换来的数字"（`haloPublish=false`、`runtimeHaloChunks=0`、跳过复查）**一律不算数**。每个阶段的验收矩阵见 §6，其中差分套件（`gradlew test`）与四路对照（`mc-smoketest/fourway.sh`）是硬门槛。

**用户硬性约束（再次强调）**：现实现（region 模式）**不删、不废弃**，作为默认与回退；新模式**一行配置可切**（`lightEngineMode = region | storage`）；每阶段都要能验收通过才进下一步。

---

## 📌 现状快照与偏差（监工方按代码指纹核对，先读这段）

**你已经做到的（核对过当前源码）：**
- `publish(data, allowHalo)` / `publishChunk(..., allowHalo)` 参数化完成，新增开关 `worldgenHaloPublish` —— 方向正确；
- 新增 `LightEngineAccessor` + `LayerLightSectionStorageAccessor` 两个 mixin（已正确注册进 `lucistarlink.mixins.json`）；
- `lucistarlink$installSection` 把最终光照直接写进 `updatingSectionData` + `visibleSectionData`，跳过 `queueSectionData`；并且**处理对了两个很隐蔽的 vanilla 细节**：`updating.clearCache()`（两层查找缓存会让引擎继续读到被替换的旧层）、以及移除 `queuedSections` 里会被遮蔽的待处理层；`onLightUpdate` 通知扇出也保留着（客户端同步不能砍）。

**因此与本文档的偏差（不要回退代码，按下面补）：**
1. 你已经在本文档的"阶段 1/2"上，而**阶段 0 的存储抽象层（`LightStorage` 接口）还没做**。不必回退，但**必须**补一个开关把新路径变成可切换的（见第 3 条）。
2. **仓库至今没有 `git`** —— 在没有版本控制的前提下直接改引擎内部存储，等于没有安全带。**立刻执行本文"开工前必读"第 2 件**（`git init` + 提交当前状态，含你这些改动；再记录 jar 的 md5 与 `dist/` 成品备份）。
3. **`lucistarlink$installSection` 目前看起来是无条件替换了原发布路径**（代码里没看到开关）→ 违反"region 模式保留、一行切换"的硬性约束。要求：加开关（例如 `directInstall`，**默认关**），或在 git 里做成一个可一键 revert 的独立提交。
4. **`worldgenHaloPublish` 默认是 `true`** → 这一刀默认没生效。请给结论：是打算"先保守、有证据再翻默认"，还是漏了？若翻默认，先给前后计数器（worldgen 侧 `publish.halo.sections` 显著下降）与边界回归证据。

**监工方将独立复验的两条（你不能只给推理）：**
- **客户端同步**：dev 客户端连 dev 服务端 → 在区块边界放光源 → 客户端屏幕是否**立刻**更新（跳过 `queueSectionData` 的最大风险就是这里）；
- **存档往返**：放灯后立刻 `save-all flush` → 重启 → 光还在、且邻居不丢光。

**本轮的硬性验收（同 §6，全部满足才继续）：**
1. 差分套件全绿（`gradlew test`）；2. `sky_hole` min ≤ 3.0 ms **且**跨区正确性证据仍非 0；3. 另外三档不低于 region 模式；4. 上述两条用例通过。

---


> 本文是**实施说明书**，不是讨论稿。目标读者：负责动手写代码的窗口。
> 前置阅读：`docs/HANDOVER.md`（当前进度与未解决项）、`docs/roadmap-and-provenance.md`（历史与实测数字）。
> 硬性约束（用户明确要求）：
> 1. **不得丢弃现有架构**：现实现（区域镜像 + 发布回写）保留为 `region` 模式，作为默认与回退路径；
> 2. **必须可切换**：一个新配置项一行切换，任何阶段都能立刻退回现架构；
> 3. **每一步都要可验收**：差分测试、四路基准、边界用例、存档用例，一个都不许省。

---

## 0. 先做的一件事（否则别开工）

在仓库根目录执行 `git init && git add -A && git commit -m "baseline: region engine"`。
理由：现在整个仓库**没有版本控制**，之前的教训是我们只能靠 md5 指纹（`docs/verify-baseline.txt`）猜改动。
有了 git 之后，本文的每一个阶段都可以是一个分支/提交，出问题能精确回退——这本身就是"备份"的最优形式。
同时把当前可用 jar 复制成 `dist/lucistarlink-region-0.1.0.jar` 留档，并把 `docs/verify-baseline.txt` 更新到这次基线。

---

## 1. 目标：把两家的优点合到一个引擎里

当前架构（Lucis 2.0 血统）：真相 = **每个区域一份镜像**（`RegionLightData`），算完把脏 section 交回原版引擎。
目标架构：真相 = **原版引擎自己的 per-section 存储**（全局、原子替换），区域只当**计算工作区**。

| 能力 | 现架构（region 模式） | 目标架构（storage 模式） | 目标架构怎么得到它 |
|---|---|---|---|
| 跨区块正确性 | 靠 halo 发布 + 外部 section 刷新 + 基线漂移重跑（我们自己补的，能对但复杂） | **天生不需要**：邻居就是真邻居，直接读全局存储 | 删除边界机制，改为"读邻居 → 只写自己地盘" |
| 回写成本 | 每脏 section 一次投递（邮箱 + 状态更新 + 27 邻居通知扇出） | **几乎为零**：把新数组原子换进引擎的存储 | 直接替换引擎持有的 `DataLayer`，走它的记账 API |
| 内存 | 每区域 3.4 MB（halo=1），必须按字节封顶 | **按 section 2 KB 粒度** | 区域数组只在作业期间作为 scratch 存在 |
| 批处理吞吐 | 强（dense 10.2 ms、structure 8.6 ms，三档第一） | 保持强 | 区域工作区与传播算法**原样保留** |
| 跳过重算（证书/采纳/惰性物化） | 有（adopt 435→0.9 ms、extract 56.8→5.6 ms） | 保持 | 这些只依赖"方块数据 + 光照数据可读"，两模式通用 |
| 客户端 | 做不了（客户端是另一套引擎与线程模型） | **可复用同一套路径** | 客户端光照引擎共享同一套存储类 |
| 与其它 mod 的兼容 | 两份真相，需要"采纳/刷新"被动纠偏 | **更好**：数据位置与形状和原版一致（就像 Starlight 那样） | 存储替换 + 保留引擎记账 |

---

## 2. 目标架构设计

### 2.1 三个核心概念

1. **存储层（LightStorage）**：唯一真相。按 (SectionPos, LightLayer) 读写，写是**原子替换整个 section 数组**（单写多读语义）。
2. **工作区（RegionWorkArea）**：作业期间的连续数组（**直接复用现有的 `RegionLightData`**，不改它的数学），用于快速批量传播；作业结束后可丢弃或缓存复用。
3. **提交（Commit）**：作业只把自己地盘的 section 原子换进存储；**邻居永远只读**。

### 2.2 一轮作业的生命周期（storage 模式）

```
变更队列（复用现有 RuntimeUpdateQueue / 合并逻辑，不改）
   ↓ 取一批（按区域）
建立工作区：
   · 读自己地盘：从存储读当前光照（采纳）或直接计算
   · 读邻居一圈：**直接从存储读**（不复制、不 adopt、不 refresh）
   · 材质：复用现有证书提取（整段空气/全不透明批量填）
   ↓ 计算（复用现有传播算法：SkyStarLightEngine / BlockStarLightEngine 的等价实现，不改数学）
提交：
   · 对本区域每个变化过的 section：构造新数组 → 原子换进存储 → 调引擎记账 API
   · 对边界相邻 section：在引擎里标记"需要复查"（复用原版不一致性机制），不自己做跨区投递
   ↓
结束：工作区归还；如与存储不一致（读到混合快照导致），交给"复查队列"兜底
```

### 2.3 必须处理的引擎记账（**这一节是成败关键，缺一样就会出现"数据对、游戏状态不对"的诡异 bug**）

替换/写入 section 数组时，必须同步：

1. **section 的可见性与存在性标记**（引擎用它判断某个 section 有没有光照数据）；
2. **"需要复查"标记**（原版的一致性机制，用来让光照线程后续重查；这也是我们替代"跨区发布"的手段）；
3. **天空高度信息**（1.21 里是 `ChunkSkyLightSources` 那套；我们改了直射天空列就必须更新它，否则重进存档后光照会错）；
4. **存档路径**：现有 `ChunkSerializerMixin`（M2b 存档安全）保留不动；
5. **客户端同步**：服务端算完的 section 必须照常发给客户端（原版路径，别绕过）；
6. **`hasLightWork()` 语义**：我们的写入要让引擎的"还有光照工作"状态正确，否则基准 harness 的屏障（以及其它 mod）会误判。

> 参考实现（**只读、可学习，不要整段搬运**）：ScalableLux/Starlight 已经证明过这条路可行，
> 它的 `SWMRNibbleArray` 与访问 `LayerLightSectionStorage` 的那套 access-widener/AT 列表可以**当作"需要触达哪些原版内部成员"的清单**来读。
> 若确实复用了它的代码或 AT 行，必须在 `NOTICE` 里补署名（同为 LGPLv3，合法但必须署名）。

### 2.4 一致性兜底（替代我们的"外部刷新 + 漂移重跑"）

问题：作业读邻居时，邻居的 section 可能正被另一个作业改写 → 读到"有的新、有的旧"的混合快照 → 结果可能略有偏差。
处理（三层，按代价从低到高）：

1. **只写自己地盘**（硬规则）→ 保证不会覆盖别人的正确结果；
2. **边界 section 一律在引擎里标"需复查"** → 引擎自己的光照线程会按原版规则重查（复用它的机制，不自己造队列）；
3. **可选**：为边界单元格做一次轻量"值比对"（现成的 `isStaleAgainstEngine` 思路可复用），只在发现差异时才加标。

---

## 3. 分阶段实施（每阶段都必须能验收通过才进入下一阶段）

### 阶段 0：抽象存储后端（纯重构，行为不变）
- 新增接口 `light/storage/LightStorage`（读 section / 原子写 section / 标记复查 / 读高度图），以及 `light/storage/RegionBackedStorage`（后端 = 现有 `OwnedRegionCache` + `LuxPublishEngine`，即把现有行为包在接口后面）。
- 把 `LuxRelighter` / `LuxRuntimeManager` / `LuxPublishEngine` 里对"存储"的直接访问改成走接口；**不改任何算法**。
- 验收：差分套件 21 项全绿；四路基准四档数字在噪声内；边界与存档用例不变。
- 目的：让后续替换是"换后端"，而不是"改引擎"。

### 阶段 1：引擎存储后端（读 + 记账，先不写）
- 新增 `light/storage/EngineStorage`：直接读写原版引擎的 section 存储（需要新增 AT/access-widener，把要触达的成员列进 `src/main/resources/META-INF/accesstransformer.cfg`）。
- 先只实现**读**与**标记复查**；写入路径暂时仍走现发布引擎。
- 验收：storage 模式跑差分套件与四路基准，四档在噪声内（此时还没享受到收益，但证明读写接口可用）；边界/存档用例通过。

### 阶段 2：storage 模式的提交路径（拿到主要收益）
- 作业提交改为"原子换进存储"；删除该模式下的邮箱投递与通知扇出。
- **必须写明"提交怎么调度"**（2026-09-18 实测修正）：存储是单写者（只有光照线程能写），换数组本身几乎免费，但把换数组**送到光照线程**、并且让"引擎承认空闲"发生在**同一轮**，才是成本所在——测得的剩余 ~0.9 ms 在这里。
  - **先纠正两个被实测推翻的假设**：
    1. "私有投递多一次 mailbox 唤醒"——不成立。`ChunkMap` 里 `ThreadedLevelLightEngine` 的 `taskMailbox` 是 `processormailbox`（"light" 线程），而它的 `sorterMailbox` 是 `queueSorter.getProcessor(processormailbox, false)`，**回调同样跑在光照线程上**；多一条任务只是一次队列跳，不是线程唤醒。
    2. "同包 mixin 可以拿到 `TaskType`"——**做不到**。把类放进 `net.minecraft.server.level` 会让 `lucistarlink` 模块与 `minecraft` 模块争抢同一个包，NeoForge 在模块路径上直接 `ResolutionException: Module minecraft contains package net.minecraft.server.level, module lucistarlink exports package net.minecraft.server.level to minecraft`，服务器起不来（实测踩到，jar 一换就崩）。任何"同包 mixin json"的方案都作废。
  - **可用做法（已实现、默认关）**：`@Inject(method = "runUpdate", at = @At("HEAD"))` 在光照线程上、在引擎自己的 `runLightUpdates()` 与 POST_UPDATE（barrier 等的就是它）之前提交，完全不需要包私有类型；触发用一条普通引擎任务（`updateSectionStatus`，对已初始化的 section 是幂等空操作）让 `lightTasks` 非空。
  - **实测结论（负面）**：这版本反而更慢——单 pass 最小值 0.48 → 1.24 ms，还出现一次 14.6 ms 的停顿。原因是触发依赖原版 `tryScheduleUpdate()`（要等下一次 `pollTask`），而现在的私有 drain 用 250 µs 定时器反而**更及时**。触发时机比省下的那一跳重要得多。
  - 因此阶段 2 的调度要重新论证：要么找到"确定性及时触发 + 同轮提交"的办法（例如在光照线程的下一次 `pollTask` 里挂提交，而不是等 `tryScheduleUpdate`），要么**放弃用调度优化去追这 0.9 ms**，改走"小编辑在服务端线程内联完成（含提交）"这条路——ScalableLux 正是因为它同步做完，才有 0.69–0.80 ms 的 per-pass 最小值。两条路都要按 §6 的测量口径验收。
- 新增配置 `lightEngineMode = region | storage`（默认 `region`），以及命令行/JVM 开关 `-Dlucistarlink.lightEngineMode=storage`。
- 验收（storage 模式，全部满足才允许继续）：
  1. 差分套件全绿；
  2. 四路对照：`dense_chunk_patch`、`block_toggle_border`、`structure_cube` **不低于** region 模式；`sky_hole` **per-pass min 的中位数 ≤ 1.0 ms**（对应 4 pass 合计 ≤ 4.0 ms 的旧口径下 **≤ 3.0 ms**）——按 §6 的测量纪律执行；
  3. 边界用例（区块边界放荧石 → 隔壁立刻亮）与"生成中的区块边界"场景通过；
  4. 存档/读档用例（放灯立刻退出 → 读档光还在；邻居不丢光）通过；
  5. **确定性**：同一配置连跑 2 次，光照指纹逐位相同（新增，见 §5 第 7 条）；
  6. 长跑 30 分钟无内存增长、无报错。

### 阶段 3：删繁就简（在 storage 模式成为默认之后）
- 把 `haloPublish` / 外部刷新 / 基线漂移重跑 从 **storage 模式**的路径上摘掉（region 模式保留，不删代码）；
- `BorderDeltaSupport` 与 `experimentalBoundaryDeltas`（已被取代的死代码）删除；
- 文档同步：README 配置表、NOTICE 变更清单、`docs/roadmap-and-provenance.md`。

### 阶段 4：客户端（新架构的额外收益）
- 在客户端光照引擎上启用同一套路径（客户端没有 worker 池，需要"在光照线程上直接跑小作业"的模式——现成的 `runtime.inlineBatchChanges` 内联执行正好是它的雏形）。
- 验收：单机创建世界 → 区块边界光照正确；F3 切换区块时无闪烁/黑块；与存档往返一致。

### 阶段 5（可选）：切换默认值
- 只有当前四阶段全部通过、并且**连续 3 次四路对照**都达标，才把 `lightEngineMode` 默认改成 `storage`。
- 切换前必须保留 `dist/lucistarlink-region-0.1.0.jar` 作为可回退成品。

---

## 4. 开关与回退（用户硬性要求）

| 场景 | 操作 |
|---|---|
| 想在旧架构上工作 | 配置 `lightEngineMode = region`（默认）或 JVM `-Dlucistarlink.lightEngineMode=region` |
| 新架构出问题 | 改回 `region`，无需回滚代码；两者共用同一套作业队列、证书、采纳、惰性物化、存档钩子与测试 |
| 代码级回退 | git 分支：每个阶段一个提交；`git revert` 阶段 2/3 即可回到"有接口但用旧后端"的状态 |
| 成品级回退 | `dist/lucistarlink-region-0.1.0.jar`（当前验证过的成品） |
| 基线对照 | `docs/verify-baseline.txt`（改动前的主源文件 md5 清单） |

---

## 5. 风险清单（按严重度）

1. **引擎记账不同步**（最严重）：数据换了但可见性/复查标记/高度图没跟上 → 表现为"某些角度光照看着对、某些角度是黑的"，或读档后错误。对策：阶段 1 只读、阶段 2 小步推进、每个记账点都有对应用例。
2. **混合快照**：见 §2.4；对策是"只写自己地盘 + 边界标复查"，先用原版机制兜底，不要自己造跨区投递。
3. **假收益**：任何"靠关掉正确性拿到的数字"（`haloPublish=false`、`runtimeHaloChunks=0`、跳过后来的复查）**一律不算数**；验收必须同时给出正确性证据（边界用例、存档用例、差分套件）。
4. **客户端路径**：客户端是单线程光照，别把"worker 池"假设带过去（用内联执行）。
5. **测量纪律**：跨会话墙钟不可比（实测同配置出现过 11 ms 与 50.6 ms）；只信同跑阶段指标 + ≥3 次重复取 min，且必须带 JDK 21 与 `WINDOWS-ROOT` TLS（见 `docs/HANDOVER.md` 的构建铁律）。
6. **存储层的两个隐蔽细节（2026-09-18 实测踩到）**：
   - `LayerLightSectionStorage.updatingSectionData` 带 2 项查找缓存（`DataLayerStorageMap.lastSectionKeys/lastSections`）。**直接 `setLayer` 之后必须 `clearCache()`**，否则后续 `getStoredLevel`/`getDataLayer(pos, true)` 仍会返回被替换掉的旧层，表现为"写入没生效"。
   - `queuedSections` 里的待处理层会**遮蔽**直接写入的层（`getDataLayerData` 先查 `queuedSections`）。直装时必须同时 `queuedSections.remove(sectionPos)`，否则客户端包与序列化会读到旧数据。
7. **"谁最后写"竞态（新发现，比记账更隐蔽）**：并发的区域作业如果都往共享区块的边界 section 发布 halo，就是**写-写竞态**——最后写的赢，而谁最后写取决于线程时序。已实测：`sky_hole`/strip 场景下，同一配置连跑三次 **block 光指纹每次不同**，而纯 vanilla 两次完全一致；`enableBlock=false`（block 光交回原版、我们只发 sky）两次一致但**仍不等于 vanilla**，说明分歧出在"用较早的镜像覆盖引擎里较新的数据"这一层，而不是光照算法本身。
   - 对策（阶段 2 必须做）：发布前校验该 section 的**基线是否移动过**（引擎现值 vs 我们计算时采用的基线），移动过就不允许用旧镜像覆盖——runtime 路径已有 `externalMarked`/`rerunBaselineMoved` 那套，worldgen 路径目前**没有**这道闸。
   - 验收补充：**确定性**必须单列一项——同一配置连跑 2 次，指纹必须逐位相同（这是本轮唯一能抓住这类竞态的廉价探针，见 `docs/HANDOVER.md` 的 `/lucistarlink dumplight`）。
8. **提交是"调度 + 时机"问题，不是"数据搬运"问题**：原版存储是**单写者**（只有光照线程能写，`ThreadedLevelLightEngine` 把每个写入都包成 `addTask`），所以"回写成本几乎为零"只对"换数组"成立，对"提交落地"不成立。而且实测表明：**触发时机比省下的一跳更重要**——用 250 µs 定时器主动 drain（单 pass 最小值 0.48–0.87 ms）优于依赖原版 `tryScheduleUpdate()` 被动触发（1.24 ms，且有 14.6 ms 停顿）。详见 §3 阶段 2。

---

## 6. 验收矩阵（每阶段结束都要贴一张）

| 项目 | 怎么测 | 通过线 |
|---|---|---|
| 差分测试 | `gradlew test` | 全绿（当前基准 21 项 / 0 失败 / 1 继承跳过） |
| 四路对照 | `mc-smoketest/fourway.sh`（4 负载 × 4 引擎 × ≥3 重复） | storage 模式不低于 region 模式 |
| 性能口径 | 读 `lucistarlink-light-benchmark.jsonl` 的 `minPassNanos`，**每档 ≥5 次重复取中位数**（跨会话/单次 wall 都不可比：实测同配置 3.70–8.37 ms） | `sky_hole` **per-pass min 中位数 ≤ 1.0 ms**（≈ 旧 4-pass 口径 ≤ 3.0 ms，即"不慢于 ScalableLux 的 0.69–0.80 ms 一个量级之上"） |
| 边界正确性 | 区块边界放荧石 → 隔壁亮度 | 隔壁立刻正确（附证据计数） |
| 生成边界 | 已加载地带边缘再生成一块 | 邻居被正确标记/重算（附计数） |
| 存档安全 | 放灯后立刻 `save-all flush` → 重启 | 光还在；无报错 |
| **确定性** | `/lucistarlink dumplight` 同配置连跑 2 次（可 `mc-smoketest/ls-border-scenario.sh gen`） | 指纹逐位相同，且与 vanilla 对照一致 |
| 内存 | 长跑 30 分钟 + 遥测 | 缓存与队列有界，无持续增长 |
| 兼容 | 与 Sable / C2ME 等同装 | 无光照发散、无崩溃 |

---

## 7. 一句话总结

**新架构 = 以"原版全局存储"为真相 + 保留我们的区域批处理/证书/采纳/惰性物化，边界与一致性改由原版机制兜底。**
实施方式不是重写引擎，而是**在现架构上抽一层存储接口、再换后端**——现架构全程保留、一行配置可切换、每阶段可验收。
