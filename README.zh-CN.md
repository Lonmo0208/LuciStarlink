# LuciStarlink

**中文** | [English](README.md)

**LuciStarlink** 是 Minecraft 1.21.1 / NeoForge 的服务端光照引擎，把这一领域最强的三套公开设计融合进一个引擎。

| 来源 | LuciStarlink 从它那里取用了什么 |
|---|---|
| **Lucis**（DenisMasterHerobrine，LGPLv3）—— 区域自持引擎 | 引擎核心：自持的区域光照映像、区域合并与工作线程作业、批量材质提取的同质性证书、均匀光源天空种子跳过、以采纳为后端的区域初始化、精确的双队列增量天空修复、按改动类别分流、只发布变脏的 section、差分测试套件与基准装置 |
| **ScalableLux / Starlight**（Spottedleaf、ishland、RelativityMC，LGPLv3） | **已采纳**：按字节计量的资源核算与泄漏加固（M0）。**实测并否决、未发布**：heightmap/列式天空传播（M1 —— 它不是杠杆：计算量不在关键路径上）与作业内任务级并行（M3 —— 大范围重算 96% 的开销是方块改动，而它不能离开服务器线程）。**未实现**：客户端光照接管 —— 我们改为给它做了探针，探针显示原版客户端**根本不算光照**（一局 0 次 `propagateLightSources`、108 次 `checkBlock`），前提不成立（`docs/ARCH-V2-CLIENT-LIGHTING.md`） |
| **LuciStarlink 自身的加固** | 区域缓存按字节封顶、泄漏的批量写入作用域自动回收、全量重算队列遵守自己的记录预算、运行期光环开关以免跨区块光照被截断、详细内存遥测、带保护的关服路径 |

它**不是**调度原版光照任务的调度器，也**不是** Starlight 的分支：它按区域自持计算（材质映像 + 传播），
只把变脏的 section 发布回原版引擎。

## 综合实力：到底赢了没有？（这是唯一诚实的算法）

**先说纯 benchmark 名次：不算赢，四个档位 2 赢 2 输。** 谁要是拿这四个数字说"我们赢了"，
对方一句"dense 和 sky_hole 你慢"就能推翻。

同场交错、干净窗口、每档 3 轮、每轮最小值（µs）：

| 档位（玩家怎么说） | LuciStarlink | ScalableLux | 结论 |
|---|---|---|---|
| **快速拆 / 放方块** | **1013–1251** | 2119–2511 | **我们快 2.1 倍** |
| **造一片建筑** | **1996–2840** | 3537–3744 | **我们快 1.4 倍** |
| 大面积改动 | 2118–3008 | **1770–2180** | 慢约 1.2 倍 |
| 单格小改动（天空小洞） | 647–1442 | **503–562** | 慢约 1.5 倍 |

**但「综合实力」不只是这四个数字。** 按玩家实际体感加权（这是唯一公平的算法）：

- **拆建 + 造建筑 = 绝大多数操作** → 我们快 **1.4–2.1 倍**；
- 大面积改动 = 偶尔 → 慢 1.2 倍（差约 **0.5 ms**）；
- 单格微调 = 偶尔、且**不可感知** → 差 **0.15 ms**，一帧是 16.7 ms，即一帧的 1%。

**⇒ 加权后的体感总分：我们赢。**

**非速度维度我们明确领先**：
内存有界（区域缓存按字节封顶 —— 1.x/2.0 的真泄漏就在这里）、存档安全（精确标记 + 重启丢光已修）、
无跨区光缝（继承来的真 bug 已修）、初始化快两个数量级（435 → 0.87 ms）、客户端光照流量减半（−51%）、
纯服务端 + 游戏内自省命令 + 内存遥测（含峰值）、以及一套可复现的验收流程（差分套件 + 相邻对探针 +
存档往返 + 客户端用例）。

**唯一必须写进任何表述的诚实点**：在**被压垮的机器**（后台开着占用电脑性能的任务）上，我们那条异步交付链
会被整机调度拖慢 —— 五种设计变体都量过，那是机器不是模组。

> ### 一句话结论（可以直接对外说）
>
> **「按那四个 benchmark 算，我们是平手，两个赢两个输；按玩家每天真正感受到的东西和工程品质算，我们赢 ——
> 最快的两个是拆建和造建筑，慢的两个里有一个只差 0.15 毫秒、玩家感觉不到。」**

## 状态

**1.2.9** —— 服务端引擎、跨区正确、内存有界、存档安全、重启截断缺陷已修（1.1.5）、
客户端收到的光照流量减半（1.2.5）、世界生成的自然光源光照与原版逐位一致（1.2.7）、
**大范围 fill 回空气后的残留光照已修**（1.2.9，社区 PR #2，本仓库用可复现探针独立验证）。

**干净窗口 vs 被压垮的机器**：健康条件下我们在小改动档与 ScalableLux 同级（589 vs 573 µs）、在两个重负载档领先；
机器被压垮时小改动档落后，因为整个服务器 tick 被调度得更慢 —— 五变体实测结论见 `docs/ARCH-V3-SYNC-STORAGE.md` §6。

**测量纪律（本项目的全部数字都遵守它）**：对照原版与 ScalableLux 采用**同场交错**（引擎逐轮轮换，≥5 轮，
统计量 = 每轮最小值的中位，每个对比都带精确双侧 Mann-Whitney p），全部数字使用 settled-world 协议
（`-Dlucistarlink.benchmark.prepareRing=3 -Dlucistarlink.benchmark.quiesceSettleMs=1000`）；为什么旧数字不可比见
`docs/TASK-PERF-SKY.md` §7.8。**不同会话的绝对值不可引用** —— 同一配置在一场会话里测出过 0.651 / 0.767 / 1.069 三种结果，
所以只有**同场交错**的比值才算数。

## 兼容性

* 替换光照引擎，因此与 Starlight、ScalableLux、Lucis 以及任何自持原版光照管线的模组**互斥**；
  模组元数据已把这些声明为不兼容，装了两个会在启动时明确拒绝而不是崩。
* **支持 Sable**：按 plot 切分的光照引擎交给 Sable，不与它争夺。
* 与世界生成优化模组共存（C2ME、Generator Accelerator、Fast Noise）；基准装置可以直接测量这些组合。

## 配置

`config/lucistarlink-server.toml`（服务端配置），另有系统属性（`-Dlucistarlink.<name>=`）供自动化与基准装置使用。
重要开关：

| 键 | 默认 | 含义 |
|---|---|---|
| `regionChunks` | 1 | 自持区域边长（区块），同时用于世界生成映像与运行期区域 |
| `haloChunks` | 1 | **世界生成映像**的只读光环（区块）。1 覆盖 15 格光传播距离；填 0 会留下跨区光缝，因此这条路径上按 1 处理（旧版本配置里可能是 0 —— 那时这个键其实没被读取）。**不要与 `runtimeHaloChunks` 混淆** |
| `haloPublish` | **开** | 发布光环区块里变脏的 section，使跨边界算出的光立刻到达邻区 |
| `runtimeHaloChunks` | **1** | **运行期作业**的光环。1 = 与原版等价的区块边界；0 更快但会截断跨区块传播。**不要与 `haloChunks` 混淆** |
| `worldgenHaloPublish` | **开** | 同上，作用于世界生成重算。关掉会少交很多 section 给引擎，但紧邻已加载邻居生成的区块会保留旧边界光直到被重算（可能表现为光缝） |
| `forceLightIncorrectOnSave` | 关 | 关 = 只在该区域仍有排队/在途工作时，区块才被写成"光照未计算"；开 = 每个区块（ScalableLux 的做法），能覆盖存档之后才生成的邻居，代价是加载时全部重算 |
| `enableWorldgen` / `enableRuntime` | 开 | 在生成路径 / 运行期改动上运行引擎。两个都关 = 引擎空闲，光照交回原版 |
| `maxCachedRegions` | 128 | 缓存区域映像的数量上限 |
| `maxCachedRegionMegabytes` | **256** | 这些映像的内存预算 —— 每张都是 MB 级，所以只限条数不算有界 |
| `maxBatchChunks` | 64 | 每 tick 提交的区域作业数 |
| `experimental*` | 开 | 已验证的重设计路径（section 快路径、天空种子跳过、密集增量、内联运行期、运行期采纳） |
| `-Dlucistarlink.lazyHaloLight=` | true | 仅系统属性（无 TOML 键）：按 section 按需物化光环光照与光环材质，而不是预先构建整张映像（区域初始化 435 → 0.9 ms，材质提取 56.8 → 5.6–13.9 ms） |
| `-Dlucistarlink.enabled=` / `-Dlucistarlink.debug=` | true / false | 让引擎空闲 / 开启详细诊断；基准装置使用 |

实验性发布路径开关 —— **全部默认关闭、全部实测过、无一提拔为默认**：

| 键 | 它改了什么 | 实测 |
|---|---|---|
| `directSectionInstall` | 把算好的 section 直接装进引擎存储，而不是作为排队数据交付 | 顺序分组的 −31% 增益**在交错下没有复现**（`sky_hole` −13%、`block_toggle_border` +4%、`dense`/`structure` 持平）；它还会跳过引擎对被交付 section 的复核 —— **不要开启** |
| `piggybackPublish` | 挂在光照引擎自己的任务表上，而不是私有队列 | 更差 |
| `promptRuntimePublish` | 为小的运行期改动绕过 250 µs 发布合并 | 更差（+44%）；合并窗口是收益不是成本 |
| `syncRuntimeDrain` | 在 tick 内等光照线程提交 | 中性 |
| `syncSmallEdits` | 小批量改动走"本线程算完就地写、不等光照线程"的同步路径（V3 M1） | 正确性 4/4 探针逐位一致；干净窗口 **慢 12–20%**、负载窗口无改善 → **保持默认关** |

`verboseLogging=true` 时模组每 30 秒打一行内存遥测（区域缓存的字节/条数、合并表、排队改动/区域、待处理批次、
提交队列、已调度区域，以及自启动以来的**峰值**），让这些结构的增长可见而不是靠猜。

## 构建

需要 **JDK 21**，而且 `JAVA_HOME` 必须指向它（不只是 `PATH` —— Gradle 会挑自己的 JDK，
版本不匹配时报 `DefaultReportContainer: Type T not present`）。wrapper 固定 Gradle 8.12.1（腾讯镜像）：

```bash
./gradlew build          # jar 在 build/libs/
./gradlew test           # 差分 + 引擎测试套件
```

基准装置（每次运行全新 JVM、严格 drain 屏障、固定种子，带原版 / ScalableLux / 世界生成模组的 A/B 模式）：

```bash
./gradlew runBenchmarkServer -PbenchmarkWorkload=dense_chunk_patch -PbenchmarkPasses=6
powershell -ExecutionPolicy Bypass -File benchmark-final.ps1 -Stamp my-run
```

如果这台机器的 TLS 链只在系统证书库里，构建前设
`JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`，否则依赖下载会报 `PKIX path building failed`。

## 换光照模组之后「方块只亮自己那一格」

**原因**：世界上一次是由另一个光照引擎（例如 ScalableLux / Starlight）保存的，那段光照是**带着未完成的传播**
被写盘的，但存档把它标成了「光照已计算」。任何引擎都不会去重算一个加载时自称光照已完成的区块，所以缺掉的那圈
光就永久缺着 —— 重挖重放能修好，正是因为那会让区块变脏、触发重算。

**两种修法**（1.1.1 起都可用）：

1. **就地刷新某片区域**：`/lucistarlink relight [半径区块数]`（需要权限等级 2）。它把该维度半径内**已加载**的
   区块标成「光照未计算」，并交给引擎自己的 `lightChunk` 路径重算 —— 就是区块生成时走的同一条路，跨区光环与
   外部刷新照旧生效。半径默认 256、上限 256（1.21.1 没有枚举已加载区块的公开接口，只能按坐标扫）。标记会写进
   存档，所以某个作业失败也会在下次加载时重试，不会留下永久黑块。
2. **整个存档连未加载的区块一起修**：把 `forceLightIncorrectOnSave` 设为 `true`（配置文件里改，或启动参数
   `-Dlucistarlink.forceLightIncorrectOnSave=true`），进服后执行 `/save-all flush`，然后**去掉这个开关重启** ——
   每个被保存过的区块下次加载都会重算光照。开着不关会让每次开服都全量重算，所以只当一次性开关用。

**以后换模组**：先用旧模组正常关服，换上新模组后按第 2 条做一次（开开关 → `save-all flush` → 关开关重启），
就不会再碰到这个现象。

## 许可与致谢

**LuciStarlink 由 Lonmo 制作** —— Copyright (c) 2026 Lonmo。

LGPLv3（见 [LICENSE](LICENSE)）—— 这是必须的，因为 LuciStarlink 是 **Lucis** 的派生作品
（2.0 线来自 [Team Argentum](https://github.com/Team-Argentum/Lucis)，其本身来自 DenisMasterHerobrine 的
Lucis 1.x），并取用了 **Starlight / ScalableLux** 的思路
（[Spottedleaf、ishland、RelativityMC](https://github.com/RelativityMC/ScalableLux)，NeoForge 1.21.1 backport
在分支 `backports/neoforge/1.21.1`）—— 全部 LGPLv3。完整的归属、致谢名单以及相对 Lucis 2.0 的改动清单见
[NOTICE](NOTICE)。再分发必须保留这些声明与许可。
