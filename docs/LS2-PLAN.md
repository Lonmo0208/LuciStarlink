# LuciStarlink 2.0 —— 换 ScalableLux 底座（立项）

> **状态：本文件是立项与 M0–M3 阶段的历史记录（2026-09-21/22），不是当前状态。**
> 之后 R0–R5 把更新回路换成了我们自己的实现并完成验收，三个开关已默认开启，代码已进 `master`。
> 当前状态看 [README.md](../README.md) 与 [ARCHITECTURE.md](ARCHITECTURE.md)，全部测量看
> [NEW-ENGINE-TEARDOWN.md](NEW-ENGINE-TEARDOWN.md) §32–§34。下面 §6 里列的两项"待决"（与上游的关系、1.x 的 80 格差异）
> 在前者已经收口，后者已定性为 1.x 自己的世界生成期缺陷。

* 决定人 / 日期：用户，2026-09-21（「做成"LS 2.0 = 换 SL 底座" 这个 才是我想要的」）
* 本文件所在仓库 = 2.0 的**底座树**：`E:\LuciStarlin\ScalableLux-neoforge-build\ScalableLux-Patched`（分支 `lucis-ideas`，
  ScalableLux `0.3.0-alpha.0.8` 的 NeoForge 1.21.1 移植，基线提交 `c312ee3`）
* 1.x 线（`E:\LuciStarlin\LuciStarlink`，当前 1.2.10）**继续存在**：2.0 未发布前它仍是玩家装的那个

## 1. 2.0 是什么

**底座换成 ScalableLux**（同步、存储即真相的光照引擎），把 LuciStarlink 的身份、功能、工程纪律搬上去。
不是"把 1.x 的引擎塞进 SL"，恰恰相反——1.x 那条异步路线**不迁**（见 §4）。

| 项 | 取值 |
| --- | --- |
| modId | `lucistarlink`（**不变**：玩家侧升级无感，依赖/配置同键） |
| 显示名 | `LuciStarlink`（不带中文，延续既有约定） |
| 首批版本号 | `2.0.0-alpha.1`（未验收前不叫 2.0.0） |
| 许可证 | **LGPL-3.0-only**（1.x 与 SL 同为此协议，无冲突；两处署名都必须保留） |
| 署名 | SL/Starlight（Spottedleaf、ishland、RelativityMC）为**代码底座**；Lucis（Team Argentum、DenisMasterHerobrine）与 1.x 为**前代**；NOTICE 写清"底座是 ScalableLux"与逐条变更清单 |

## 2. 为什么换（依据都是本项目自己量的）

1. 三方六轮：SL 在 `block_toggle_border` 1.2954、`dense_chunk_patch` 1.132、`sky_hole` 1.391 上更快，我们只赢
   `structure_cube`；加权 0.9339 是我们落后（`LuciStarlink/docs/TASK-PERF-SKY.md` §18）。
2. 玩家体感的落地延迟：`structure_cube` 上 SL 66.5 ms/趟 vs 我们 99.1 ms（1.49×）——差的是跨了几个 tick，
   不是算得慢（同文档 §19）。
3. 正确性：差分探针在 599,040 格盒子上，**SL 与 vanilla 逐位一致**；同一盒子**我们 1.2.10 差 80 格天空光**
   （方块相同、可复现，同文档 §20，本分支文档 §12）。

## 3. 里程碑

* **M0 身份切换**（本分支）—— **已完成 2026-09-21**（提交 `M0: LuciStarlink 2.0 identity on the ScalableLux base`）：
  modId `lucistarlink`、显示名 `LuciStarlink`、版本 `2.0.0-alpha.1`（继承来的 build-counter 后缀已去掉）、
  entrypoint/命令/mixin 配置改名、配置文件 `lucistarlink.properties`、与独立 ScalableLux 互斥、
  LICENSE 保留 + NOTICE 写清署名链与变更清单；内部 `scalablelux$` mixin 成员前缀与 `ca.spottedleaf.starlight`
  包名**有意保留**（每个文件的出处一目了然）。构建产物
  `lucistarlink-1.21.1-2.0.0-alpha.1-all.jar`（md5 `51fe5d7f8e54e63561ef0d6cf1878863`），jar 内元数据已核对。
  **冒烟实测**：启动行 `LuciStarlink 2.0 active (ScalableLux base)`、`Registered /lucistarlink (stats, light, relight)`、
  `SLTELEM dim=minecraft:overworld tasks=1 dirty=1 poolSky=0 poolBlock=0` 都出现 —— 身份、遥测、命令的接线都对。
* **M1 功能迁移** —— **已完成 2026-09-21**：配置面（`fc91b49`）把开关搬进 `config/lucistarlink.properties`
  并给了 `docs/CONFIG-MIGRATION.md`（1.x 的 25 个键里只有 `enabled` 有对应物，其余 24 个是异步/区域引擎专属，
  属"不适用"而非"待迁移"）；Sable 守卫（`46fe822`）按存在性判据 + 访问器 + 五个延迟点移植，
  **实测有/无 Sable 各一次**（无 Sable：结构方块 7.91 ms、err=0；有 Sable 2.0.3：服务器起、基准跑完、无异常；
  诚实边界：plot 延迟路径本身未被触发——基准区域不是 Sable 的 plot）。保存/重启保证的核对见 §10。
* **M2 量具支持 2.0** —— **已完成 2026-09-21**（提交 `M2: 2.0 becomes measurable through the standard rig`）。
  阻塞点根因：harness（主项目）与 2.0 都声明 modId `lucistarlink`，FML 只加载其一，基准的 prepare 从未开始。
  解法**不动出厂身份**，只加一个 rig 变体：`-Pmod_id=lucistarlinkrig` 让 toml id、mixin 配置名
  （`lucistarlinkrig.mixins.json`）与入口类一起跟随；并且**按构建互斥地排除入口类**——FML 对"@Mod 类 id 未声明"
  是硬失败（`dangling_entrypoint`），不是忽略。rig 入口类改用 `EVENT_BUS.addListener` 手工挂载命令与遥测
  （`@EventBusSubscriber` 的 modid 是编译期常量，跟不了构建），mixin 也改成自带日志器（第一版就是它引用了被排除的
  入口类而崩）。
  实测：md5 `01026968ead25c1479399842fef92bf3` 经 `sl-jar.sh` 跑 `structure_cube`，`err=0`，
  minPass 7.46 / 7.75 ms（中位 **7.61 ms**）—— 与官方 SL 同档（符合预期：我们的新增不碰计算路径）。
  **发布时用默认 id 重建**（不带 `-Pmod_id`）。rig 变体与出厂件的差别只在：modId、mixin 配置名、入口类。
* **M3 验收与发布**：见 §5 与 §7（第 1、2、4、5 项已完成；第 3 项已按 §7.3 的理由重新定界）。

## 4. 迁什么 / 不迁什么

**迁**：

* 遥测行（`SLTELEM` → 命名随 2.0 调整，内容不变）与自省命令 `stats` / `light` / `relight`（本分支已实现并验证）
* 差分正确性探针接入 2.0 的验收（量具在 1.x 仓库，两边共用）
* 1.x 的工程纪律：发布件不进 GitHub、verify-baseline 记录 md5/元数据、CHANGELOG 逐条、README 双语且带诚实结论
* 1.x 的正确性保证（重启不截断、世界生成逐位一致、跨区块去光收敛）——按 §10 的核对结论，SL 两条钩子等效或更强，
  只需在 2.0 里**实测一次**而不是重新实现

**不迁**：

* 异步/区域映像引擎（与 SL 的同步底座互斥，本分支 §8 已判）
* 与区域映像绑定的内存/懒材质优化（没有那个结构，天然不需要）
* 1.x 的实验性开关（`batchLimit` 等默认关且无收益的——只留文档记录）

## 5. 验收标准（缺一不算过）

1. **不劣化 SL**：同负载、同会话交替 A/B，2.0 vs 官方 SL 的 jar：`wall` 与 `minPass` 都不劣化（§19 的双口径纪律）。
2. **正确性**：差分探针在测试盒子上与 vanilla **逐位一致**（§12 的方法，命令可复用）。
3. **三方**：用第三方的 `threeway.ps1`（不改脚本）把 2.0 放进"我们"那一列，六轮默认协议，产出可发布的表。
4. **存档迁移**：拿一份 1.x 存档直接加载 2.0，光照正确（不出现暗块/亮块）；必要时给 `/lucistarlink relight` 的使用说明。
5. **配置迁移**：1.x 的键逐个给出"等价键 / 废弃"说明，写进迁移文档。

## 6. 风险与待决

* **存档格式**：1.x 与 SL 都走 vanilla 的 `isLightOn` + 每节 `BlockLight`/`SkyLight`，理论可互读；**必须实测一次**
  （§5.4），不实测不许写"兼容"。
* **包名**：SL 的代码在 `ca.spottedleaf.starlight.*`。建议**保留**（LGPL 允许改，但保留更能说清代码出处），
  我们新增的代码放 `dev.lucistarlink.*`；迁移时再定。
* **配置文件**：1.x 用 NeoForge ModConfigSpec；2.0 应沿用同一套（键名尽量不变），避免玩家改配置。
* **与上游的关系**：是否把我们的遥测/命令提给 SL 上游（LGPL 允许，但属对外动作），以及是否公开 2.0 的底座出处，
  都由用户拍板后再做。
* **1.x 的 80 格差异**：仍要查（最小复现）。结论会影响 2.0 的紧迫度：若是我们的真缺陷，2.0 就是它的根治方案；
   若是 vanilla 怪癖，1.x 可以慢慢来。

## 7. M3 验收进展（2026-09-21）

| # | 验收项 | 状态 | 证据 |
| --- | --- | --- | --- |
| 1 | 不劣化官方 SL（wall + minPass，同会话交替） | **进行中** | `mc-smoketest/sl-ab2.sh`（两个**不同** jar 的交替 A/B，每侧各带自己的 `expectedMod`），`structure_cube` 6+6 正在跑 |
| 2 | 差分正确性：与 vanilla 逐位一致 | **通过** | 2.0 出厂构建在同一 599,040 格盒子上 `skyDiff=0 blockDiff=0`，指纹 `905931078dfc5ace` / `59e2252f732ce67b` 与 vanilla、与官方 SL 完全相同（rig 变体 md5 `2f866b87…`，纯 prepare、RTS=0、落定后） |
| 3 | 第三方 `threeway.ps1` 六轮表（2.0 进"我们"那一列） | **已重新定界**（见 §7.3） | 该脚本的"我们"列跑的引擎就是主项目自身（dev classpath），插不进另一个 jar；且"上游 Lucis"列属 1.x 血统，2.0 不是 Lucis 派生 |
| 4 | 1.x 存档能被 2.0 正确加载 | **通过（光照维度）** | 见下 |
| 5 | 配置迁移说明 | **通过** | `docs/CONFIG-MIGRATION.md`（25 键逐条） |

**第 4 项的实测与读法**：拿一份 1.x 生成并保存的存档（8.3 MB、49 区块，1.x 把光照按"正确"存盘）：

* **vanilla 读它 = 1.x 的值**（`641356fc41163add`）：与 vanilla 自建同种子世界相比，差异**正好是那 80 格**，而方块只差 5 格且位于 y≈32 的无关处 —— 即 **1.x 把自己那份偏暗的光照存成了"正确"，谁信它谁继承这 80 格**；
* **2.0 读它 = 规范值**（`905931078dfc5ace`），**官方 SL 读它也是同一个规范值**（同样 `skyDiff=80` 对 vanilla 的读数）→ 结论：**升级加载后那 80 格被自动修好，而这个行为是底座自带的，不是我们的改动**；
* 成本正常：prepare `loadMs=30`（全新世界 17）、49 区块全部 `lightCorrect`、无异常；
* **边界（必须一起说）**：这是 49 区块的基准世界、比的是**光照指纹**，不等于"任意玩家存档的每一个可见细节都验过"；机制（底座在加载时重算了哪些节）没有细查。

**第 1 项的门槛与读法**：与项目一贯纪律一致 —— 双侧 `minPass` 的中位数比值之外，还要看 `bench.pass_wall_actual`（玩家体感的落地延迟）；结论要求"不劣化"，而不是"赢"（我们的新增本来就不碰计算路径，预期两者不可区分）。

### 7.1 A/B 参照的选择（本轮踩到的坑，写下来防复现）

第一次 6+6 用的是 `LuciStarlink/build/downloads/ScalableLux-0.1.0.1+neoforge.1cb1e91-all.jar` —— **那份不是三方对比一直用的版本**，
它读 `structure_cube` 中位 **3.6 ms**，而本项目所有会话里 `0.3.0-alpha.0.8` 的读数都在 **7.2–8.9 ms**（官方件与我们自建件都是；
纯净底座本轮实测 6.74/7.93，中位 7.33）。那份 0.1.0.1 确实是 SL 的构建（同 modId、同 `StarLightEngine` 结构），
**是"老版本真的更快"还是"它的 `waitForPendingTasks` 更早完成导致 `minPass` 口径偏小"未定**——后者会体现在 wall 上
（§19 的双口径纪律就是为这种情况立的）。结论：拿它当参照的 A/B 无效。

**因此本分支 A/B 的参照按项目纪律取"同源构建"**：用 `git worktree` 出一个 `master` 的纯净底座
（`ScalableLux-Master/`，构建产物 md5 `5e201b83861d4fd1cf7f8165718fbb2e`，与本次会话最早的基线一致），
而不是任何下载来的旧 artifact。**规矩：跑 A/B 前先单独量一次参照 jar，确认它落在已知档位**；每侧各带自己的
`expectedMod`（2.0 的 rig 变体是 `lucistarlinkrig`，底座是 `scalablelux`）。

### 7.2 第 1 项的实测结果（2026-09-21，同源交替 6+6，`structure_cube`）

| 侧 | 逐轮 minPass（ns） | 中位 | 比值 |
| --- | --- | --- | --- |
| **2.0（rig 变体 `2f866b87…`）** | 7,303,000 / 7,266,100 / 8,234,100 / 8,443,300 / 6,526,600 / 6,493,100 | **7,284,550** | |
| 纯净底座（`5e201b83…`） | 7,359,100 / 8,097,800 / 7,169,900 / 8,541,500 / 7,261,900 / 6,895,000 | **7,310,500** | |
| A/B | — | — | **0.9965**，精确 p = **0.8182**（U=16/20，C(12,6)=924 全枚举） |

`bench.pass_wall_actual`（3 趟合计 → 每趟，ms）：2.0 中位 **66.82**（66.55…66.97），底座中位 **66.70**（66.04…67.00），比值 ≈ 1.002，
精确 p = **0.9372**。这组 wall 与 §19 里官方 SL 的 66.5 ms/趟 完全同档。

**读法**：两项口径都**不可区分**——正是预期的结果（我们的新增不碰计算路径：遥测每 30 s 一行、内容相同的发布跳过、
默认关的批处理、一次缓存布尔的 Sable 判断）。**"不劣化"这一条成立。**

### 7.4 客户端探针：通过（2026-09-21，端到端真实会话）

**2.0 的客户端半边此前从未被跑过**（量具全是服务端）。这一轮补上：2.0 的 dev **服务端** + 2.0 的 dev **客户端**（`runClientProbe` / `runClientMultiplayer`，两个 run 配置加在 2.0 树里，Loom DSL），客户端连 `127.0.0.1:25565`。

**实测（客户端窗口里的真实会话）**

* 服务端日志：`Dev[/127.0.0.1:50142] logged in with entity id 115 at (-193.5, 70.0, 37.5)`、`Dev joined the game`；
* 客户端画面：正常进入世界（标题变成 `… - Multiplayer (3rd-party Server)`），沙漠/水面/天空**光照渲染正确**，无黑块；
* **在游戏里真的执行了命令**（此前只验证到"注册成功"）：
  * `/lucistarlink stats` → 聊天里回 `LuciStarlink tasks=0 dirty=0 poolSky=8 poolBlock=8 batchLimit=1 profile=false`；
  * `/lucistarlink light ~ ~ ~` → `LuciStarlink light -194, 70, 37 block=0 sky=15 raw=15 lightCorrect=true`（脚下白天 sky=15，正确）；
* 客户端日志无与本模组相关的异常（只有 logrotate 的两条"无法删除日志文件"）。

**过程中发现并修掉的**：stats 的聊天前缀漏改（仍是 `ScalableLux `）—— 已改为 `LuciStarlink `。

**两个操作要点（写下来省下一次踩坑）**：① 1.21 的 quick-play 需要 `--quickPlayPath`，且**首次启动的无障碍提示会挡住它**——点掉 `Continue` 之后 quickPlay 就会自动连接，之后每次启动都自动进服；② 2.0 的 dev run 不能同时容纳两个入口类，所以入口类要按 `mod_id` **在源集层面互斥**（只排除 jar 里的 class 不够，dev 运行读的是 `build/classes`）。
