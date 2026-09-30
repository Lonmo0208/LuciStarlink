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
