# LuciStarlink 2.0 —— 换 ScalableLux 底座（立项）

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
* **M1 功能迁移**：配置面（1.x 的 ModConfigSpec 键 → 新配置，逐键给迁移说明）、Sable 兼容守卫（1.x 会退让给
  Sable 的分区光照引擎，SL 底座上要重做同等守卫）、1.x 的保存/重启保证逐条对到 SL 的两条钩子
  （本分支文档 §10 已完成核对：等效或更强）。
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
* **M3 验收与发布**：见 §5；通过后打 tag、写 verify-baseline、双语 README/CHANGELOG。

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
