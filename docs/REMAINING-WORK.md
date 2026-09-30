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
