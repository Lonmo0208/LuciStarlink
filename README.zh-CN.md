# LuciStarlink

[English](README.md) | **中文**

**LuciStarlink** 是面向 **Minecraft 1.21.1 / NeoForge** 的服务端光照引擎模组。

它的底座是 **ScalableLux**（Spottedleaf 的 stateless 光照引擎，作者 Spottedleaf、ishland、RelativityMC），
在这个底座上带着**一套我们自己做、自己量的更新回路**：编辑发生在哪次调用里，就在那次调用里把光传播并安装完；
改动按区块攒批；整片的天空光改动用一次**精确的窗口重算**收口，而不是发起一轮播种式洪泛。
它吸收的经验来自两条前辈线：ScalableLux，以及此前发布过的 1.x —— 后者的血统是 **Lucis**；外加本项目自己的
测试台量出来的经验，**包括那些结论是"别做"的**。

## 哪些是我们的，哪些不是

这一条最该先讲清楚，所以放在第一节。

| 层 | 代码归属 | 说明 |
|---|---|---|
| 光照存储、增量传播 BFS、区块/世界生成管线、原版接口、客户端发包路径 | **ScalableLux**（Spottedleaf、ishland、RelativityMC）—— LGPL-3.0-only | 引擎主体。包名（`ca.spottedleaf.starlight.*`）与 `scalablelux$` 混入前缀**故意保留**，好让每个文件的出处一眼可见。由 NeoForge 1.21.1 回溯树 `0.3.0-alpha.0.8` 产出。 |
| **本版本拿来被评判的更新回路** | **LuciStarlink** | 新增 21 个文件 + 改动 29 个 ScalableLux 文件（+1734/−457）。即下面的五条机制。 |
| 遥测、profiler、游戏内命令、Sable 兼容、配置、存档保证、测量用 entrypoint | **LuciStarlink** | 从 1.x 线继承下来的做法（见下）。 |
| 1.x 自己的引擎（区域图像 region images） | **一行都没有** | Lucis 那套要靠整块区域图像和自有的材料抽取；在本底座上移植过，结论是不可行（`docs/NEW-ENGINE-TEARDOWN.md`、`docs/PORT-LUCIS-IDEAS.md`）。 |

**所以：引擎主体是 ScalableLux 的代码，但决定下面那些数字的那一半是我们的。** 说它是"完全自研引擎"是错的；
说它是"ScalableLux 加个补丁"也是错的 —— M0–M3 阶段确实是后者，R 阶段把更新路径换掉了。

### 属于我们的五条机制（全部记录在 `docs/NEW-ENGINE-TEARDOWN.md`）

1. **就地编辑通道**：在做出改动的那次 `setBlockState` 调用里就把光传播并安装完，不交给光照线程。它完全不碰
   引擎队列，因此那 ~4.3 ms 的完成路径往返（十二次队列侧尝试全都死在它手上）一次都不用付。
2. **按区块攒批**：一次引擎调用处理一整批改动，而不是每格一次；每次刷新只付一次固定的 setup + 播种 + 抽干成本。
3. **合并的减光抽干 —— 做了、量了，然后因为不正确而删除（2.0.4）**：它让每个受影响的区块只做播种，整批结束后
   **只走一遍**减光队列。问题是那次抽干发生在播种已经**销毁引擎缓存**之后 —— 读不到邻居、也写不进任何一格，等于空转：
   传播完全没发生，放下的光源只有自己那一格亮（`docs/BUG-EMITTER-BLOCK-LIGHT.md` 根因四）。批量路径现在用基底自带的
   逐区块顺序：建缓存 → 播种 → 抽干 → 发布 → 销毁。它在 `block_toggle_border` 上那个"成绩"就是它没做的那些活
   —— 见上面性能一节的口径说明。
4. **窗口化天空收口**：整片天空光改动只重算编辑能影响到的 y 窗口（`±16`；因为光级是 0..15、每一步至少衰减 1，
   所以这是精确的），而且用一个"窗口顶边的一格读"代替逐列的调色板扫描来决定要不要扫。一次收口：整块版
   8900–9700 µs，窗口版 **213–281 µs**，光照结果完全一致。
5. **提升到区块级的守卫 + 方块光跳过**：线程/区块/状态检查按*区块*做一次，不是按每个改动做（apply 段
   704 → **548 ns/改动**）；另外，把一个本来就方块光为 0 的格子改成完全不透明，可证明不会推动方块光——而
   这正是每一次整片填充的形状。

开关**默认都是开的**；要关掉可以手动指定（`-Dscalablelux.ownEdit=false`、`-Dscalablelux.recomputeSky=false`，见"配置"）。这个列表原来还带着第三个开关 `scalablelux.batchDecrease`，已随它控制的代码一起删除。

## 性能

以下数字全部来自本项目自己的测试台（`run-benchmark-scalablelux`）：同一个会话、交替运行、同一张地图、固定种子，
每次测量前世界已静默。

**引擎口径** —— `minPassNanos`，引擎自己在一个 pass 里的工作量（取最好的那个 pass，避开跨 tick 的调度噪声）。
3 轮中位数，同一会话，CPU 平均 29.9%：

| 档位（玩家的说法） | **LuciStarlink 2.0** | ScalableLux | 1.x（Lucis 线） |
|---|---|---|---|
| `block_toggle_border` —— 沿区块边界快速拆放 | **0.32 ms** | 4.01 ms | 0.84 ms |
| `structure_cube` —— 盖一个实心建筑 | **2.51 ms** | 4.97 ms | 2.69 ms |
| `dense_chunk_patch` —— 大面积改动 | **1.07 ms** | 3.65 ms | 1.96 ms |
| `sky_hole` —— 一次很小的改动 | **0.69 ms** | 0.88 ms | 0.73 ms |

作为参照，**原版**（不装任何光照模组）在同一个测试台的四档读数是 10.41 / 10.81 / 10.17 / 1.03 ms —— 那是另一个
会话量的，所以按数量级引用，不要当成同会话比值。

**玩家口径** —— `bench.pass_wall_actual`，一个 pass 的墙钟时间，**包含**玩家真正要等的跨 tick。中位数 / 最好一轮，
同一会话：

| 档位 | LuciStarlink 2.0 | ScalableLux | 1.x |
|---|---|---|---|
| border | **49 / 49 ms** | 50 / 49 ms | 101 / 85 ms |
| structure | **49 / 49 ms** | 49 / 49 ms | 68 / 55 ms |
| dense | **50 / 50 ms** | 50 / 47 ms | 73 / 65 ms |
| sky_hole | 50 / 50 ms | 50 / 50 ms | **49 / 27 ms** |

**这两张表的诚实读法：**

- **对 ScalableLux：引擎口径四档全赢（1.3× 到 12.5×），玩家口径打平。** 打平不是遗憾——49–50 ms 就是**一个服务端
  tick**，任何引擎要让改动在下一 tick 被看见，都只能做到这里，两台引擎都站在这个地板上。
- **对 1.x：引擎口径四档全赢，玩家口径四档赢三档。** 例外是 `sky_hole`：1.x **最好那一轮** 27 ms 快过我们的
  50 ms。那一档是"一次 25 格的小改动"，而 1.x 的引擎在那里本来就是同步的。这是本文档里唯一一处前辈更快的地方。
- **`structure_cube` 这一格必须随数字一起说明的口径**：那一格约 **90% 的读数**是 Minecraft 自己的
  `Level.setBlockState`（写状态、高度图、标脏、原版同步的光照钩子）——**每一个**引擎量到的都是 600–775 ns/改动，
  原版也一样。1.x 的 2.86 ms 里约 2.6 ms 就是这层地板，它自己的光照工作只剩 ~0.26 ms。所以这一格**不可能**出现
  大比分胜利，我们也不行。
- **不是速度，而是正确性**：本引擎产出的光照在 599,040 格的验证箱上与**原版和 ScalableLux 逐位相同**——每次运行
  都会打印指纹（`sky=905931078dfc5ace block=59e2252f732ce67b`）。1.x 线的指纹是 `641356fc41163add`，**即它不等同
  原版**。窗口重算复现的是引擎自己那条传播规则本身，不是近似它，而这一点在每次验收里都会被查。

**在发布默认值上复验过**（完全不带任何 `-Dscalablelux.*` 参数，只有测试台协议三件套，CPU 平均 8.3%）：
0.331 / 2.698 / 1.029 / 0.772 ms，玩家口径 48.5–50.5 ms/pass，指纹仍为标准值。**默认构建就是被验收的那台引擎。**

## 兼容性

- 会替换光照引擎，因此与 Starlight、ScalableLux、Lucis 以及任何接管原版光照管线的模组**互斥**。模组元数据里
  已把 ScalableLux 声明为不兼容。
- 支持 **Sable**：每个 plot 的光照引擎让给 Sable，不去和它抢。
- 与地形优化模组（C2ME、Generator Accelerator、Fast Noise）可共存；测试台能直接测这些组合。
- 三个引擎开关**只在**"当前是不是 `ServerLevel`、是不是服务器线程"这两道守卫之内被读取，所以客户端跑的就是
  未改动的底座路径——**这次改动对客户端完全不可见**。

## 配置

首次运行会写出 `config/lucistarlink.properties`，里面每个键都有。每个键也能用系统属性覆盖
（`-Dscalablelux.<key>=`），测试台和管理员靠它免改文件。

| 键 | 默认 | 含义 |
|---|---|---|
| `parallelism` | 核数 / 3 | 光照引擎的工作线程并行度（底座的键） |
| `enabled` | `true` | 是否安装光照引擎。`false` 让所有维度留在原版引擎 —— 兼容逃生口，也是在真实服务器上做 A/B 的办法。改动需要重启 |
| `telemetrySeconds` | `30` | `SLTELEM` 行的间隔秒数（队列深度、脏格数、池化传播器数）；`0` 关闭 |
| `profile` | `false` | 开发用 profiler 计数器打到日志 |
| `batchLimit` | `1` | 逐改动钩子在进入完整调度路径前攒多少个改动（`1` = 底座行为）。实测中性，留作 A/B |

引擎开关（只能用系统属性，因为它们在类加载时读一次）：

| 属性 | 默认 | 含义 |
|---|---|---|
| `-Dscalablelux.ownEdit` | **`true`** | 就地编辑通道（机制 1、2）。`=false` 回到底座的队列路径 |
| `-Dscalablelux.recomputeSky` | **`true`** | 窗口化天空收口（机制 4） |
| `-Dscalablelux.recomputeMinChanges` | `128` | 攒到多少格天空改动才切到重算（缓冲每 256 格刷新一次，所以阈值再大就永远触发不了） |
| `-Dscalablelux.inlineMinBurst` | `0`（关） | 把小批量改回异步路径的分派规则。**已由测量否决**：它把边界档从 0.43 ms 变成 4.03 ms |
| `-Dscalablelux.bulkRelight` | `false` | 实验：同一区块的大批量用一次整块重光照收口 |
| `-Dscalablelux.recomputeDebug` | `false` | 每次收口的各阶段耗时打到日志 |

## 命令

`/lucistarlink`（权限等级 2）：

- `stats` —— 引擎实时状态：队列深度、脏格数、池化传播器数、新引擎计数器。
- `light <pos>` —— 引擎在该位置持有的光级，按层显示，并给出它用的方块状态。
- `relight [radius]` —— 把半径内**已加载**的区块标成"光照未计算"，交给引擎自己的 `lightChunk` 路径重算。它同时
  也是**换模组之后**的修复手段：由另一个光照引擎写下的存档把"没传播完的光"记成了"已计算"，症状是"方块只亮
  自己那一格"。

## 构建

需要 **JDK 21**（`JAVA_HOME` 也要指向它——Gradle 否则会自己挑一个 JDK），Loom 构建：

```bash
./gradlew build          # jar 出在 build/libs/
```

测量用构建（`./gradlew build -Pmod_id=lucistarlinkrig`）会把 mod id 与 mixin 配置改名，好让这个 jar 能与 1.x 的
测试台模组**同时**加载，做 A/B。

如果这台机器的 TLS 链只存在于操作系统证书库里，构建前设
`JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`，否则依赖下载会 `PKIX path building failed`。

GitHub Actions 的 CI 用 JDK 21 编译整棵树，**不上传任何产物**：本项目的发布件一律留在本地。

## 许可与归属

**LuciStarlink 由 Lonmo 开发** —— Copyright (c) 2026 Lonmo。

**LGPL-3.0-only**（见 [LICENSE](LICENSE)）—— 这是必须的，且与两条前辈线的许可一致：本发行版是
**ScalableLux**（Spottedleaf、ishland、RelativityMC）的衍生作品，它的前一代 1.x 线则是 **Lucis**
（Team Argentum、DenisMasterHerobrine）的衍生作品。完整的归属说明与相对底座的改动清单见 [NOTICE](NOTICE)。
再分发必须保留这些声明与许可。
