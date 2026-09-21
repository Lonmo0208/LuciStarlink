# 缺陷记录：世界生成阶段天空光在区块最西列少 1~5 级（80 格，可复现）

* 状态：**已定位、未修**（1.x 引擎；1.2.10 上复现）
* 记录日期：2026-09-21
* 发现方式：跨引擎差分正确性探针（`docs/TASK-PERF-SKY.md` §20）

## 现象（可复现的三条读数）

同一盒子（x -40..55, y 32..96, z -40..55，599,040 格；`structure_cube`；随机刻冻结 RTS=0；落定后）：

| 引擎 | sky 指纹 | 差异 |
| --- | --- | --- |
| vanilla（两次跑相同） | `905931078dfc5ace` | 参照 |
| ScalableLux | `905931078dfc5ace` | **与 vanilla 逐位相同** |
| **我们 1.2.10** | `641356fc41163add` | **80 格不同**：`skyDiff=80 blockDiff=0 lightDiffWithStateSame=80` |

* 80 格**全部落在方块相同的格子上**（不是地形噪声），方块光零差异；
* 两次独立运行、以及 `passes=0` 的**纯 prepare 运行**都是同样的 80 格与同样的指纹
  → **差异在世界生成/加载阶段就已存在**，与基准的编辑序列、与编辑后的传播无关；
* 位置：两处贴区块边界的竖向带 —— 簇 A `x -32..-28, z -16..-14`（chunk -2 的最西三列、chunk -1 的南三行），
  簇 B `x 32..33, z -16..-7`（chunk 2 最西两列）；都从 `y≈50` 往上，我们比 vanilla **暗 1~5 级**。

## 剖面（`vanilla/ours`，x 从 -34 到 -26）

```
y=56 z=-16 : 7/7  6/6  6/1#  6/2#  6/3#  6/4#  6/5#  6/6  7/7
y=57 z=-16 : 8/8  7/7  7/2#  7/3#  7/4#  7/5#  7/6#  7/7  8/8
y=57 z=-17 : 9/9  8/8  8/8   8/8   8/8   8/8   8/8   8/8  9/9
```

* vanilla：`z=-17` 一行是均匀 8、`z=-16` 是均匀 7 —— **逐层 -1 的竖直衰减**，来源在上方/北侧；
* 我们：同样的格子上是 `2,3,4,5,6` 的**斜坡**，方向朝东 —— 只从东侧邻居收到光、每格 -1，
  而紧邻的西侧（`x=-33`）明明有 7 却过不来（一步落差 5，物理上不可能由同一组方块的自然衰减产生）。

**签名 = 跨区块边界的天光传播缺口**：本引擎在区块最西列遇到"光应从西侧/上方补进来"的洞时，少算了一条路径，
于是这些格子拿到的是绕远路的值。与 1.2.9（跨区去光收敛）、1.1.5（重启截断）同族，都属"区域映像的边界"这一类。

## 为什么对 2.0 不是问题

ScalableLux 在同一个盒子上与 vanilla **逐位相同**（上表），而 2.0 的底座就是它 —— 这条缺陷属于 1.x 的引擎，
换底座后自然消失。这也是"2.0 = 换 SL 底座"的理由之一。

## 复现方法（两条命令，任何一次都能重跑）

```bash
# 1) vanilla 参考（纯 prepare，无编辑）
RTS=0 PASSES=0 WARMUP=0 DUMP=E:/LuciStarlin/sl-jar/dump/refprep.sky \
  bash E:/LuciStarlin/mc-smoketest/vanilla-dump.sh structure_cube "-40,32,-40,55,96,55" 1 refprep
# 2) 我们这一侧，运行时直接对参考做差分（打印差异格坐标与两侧取值）
./gradlew runBenchmarkServer -PbenchmarkWorkload=structure_cube -PbenchmarkPasses=0 -PbenchmarkWarmupPasses=0 \
  -PbenchmarkRandomTickSpeed=0 -PbenchmarkLightFingerprint="-40,32,-40,55,96,55" \
  -PbenchmarkLightDiff=E:/LuciStarlin/sl-jar/dump/refprep.sky -PbenchmarkOutput=...
```

## 待办（若还要继续查 1.x）

1. 造一个**合成场景**把 80 格压到可解释的几格：一条洞、唯一光源在区块边界的另一侧（可用 setblock/命令构造，
   不必等世界生成），然后在 1.x 与 vanilla 上比同一格的光照值 —— 有差异即最小复现成立；
2. 顺着"区块最西列"定位到 1.x 的区域映像 halo / 边缘刷新路径（`LuxRuntimeManager` / 世界生成发布侧）；
3. 修好后用本文的两条命令回归，要求 `skyDiff=0`。
