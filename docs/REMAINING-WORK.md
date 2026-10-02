# 剩余工作清单（交接文档，2026-09-30）

> 写给接手的同事：这棵树是 **LS-V2 分支 = LuciStarlink 2.0**（ScalableLux 基座 + 我们的层）。
> 每一项都给了「现状 / 证据 / 下一步」，按优先级排序。工具全部在 `tools/rig/`，过程记录在 `docs/HANDOVER.md` §10.x。
> 规矩只有三条：**改动必须有门 + 指纹；不行的撤；每个结论写进 HANDOVER。**

---

## P0 — 大编辑光源残留（未解决，用户实测仍在）

**症状**：活会话里 `/fill` 一大片不透明方块（如 11088 块红羊毛）再清掉，原地留一片亮；**退出存档重进就好**，再放再拆又坏。

**已修的两个子缺陷**（无头门已绿，但没盖住用户的形状）：
1. `b14aec5`：`SWMRNibbleArray.updateVisible` 的 HIDDEN/HIDDEN 早退分支只比状态不比数组内容 → visible 层永远不合并。已改为引用相同才跳过。
2. `d42eda9`：天光 sweep 入口在"上方 section 无存储（HIDDEN/NULL）"时读 0 → 整列跳过。已加高度图回退（窗口上方敞开 ⇒ 视作 15）。附带两个防崩溃安全网（merge 归零 null-updating；set() 补分配）。

**用户实测（2026-09-30 截图）**：上述修复后大 fill 仍残留 ⇒ **必有一条我们没盖住的序列**。
无头门（`gate-bigfill.sh`，萤石 3072 块）三轮全绿，所以差异可能在：方块种类（羊毛 vs 萤石）、fill 尺寸/形状、
**真客户端的客户端侧光照缓存**（无头门只读服务器数组，客户端渲染那一层没被测到）。

**下一步（按序做，别发散）**：
1. 在残留现场跑 `/lucistarlink layerdump 2`（本轮新增的诊断命令，会列出每个两层不一致的 section + 状态名 + 差异字节数）：
   - 列出 `HIDDEN/HIDDEN differingBytes>0` → 合并家族还有兄弟路径，改同一判据；
   - 什么都不列但 `block=` 与 `updBlock=` 不同 → 旧数据不在 chunk nibble 里，查读取端（vanilla 侧缓存/客户端包）；
   - 两层相等但值错 → updating 侧真错，走下面 P0-b。
2. 用**红羊毛**重做 `gate-bigfill.sh`（当前用萤石），加"客户端视角"判据（进服截图或读客户端日志）。
3. 已知剩的一条序列（无头可见）：`gate-residue.sh` 修后仍剩 2 格 `visible=15/updating=0`——de-init 后没有一次发布扫过它
   （HANDOVER 10.45 候选①：cache 出界）。修法方向：de-init（`setNull`/`setHidden`）本身标记需要一次补发布。

## P0-b — HIDDEN 段的更新侧语义（sky 残留的根）

3×3×3 放+拆已由 `d42eda9` 修到 0/11；但 HIDDEN/NULL 段在引擎自己那一侧读 0 仍是全引擎的隐雷
（任何依赖"窗口上方有光"的路径都会踩）。下一步：系统性排查 `getLightLevel`/sweep/checkBlock 对无存储 sky 段的读法，
决定统一语义（高度图回退 or 强制初始化），一次改对，门 = 三个残留门 + 四格指纹。

## P1 — 判据现状（2026-09-29 晚三引擎同窗口，36/36，负载 71%）

- **引擎口径 minPass**：structure **赢 1.x 45%** ✓、dense **赢 2.8×** ✓、border **输 24%** ✗、sky_hole 最优轮输 31% ✗。
- **玩家口径 wall**：对 ScalableLux 本轮 **未成立**（3/4 格慢 2-5 ms；负载下降级系数比 SL 大）。

**border（用户要求必须超 1.x）**：已修一个真缺陷（lane 拥有的 burst 不做天光变暗，`0f8f41d`）；
但 forced 路线的 0.55 ms minPass 是**假的**——我们的同步工作不在 harness 的完成判据里（`waitForPendingTasks` 只看基座 future）。
**下一步**：先把我们的 flush/lane/窗口重算纳入 `syncFuture` 的完成语义，border 的胜负才可判。
（注意 10.44：grouped 路径从不跑 increase 是真不对称，试过没解决残留已回退，修 P0 时可顺手重新验证。）

## P2 — 装置卫生

- **1.x 侧没钉版本**：rig 里 1.x 跑的是它 worktree 的 dev 构建（`1x-line @ 472d897`），列对 HEAD 敏感。给它出一个 jar 并 md5 钉住。
- **1.x 基线对协议敏感**：2026-09-26 的表（border 0.81 等）在现行协议下不复现；引用 1.x 数字必须带协议与轮次离散度。
- tick-end flush 的玩家口径 A/B 一直没跑（脚本玩家列的补丁没生效）。

## 工具索引（全部 `bash tools/rig/xxx.sh`，旋钮走 `-PslArgs`，rig jar 必须 `-Pmod_id=lucistarlinkrig`）

| 工具 | 用途 |
|---|---|
| `gate-gradient.sh` | 标准 22 探针正确性门（放 → 读 → relight → 读） |
| `gate-residue.sh` | 大板残留门（放/拆/relight 三读，判"拆后 == relight 后"） |
| `gate-cycle.sh` | **循环残留门**（五形状 × 2 轮，判"每次读 == 基线"，末次 relight 判真值） |
| `gate-bigfill.sh` | 整 chunk 大 fill 门（含 `layerdump`） |
| `check-layers.sh` | visible==updating 不变量（对任意门日志，`--selftest` 自检） |
| `onecell.sh` | 单格单侧带相位表跑分 |
| `threeway-materialplanes.sh` | 三引擎同窗口对照（36 run） |
| `/lucistarlink layerdump <r>` | 游戏内诊断：列出所有两层不一致的 section（P0 的第一工具） |

## 本轮已落地（防重复劳动）

2.0.17：`WindowSource`（同算法换数据源，默认关，逐字节等价）；2.0.16 分支 `LS-V2` 领先部分见 CHANGELOG 2.0.9–2.0.17；
诚实探针推翻了 10.36 的"区域粒度重算等价"结论（288/9216 不同，它是另一个算法，保留为诊断代码）；
三个残留门 + layerdump + check-layers 都是本轮新建的。

---

## 两轴成绩表（2026-09-29 晚，三引擎同窗口，36/36 新鲜，负载均值 71%）

**协议**：`tools/rig/threeway-materialplanes.sh`，3 引擎 × 4 格 × 3 轮同窗口拉丁方。
us = 本树（lane 开、WindowSource 关，jar `md5=120d5a19…`）；SL = 原版 ScalableLux 0.3.0-alpha.0.8；
1.x = 其 worktree dev 构建（`1x-line @ 472d897`，**rig 里未钉 jar，列对 HEAD 敏感**）。

### 引擎口径 `minPassNanos`（引擎自己为一次 pass 干完活的时间；min-of-2 均值 / 最优轮，ms）

| 格 | us (2.0.17) | ScalableLux | 1.x | 对 1.x 判定（要求追平或超过） |
|---|---|---|---|---|
| `block_toggle_border` 边境快放快拆 | 5.08 / 4.87 | 5.55 / 5.31 | **4.35 / 3.92** | ✗ **落后 17–24%** |
| `structure_cube` 盖建筑 | **3.56 / 3.22** | 7.19 / 6.86 | 6.34 / 5.87 | ✓ **领先 44%** |
| `dense_chunk_patch` 大面积编辑 | **1.39 / 1.23** | 5.16 / 4.27 | 4.14 / 3.46 | ✓ **领先 2.98×** |
| `sky_hole` 单点小改动 | 1.09 / 1.05 | 1.01 / 0.87 | 1.78 / **0.80** | △ 均量领先、**最优轮落后 31%** |

对 ScalableLux：4 格中 3 格领先（structure 2.0×、dense 3.7×、border 1.09×），sky_hole 落后 8%。

### 玩家口径 `bench.pass_wall_actual`（跨 tick 的真实过线时间；3 轮中位数 / 最优轮，ms）

| 格 | us | ScalableLux | 1.x | 对 SL 判定（要求严格超过，平不算） |
|---|---|---|---|---|
| border | **45 / 45** | 55 / 39 | 67 / 36 | △ 中位数领先 10 ms，SL 最优轮更快 |
| structure | 49 / 45 | **47 / 43** | 63 / 52 | ✗ 慢 2 ms |
| dense | 57 / 52 | **52 / 50** | 58 / 50 | ✗ 慢 5 ms |
| sky_hole | 51 / 48 | **48 / 41** | 75 / 17 | ✗ 慢 3 ms |

**读法**：玩家口径全部落在「一个 tick」（≈50 ms）附近，差值 2–5 ms，且这是负载 71% 的窗口；干净窗口里
我们与 SL 打平。**结论：玩家口径本轮未成立**（4 格里只有 border 的中位数过线）。

### 正确性

us = SL = **`sky=905931078dfc5ace`**（= 原版，四格指纹）；1.x = `641356fc41163add`（它已知的非原版天光）。

### 两轴合并计分

| | 引擎口径 vs 1.x | 玩家口径 vs SL |
|---|---|---|
| 过线 | structure、dense（2/4） | border（中位数，1/4） |
| 未过 | border（−24%）、sky_hole（最优轮 −31%） | structure、dense、sky_hole |

**注**：1.x 的轮间离散度 2–5×（border 3.92/4.77/19.10，天光 0.80/2.75/3.32），本树 ≤1.4×（border 4.87/5.29/5.91），
所以 1.x 一律按**最优轮**比；它的数字对协议也极敏感（2026-09-26 那张表 border 0.81 在现行协议下不复现）。

---

## 四路同窗口对比（2026-09-30，半脏环境，负载均值 56–59%、峰值 95.6%）

**四侧**：`us` = 当前树（含全部残留修复，jar `md5=2c439854…`）；`prev` = 修复前的 2.0 构建（`md5=120d5a19…`）；
`sl` = 原版 ScalableLux 0.3.0-alpha.0.8；`ls1` = 1.x 线 dev 构建。顺序每轮轮转、同一窗口内交错。
**两个窗口**：border/structure 来自补跑窗口（24/24 成功）；dense/sky_hole 来自前一个窗口（该窗口被负载杀了 25/48）。
**每一行内部是同窗口四侧对照，行与行之间不是。**

### 引擎口径 `minPassNanos`（最优轮，ms；越低越好）

| 格 | us | prev | sl | ls1 | 我们 vs SL | 1.x vs SL |
|---|---|---|---|---|---|---|
| border | 3.94 | 4.04 | 4.18 | **0.73** | ✓ 快 6% | ✓ 快 5.7× |
| structure | 2.75 | 2.58 | 4.62 | 2.62 | ✓ 快 40% | ✓ 快 43% |
| dense | **1.15** | 1.50 | 3.60 | 2.46 | ✓ 快 3.1× | ✓ 快 1.5× |
| sky_hole | 0.75 | 0.72 | **0.64** | 1.37 | ✗ 慢 17% | ✗ 慢 2.1× |

### 玩家口径 `bench.pass_wall_actual`（每 pass 中位数，ms；越低越好）

| 格 | us | prev | sl | ls1 | 我们 vs SL | 1.x vs SL |
|---|---|---|---|---|---|---|
| border | 51 | 50 | 50 | 106 | ✗ 慢 1 ms | ✗ 慢 2.1× |
| structure | 53 | 50 | 50 | 79 | ✗ 慢 3 ms | ✗ 慢 1.6× |
| dense | 54 | 58 | 49 | 72 | ✗ 慢 5 ms | ✗ 慢 1.5× |
| sky_hole | 51 | 48 | 50 | 50 | ✗ 慢 1 ms | = 平 |

### 两轴差距（引擎自己的工作量 ÷ 玩家实际等待，越低越"够快"）

| 格 | us | prev | sl | ls1 |
|---|---|---|---|---|
| border | 7.7% | 8.1% | 8.4% | **0.7%** |
| structure | 5.2% | 5.2% | 9.2% | 3.3% |
| dense | **2.1%** | 2.6% | 7.3% | 3.4% |
| sky_hole | 1.5% | 1.5% | 1.3% | 2.7% |

**这一行就是"两轴差距"的答案**：玩家的等待几乎全是一个 tick（≈50 ms），引擎自己的活在 0.7%–9.2% 之间；
四格全部装得进一个 tick，所以**玩家看四侧几乎一样快**，引擎口径的 3–5 倍差只在"谁会溢到下一个 tick"时才会被感觉到。
1.x 的 0.7%（border）是它在引擎侧快 5.7 倍却把墙面拖到 106 ms 的来源：它的活被摊到多个 tick。

### 判定（按放宽后的要求：两种引擎都要赢过 SL）

| 口径 | 我们 vs SL | 1.x vs SL |
|---|---|---|
| 引擎 | border ✓ / structure ✓ / dense ✓ / **sky_hole ✗** | border ✓ / structure ✓ / dense ✓ / **sky_hole ✗** |
| 玩家 | **四格全 ✗**（慢 1–5 ms，都在 tick 底噪内） | 四格全 ✗（border/structure 差得多） |

**结论**：引擎口径上"两种引擎都赢 SL"已经 3/4（只差 sky_hole，且那一格 SL 在 0.64–0.87 ms 的地板上，我们 0.75）；
玩家口径两种引擎都还没到"严格超过 SL"（差 1–5 ms，且这是负载窗口；干净窗口我们与 SL 打平）。
**修复没有代价**：`prev` 与 `us` 四格互有胜负、全在噪声内（border 4.04→3.94、dense 1.50→1.15 是好的方向，
structure 2.58→2.75、sky_hole 0.72→0.75 是坏的方向），即残留修复**没有把性能改回去**。

---

## 四路对比（2026-10-01，合并伙伴 `bd0255f` 之后；48/48 全成功，负载均值 49.5%、峰值 95.6%）

**四侧**：`us` = 当前树（含伙伴的 same-section neighbour 快路径，默认开，jar `md5=89c6dab5…`）；
`usoff` = **同一个 jar**、把该快路径关掉（`-Dscalablelux.sameSectionNeighbours=false`）→ 隔离伙伴那次改动；
`sl` = 原版 ScalableLux；`ls1` = 1.x 线 dev 构建。四侧同窗口交错、每轮轮转，每侧 12/12 新鲜。

### 引擎口径 `minPassNanos`（最优轮，ms）

| 格 | us | usoff | sl | ls1 | 我们 vs SL | 1.x vs SL |
|---|---|---|---|---|---|---|
| border | 4.32 | 4.36 | 4.52 | **1.19** | ✓ 快 4% | ✓ 快 3.8× |
| structure | 3.12 | 2.83 | 5.34 | 5.24 | ✓ 快 42% | ✓ 快 2% |
| dense | **1.53** | 1.54 | 4.99 | 3.89 | ✓ 快 3.3× | ✓ 快 1.3× |
| sky_hole | 0.98 | 0.97 | **0.89** | 1.36 | ✗ 慢 10% | ✗ 慢 53% |

### 玩家口径 `bench.pass_wall_actual`（每 pass 中位数，ms）

| 格 | us | usoff | sl | ls1 | 我们 vs SL | 1.x vs SL |
|---|---|---|---|---|---|---|
| border | **48** | 54 | 50 | 111 | ✓ **快 2 ms** | ✗ 慢 2.2× |
| structure | 56 | 52 | **51** | 78 | ✗ 慢 5 ms | ✗ 慢 1.5× |
| dense | 59 | 52 | **48** | 64 | ✗ 慢 11 ms | ✗ 慢 1.3× |
| sky_hole | **44** | 51 | 48 | 71 | ✓ **快 4 ms** | ✗ 慢 1.5× |

### 两轴差距（引擎自己的活 ÷ 玩家等待）

| 格 | us | usoff | sl | ls1 |
|---|---|---|---|---|
| border | 9.0% | 8.1% | 9.0% | 1.1% |
| structure | 5.6% | 5.4% | 10.5% | 6.7% |
| dense | 2.6% | 3.0% | 10.4% | 6.1% |
| sky_hole | 2.2% | 1.9% | 1.9% | 1.9% |

### 伙伴那次改动的独立读数（同 jar，只差一个开关）

border 4.32 vs 4.36、dense 1.53 vs 1.54、sky_hole 0.98 vs 0.97 = 平；
structure 3.12 vs 2.83（开=慢 10%，而 structure 本窗口四侧本身就摆 ±30%）。
⇒ **没有可成立的效果，也没有回归**——与作者自己写的"低于本机噪声底、需在安静机器上复测"一致。

### 判定（放宽后的要求：两种引擎都要赢过 SL）

| 口径 | 我们 | 1.x |
|---|---|---|
| 引擎 | **3/4 赢**（border/structure/dense），**sky_hole 输 10%** | 3/4 赢，sky_hole 输 53% |
| 玩家 | **2/4 赢**（border 48<50、sky_hole 44<48），structure/dense 输 | 0/4 |

**正确性回归检查（合并后立即做的）**：`gate-cycle.sh` **0/11**（残留修复仍有效）、`gate-gradient.sh` **GREEN**、
四侧指纹 us/usoff/sl = `sky=905931078dfc5ace`（= 原版），1.x 仍是它自己的。

---

## 2026-10-01 凌晨的进展与最新账目

**已采纳（默认开）**：冲洗闸门 `lucistarlink.flushWhenPending`（有活就结算，不等下一个 tick）。
同 jar A/B 四格 wall 全赢（border 49<50、structure 50<51、dense 48<54、sky_hole 49<51），2.0.15 的空轮询保护保留。
**已否决**：`recomputeMinChanges=64`（border 变慢，sky_hole 平）——MIN 保持 4。

**对 SL 的诚实账（同窗口 24/24）**：引擎口径 dense 赢（3.39 vs 3.66）、border/structure/sky_hole 输 4-14%；
玩家口径 border 赢 1 ms、structure/sky_hole 平、dense 输 1 ms。**引擎口径的绝对值已诚实化，旧表（含"3/4 赢"）不可直接比。**

**下一个杠杆（团队接手）**：四格的引擎活只占 wall 的 2-10%，两侧都装进一个 tick —— 要严格超过 SL 的玩家口径，
需要把我们的完成点在 tick 内前移（结算/发布的时序），这是调度问题不是算力问题。border 的引擎口径
（4.55 vs 4.11）仍以基座 decrease 波为主，伙伴的 same-section 快路径实测平，下一刀在下降波的逐检查成本。

---

## n=6 决定性对照（2026-10-01 晚，安静窗口 42%，48/48 fresh）——取代一切 n=3 窗口结论

| 格 | 引擎 med/best（us vs SL） | wall med | n=6 判定 |
|---|---|---|---|
| border | 4.46/4.12 vs 4.00/3.81 | 49.9 vs 49.6 平 | 引擎 SL +11% |
| structure | 5.91/5.16 vs 5.07/4.84 | 49.8 vs 49.0 平 | 引擎 SL +17% |
| dense | **3.69/3.22 vs 4.07/3.46** | **49.5 vs 50.0** | **双轴赢 ✓** |
| sky_hole | **0.84/0.71 vs 0.96/0.80** | **49.5 vs 50.8** | **双轴赢 ✓**（n=3 时误判为输） |

**sky_hole 是 n=3 噪声的受害者**（0.64-1.33 抖动带）——任何一格的胜负判定必须 n≥6。

**border/structure 引擎差距的根因（已定位，未修）**：两者的改动现在都走基座队列（异步），但我们的**入队发生在
apply 之后**（第一次 wait 触发 flush 时），SL 的入队发生在 **apply 之中**（vanilla setBlockState 钩子）——
border 的 apply ≈0.6ms、光工作 ≈3.5ms，SL 重叠掉了其中 ~0.5ms。**下一刀：capture 时就把队列 eligable 的 chunk
直接 queueBlockChange（apply 中重叠）**。**已知的双写风险必须先解**：capture 时无法预知整个 flush 会不会让
lane 区域达标（多 chunk 各 25 改动可跨过 64）——一旦达标，lane settle 与先入队的任务写同一批格子。
安全方案没想出来之前不要动（本仓六次光照事故全是这类竞态）。候选：区域级"已入队"登记表 + flush 时对
已入队 chunk 改走队列完成语义（不二次 settle）。

---

## capture 时入队（border/structure 最后一刀）的安全设计稿（2026-10-01 深夜，未实现——先解两难再动代码）

**目标**：把入队从"apply 之后的第一次 flush"提前到"apply 之中"（capture 钩子在服务器线程、`lucis$pendingEdits`
的添加点在 `StarLightInterface` 的 editPos 路径），让光线程与 apply 重叠，吃掉 border/structure 引擎口径
最后的 +11%/+17%。

**两难（推演结论，动手前必读）**：
1. **跨阈值提交两难**：capture 时每个改动≤32 才能入队，但第 33 个改动到达时前 32 个已经排进队列——
   此时只剩两个选择：(a) 整 chunk 提交给队列（structure 4096 全走队列 = 实测慢 7%，见 10.52）；
   (b) 前 32 个与后 4064 个分属队列和窗口两个写者 = 2.0.16 同类竞态。
   结构性的出路候选：**阈值内入队 + 跨阈值时"等待前 32 个的队列 future 完成后再跑窗口"**（串行化但只在
   跨越发生的那一次），或者**窗口路径感知已排队位置**（从窗口种子集合里剔除它们）——两者都有正确性论证
   要写，未写完不动手。
2. **区域达标竞态**：多 chunk 各 ≤32 的区域加起来能跨过 64（lane 达标）——一旦达标，lane settle 与先入队的
   任务写同一批格子。出路：flush 时若区域含已入队 chunk → 整区域改走队列 + `staleRegions` 作废 lane 缓存
   （10.52 的 Option 2，语义完整）；lane 的 `covers()` 必须排除含已入队 chunk 的区域。

**验收（不变）**：三个门 + check-layers 归零 + 四格指纹 + 安静机器 n≥6 的 us-vs-SL（ROUNDS=6 已支持）。
预期收益：border/structure 引擎口径 −0.4/−0.8 ms（apply 与光工作的重叠），wall 已平，不指望再动。

---

## 2026-10-02 深夜：capture 时入队第三尝试的机理级关闭 + 测量纪律新条目

**队列调度语义已解透**（这是 P1 第一项的一半）：`propagateChanges()`——把任务派发到 scalablelux-N 工作线程的
唯一入口——**只被原版光照 tick 调用**（服务器线程）。`queueBlockChange` 只进 dirtyPos。所以：
- 任何"apply 中途 join 队列 future"的写法 = 等"被 park 的服务器线程永远走不到的下一个 tick" = 自锁
  （三次实验：无 pump 自锁；无 join 提交毒化 lane（dense −25%）；pump+join **形状相关死锁**——
  64 格跨阈值通过、405 格挂死，问题在 SchedulingUtil 的 5×5 chunk 锁 token 消化，未解透）。
- **"capture 时重叠 apply"在本架构下关闭**。重开的前置 = 解透 SchedulingUtil token 语义（own-scheduler 领地）。

**P1 的两项更新**：
1. ~~队列调度语义~~ → **已解透一半**（泵=光照 tick 已确认；剩 token 消化语义，属于上条的前置）。
2. border/structure 引擎残差（+4~12%，随窗口）的最后一段 = 同步 flush 的收尾 + lane 保险费（structure 8.5%
   不可约）+ 测量噪声。在两项引擎任务（token 语义、下降波逐检查成本）之前，**没有已知的、安全的、
   单工作窗能完成的杠杆**——12 次尝试 + 伙伴快路径平 + 三次 capture 实验构成完整的否定记录。

**测量纪律（新）**：`onecell.sh` 的相位表带 profiler 自身开销——border 同构建 minPass 4.3（profiler 关）
vs 8.3（开），**profiler-on 的绝对值禁止与 profiler-off 的表对比**；相位表的相对结论（下降波主导）仍有效。
另外窗内 95.6% 峰值部分来自测量自身叠加——任何时刻只跑一个测量进程（脚本已保证）。
