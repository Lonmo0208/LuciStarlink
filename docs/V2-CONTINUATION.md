# V2 续接档（给压缩后的我 / 给另一个窗口）

**读这一份就够。** 写于 2026-09-19 03:35，位于分支 `v2-storage`。

## 0. 一句话现状

**V2 已完整实现并收线（2026-09-19 05:20）。** 阶段 0/1/2 全部落地、验证、提交在 `v2-storage` 分支：`4f88296`（写入路径）、`eb19af4`（结果与阶段 3 取消）。**性能中性**（storage 0.815 vs region 0.819 ms，p=0.69，前后健康对都健康 ✓），机制计数证明真在写（4174 个 section 直接装进引擎 ✓）。于是 **V2 的核心假设被真实现否证**：写入仍必须在引擎的光照线程上（drain 内），链路的关键一跳没消失 ✗。**阶段 3 也取消，且理由更强：它的前提是错的** —— halo / 外部刷新 / 漂移重跑在两种模式下都必需（区域映像在两种模式下都是引擎数据的副本），删任何一条都会把跨区黑缝放回来 ✗。分支保留为一个**可切换、经 parity 验证的替代引擎模式**（默认仍是 region ✓），不并入发布线。
阶段 0/1/2 的代码全部保留在 `v2-storage` 分支（读路径接缝、真写入路径、影子计数、`storage-parity.sh`、`lightEngineMode` 键），**不合并进 `master`**（性能中性，对外没有行为价值）。

**重启条件（必须是新实测，不是推理）**：某个交付路径改动在同场交错测量中给出 ≥15% 的 `sky_hole` 改善。
当前证据反对：交付量已只剩约 21%（79% 的脏 section 本来就相同），且直装、通知收窄、piggyback、promptDispatch、
发布分道、内联阈值、合并窗口（三次）全部为 0 或更差，再加上真实现的 storage 模式也是 0（p=0.69）。

**于是天空档的 2× 走"接受"路线**，并有了架构层面的解释（见上）：只要引擎的存储与更新循环不由我们独占，那条链就存在 ✗。
**真正超过 ScalableLux 的唯一路径**是像它那样独占存储与更新循环、把计算放进 `setBlock` 里同步做（吞吐换延迟 ✓ 与当前取向相反 ✗）——
那是比 V2 大得多的工程，且会改变模组定位，**需要用户明确决策后再立项** ✓。对外表述（README/CHANGELOG 的区间表）已是最终形态，无需改动 ✓。

## 1. Git 状态（先看这个再动手）

| 项 | 值 |
|---|---|
| 分支 | `v2-storage`（`master` = 1.0.3 发布线，**不要在 master 上做 V2**） |
| 标签 | `v1.0.2`、`v1.0.3`（回退点；成品 `dist/[光源优化模组V1龙师傅特供]lucistarlink-1.21.1-1.0.3.jar`，md5 `0d220c139c5f31dd2f2d24b597efd1bc`） |
| 分支已提交 | 开工基线 → 阶段 0 → 测量协议 → 发布件改名 → 1.0.3 署名 → 审阅/验收/续接档 → **`1344716` 阶段 1（含两个隐患修复）** |
| 谁在干什么 | **我**：实现 + 验证 + 文档。没有其他人在这棵树上。 |

## 2. 阶段 0（已提交，我做的）

- `light/store/SectionStore.java`：单一存储抽象（`read` / `write` / `notifyChanged` + 三条不变量：**原地覆盖 `DataLayer`**、
  **只在光照线程写**、**只通知真正变化的 section**）。
- `light/store/RegionSectionStore.java`：region 适配器（读走引擎 layer listener、写走既有发布路径）。
- 配置键 `lightEngineMode`（`region` | `storage`，默认 region）：解析 + 校验 + 启动日志。
- 阶段 0 的验收：**没有任何调用点**，所以"行为零变化"由构造保证 ✓ 套件绿 ✓。

## 3. 阶段 1（已提交 `1344716`，两个隐患已修）

内容：`SectionStores`（按模式选 store、每 level 缓存、`readEngineLayer` 作为唯一引擎读取点）+
`EngineSectionStore`（`write` **只测量不写**，计数 `storage.shadow.sections` / `.identical.sections`）+
`adoptLayer` 经 store 读 + `statusLine()` 增加影子行 + mixin 在 storage 模式下对每个待发布 section 调一次
`store.write()` 做比较。**"两种模式读同一份字节"是代码性质**（唯一读取点），不是靠测试维持 —— 这比原计划更强。

**我已修掉的两个隐患（原为审阅意见，见 `docs/REVIEW-V2-STAGE1.md`）**：
1. **publisher 不再注入**：`RegionSectionStore` 在 `write` 时从 level 解析 publisher（`getLightEngine() instanceof
   LuxLightPublisher` ✓ 拿不到就**抛异常快速失败** ✗），`SectionStores.of(level)` 去掉 publisher 参数 →
   "缓存里存到 null-publisher 实例"这一整类 bug 消失（不必再靠约定）。
2. **`adoptLayer` 不再每 section 解析 store**：`LuxRelighter` 里加了**单槽缓存**（按 level ✓ 连续同 level 的作业命中率≈100% ✓），
   把"每 section 一次 CHM 查找 + 字符串比较"降成"每作业一次"。
3. 影子行的注释改成实话（**只在 storage 模式产生**；region 模式的等价数字是发布路径自己的
   `publish.skippedIdentical.sections`）。

## 4. 我补的验收工具（不与其重叠）

`mc-smoketest/storage-parity.sh <tag>` —— **阶段 1 的判据工具**：
同一份世界快照 → region 模式跑一遍运行时边界编辑 → 恢复快照 → storage 模式跑同一场景 → 逐行 diff
`LUCIS_LIGHT_FINGERPRINT` / `LUCIS_LIGHT_PLANE`（每行一个 z 的逐格十六进制，可逐格比较）/ 基准计数。
判据 = **完全一致**（阶段 1 只改读路径，不改光值）。脚本还在前后各插一次**健康对照对**（这台机器健康窗口只有 ~10~15 分钟，
见 §5），健康线：我们 wall 3.7~5.2、原版 4.2~4.9；任一侧 ≳20 ms 就别下结论。

**当前进度**：场景世界已生成（`ls-border-scenario.sh gen p1` ✓ errors=0），rig 版已用**含阶段 1 在制品的树**重建
（md5 `99a96c0a0803cb74639d413e2601beaf`）。**下一步就是跑 `./storage-parity.sh p1`（约 12~15 分钟）**。

## 5. 这台机器的坑（每条都实测踩过）

- **健康窗口 ~10~15 分钟**：同一配置在健康时 wall≈3.7、降级后 wall≈24~39（= 一个 tick）。症状：**异步引擎**（我们/原版）
  wall 变成 ~20~56 ms，而 SL/Lucis 不受影响 → 连比值都不能用。**每次测量前后都要插对照对**，V2 一律按"分块测量"（V2-PLAN §0）。
- **NeoForge 配置陷阱（V2 每加一个键都会遇到）**：① 配置值的 `.get()` 只能在 `sync()` 里，放 `applyOverrides()` 会让服务器
  起不来（`Cannot get config value before config is loaded`）；② `defineInList` 的候选值**不能用 `List.of(...)`**
  （旧配置缺该键时用 null 校验 → `List12.indexOf(null)` NPE → 启动即崩），用 `Arrays.asList(...)`；
  **新键一定要在"确实缺该键的配置文件"上验证**。
- **`.gitignore` 有 `*.jar`** → `dist/` 的发布件要 `git add -f`；改名会表现为"删除旧路径"。
- **不要用 PowerShell `Set-Content` 写源码/MD**（加 BOM、毁 UTF-8）；**不要在 bash heredoc 里用反引号或 `\r\n`**
  （会被 shell 执行/吃掉，今天我踩了三次）；要改文件用文件工具。
- 跑基准时**别并发跑 Gradle/其它 CPU 活**；**别在脚本运行中改脚本**（bash 边读边执行）。

## 6. 关键数据在哪

| 想知道 | 看 |
|---|---|
| V2 计划、阶段 0-5、熔断条件、验收口径、工作量 | `docs/V2-PLAN.md` |
| 设计细节（引擎记账每个坑、生命周期） | `docs/ARCH-V2-GLOBAL-STORAGE.md`（顶部有指向 V2-PLAN 的更正说明） |
| 阶段 1 的审阅意见（两个隐患） | `docs/REVIEW-V2-STAGE1.md` |
| `sky_hole` 战役全过程、14 个被否定的杠杆、量具两次修缺陷 | `docs/TASK-PERF-SKY.md` §7-§11 |
| 三方/四路性能表（区间）、默认开关、构建铁律 | `README.md` 顶部 / `CHANGELOG.md` 1.0.x / `docs/HANDOVER.md` |
| 成品 md5、构建命令、验证记录 | `docs/verify-baseline.txt` |

性能现状（引用区间，勿用单值）：对 ScalableLux 两档领先（拆建 2.05~2.49×、结构 1.31~1.77×）、两档落后
（密集 1.21~1.73×、天空小改动 1.87~2.0×）；对原版三档领先 1.2~4.9×、天空持平。**V2 的唯一目标是把天空档从 2× 拉到 ≤1.05×。**

## 7. 下一步（按顺序）

1. 跑 `mc-smoketest/storage-parity.sh p1` → 阶段 1 的读路径一致性证据（判据：逐格完全一致）。
2. 把 §3 的三个问题交给另一个窗口（它改，我不动它的文件）。
3. 它提交阶段 1 后：按 V2-PLAN 的协议（分块 + 前后对照对）拿四档基线；然后才是**阶段 2 的写路径**（主要收益 + 熔断点：
   `sky_hole` 改善 <15% 就停 V2）。
4. 未做且与 V2 无关的收尾（不阻塞）：1.1 清理清单（`docs/V2-PLAN.md` §9）、C2ME 互操作实测、客户端光照接管。
