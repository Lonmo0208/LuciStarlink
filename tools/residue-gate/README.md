# 残留门

大范围 `/fill` 荧石 → 空气 循环，停在空气那一步，然后问引擎自己：**visible 与 updating 一致吗**。

## 它补的是哪一块

`tools/emitter-gate` 覆盖"放了一个光源，存盘重进还亮吗"。它自己的 README 说得很清楚：四档验收看不到那一类 bug，因为唯一被 gate 的工作负载是 `structure_cube`，而它的改动全是非发光方块。

这个门补的是另一块：**玩家手操尺寸的批量清除**。上游的 cycle gate 用的是自造的小形状（好处是能确定性复现），这里用的是用户实报的尺寸 —— 32×32 铺一层荧石、整层清成空气、保存退出重进会看到残留亮斑。

## 判据：全部来自引擎自己的仪器

| 命令 | 它回答什么 |
| --- | --- |
| `lucistarlink layerdump <r>` | 走遍半径内已加载区块，打印 visible 与 updating 不一致的 section。**不一致 = 某次 merge 从未发生**，也就是残留的形态本身 |
| `lucistarlink relight <r>` | 修复路径。之后 `layerdump` 必须干净 —— 否则说明不一致能被"修出来"而不是被清掉 |

**没有新增任何生产路径代码。** 这个门只读引擎已有的诊断仪器。

## 怎么跑

```bash
bash tools/residue-gate/residue-gate.sh                       # 从脚本位置推项目根
bash tools/residue-gate/residue-gate.sh /path/to/LuciStarlink # 指定项目根
LANE_INVARIANT=1 bash tools/residue-gate/residue-gate.sh      # 额外开图像通道逐格自校验
KEEP_WORLD=1 bash tools/residue-gate/residue-gate.sh          # 保留世界（默认先删，保证首触干净）
```

可调的环境变量：`RADIUS`（默认 1，覆盖 3×3 区块）、`FILL`（默认 `80 80 111 111`，2×2 区块）、`Y`（默认 120）、`BOOT_TIMEOUT`（默认 420 秒）、`LOG`。

**退出码**：`0` = 干净；`1` = 有残留；`2` = 环境起不来。

## 为什么开 `LANE_INVARIANT` 有价值

`ImageLane.LANE_INVARIANT` 是逐格比对"映像为某个 lane-owned section 持有的"与"世界持有的"（约 110 µs/section）。类注释里写明它就是为该抓 2026-09-27 那次 16 格错位而加的 —— 那次 image 算对了光、pack 写低了一个 section，只有这个比对会失败而其它一切看起来健康。

**它默认关着，因为它是给门用的，不是给生产用的。** 这个门提供的就是那个"门里开着跑一遍"的入口：代价只落在这条命令上，生产路径零成本。

## 设计上刻意沿用的两条教训

来自 `tools/emitter-gate/README.md`，两条都是踩出来的：

- **不用管道喂控制台命令。** stdin 喂多条命令会在中间静默丢命令且不报错。全部用 datapack 的 `schedule` 串起来。
- **客户端与服务端不能共用 `run/`。** 两个活 JVM 会抢 `run/logs/latest.log`，第二个死在启动期，报的错看起来像 mod 故障。所以这里也用 `run-diag/`。

另外：`45s` 才动手，等 spawn 生成结束、视野内区块都过了 `LIGHT`。

## 已知限制

- **一次启动，不是存盘往返。** 这个形状的残留是内存态就出现的（`layerdump` 直接能看到 visible≠updating），所以不需要像 `emitter-gate` 那样重启存档。要覆盖"存盘后客户端看到什么"得另加一轮，`emitter-gate.sh` 里有现成的写法可以照抄。
- **`layerdump` 的半径上限是 4**（命令本身的约束），以 4×4 区块的瓦片算，一圈半径 4 能覆盖到相邻瓦片边界；更大范围的残留要靠多次调用或提高 `RADIUS` 到上限。
- **判定靠日志里的 `⚠`/`mismatch` 关键字。** 如果哪天 `layerdump` 换了输出格式，判定要跟着改。
