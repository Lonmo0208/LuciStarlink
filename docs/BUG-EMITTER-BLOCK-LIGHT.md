# BUG（已修复）：放置的光源方块不亮 / 退出重进后不发光

**状态：已修复、已端到端验证（2.0.2 + 2.0.3）。** 三个独立缺陷叠在一起，而且**只有第三个**解释玩家实际遇到的那一格。

## 症状

玩家说法：「光源方块放置后 退出存档 重新进 会不发光了」。服务端可复现的最小形式（专用服务器，控制台）：

```
setblock 0 150 0 minecraft:glowstone
lucistarlink light 0 150 0   ->  block=0 sky=15 raw=15 updBlock=0    ← 萤石自己那格是 0，应该是 15
```

- **可见层与引擎内部层（`updBlock`）都是 0** —— 不是"算了没发布"，是根本没算。
- 换原版引擎（`-Dscalablelux.enabled=false`）同一条命令读到 `block=15` → 测试本身没问题。
- 受影响的是**不透明度 15 的发光方块**：萤石、海晶灯、红石灯、蛙明灯等（火把不透明度 0，不受影响）。
- 存档后再进：那格仍是 0（存盘存的就是没算出来的值）→ 玩家看到「重进不亮了」。

## 根因一（2.0.2）：批量填充的"方块光空写"跳过规则漏了发光判定

`StarLightInterface.toBlockPositions(...)`（R5 的 §30 优化）当时写的是：

```java
if (blockLight.getLightValue(pos) == 0) {          // 这一格现在没有方块光
    if (opacityOf(state) == 15) { continue; }      // 而且现在完全不透明 → 认为对方块光无影响，跳过
}
```

推理漏了一步：**萤石正是不透明度 15 的发光方块**。"已经暗 + 现在不透明"这句既描述石头也描述萤石，而后者自己那格必须变成 15。
**修法**：跳过前必须同时确认 `state.getLightEmission(world, pos) == 0`。

## 根因二（2.0.2）：攒批的改动没有"必定发生"的服务器线程刷新点

就地通道把改动攒在 `lucis$pendingEdits` 里、只在服务器线程应用，而它原本唯一的落地点 `hasUpdates()` **光照线程也会调用**
（被线程守卫正确拒绝），原版又只在**引擎队列里有活**时才从服务器线程问一次——就地通道恰恰故意不排队列，形成自锁。
实测空闲服务器上改动躺了 **5 秒**才落地；玩家"放完就退出"正好落在窗口里 → 存盘存的是改动前的光。
**修法**：`ServerWorldMixin` 注入 `ServerLevel.tick` 的 HEAD，每 tick 在服务器线程刷新一次（最多晚 1 tick = 50 ms）。

## 根因三（2.0.3）★ 真正解释玩家那一格的：区块键 `-1` 与"保留这个区块"的哨兵值撞车

`hasUpdates()`/`lucisFlushPendingEdits()` 用 `keepKey = -1L` 表示"不保留任何区块"，而区块键的算法是

```java
(long)x & 0xFFFFFFFFL | ((long)z & 0xFFFFFFFFL) << 32        // x = z = -1  →  0xFFFFFFFFFFFFFFFF  =  -1L
```

**区块 (-1,-1) 的真实键就是 -1L。** 于是那座区块的每一次刷新都把它当成"正在攒、先别动"跳过——**改动永远不落地**，
无论走哪个开关、无论有没有 tick 钩子。玩家的坐标 (-1.8, -39.1, -8.3) 就在这座区块里（出生点旁边的那一格），
**他在家附近放的光源全部被静默吞掉**；而离开这座区块一格就正常。这就是"光源方块不发光"的完整答案。

排查里最坑的一点：**之前的服务端测试用的是 (0,150,0) → 区块 (0,0) → 键为 0 ≠ -1**，所以两个"已修复"版本都通过了测试，
而玩家那一格依然是黑的。**直到把玩家的存档复制出来、在同一个坐标上做追踪**，才看到刷新的循环体一次都没进：

```
FLUSHDBG enter thread=Server thread pending=1 ...   （刷新在跑，但一次都没 apply）
EDITDBG blockChange BlockPos{x=-1, y=-37, z=-8} -> inline lane
（没有 FLUSHDBG apply；改动静默消失）
```

**修法**：哨兵不再用 `-1`，而是显式的布尔参数——`lucis$flushAllPendingEdits(settle)` 走 `keepOne=false` 分支，
只有"正在攒的那一个区块"才跳过。用一座不存在的区块做哨兵值，本身就是错的设计。

## 验证

**玩家存档的副本 + 玩家本人的客户端**（同一坐标 (-1,-37,-8)，先清成空气再放萤石 = 真实改动）：

| 场景 | 读数 |
|---|---|
| 引擎侧专用服务器（存档副本） | `block=15 updBlock=15` ✓ |
| 存盘 → 重启 → 同一存档 | `block=15 updBlock=15` ✓（持久化） |
| **玩家自己的客户端（游戏内命令）** | `block=15 updBlock=15 state=Block{minecraft:glowstone}` ✓ |

**注意一个容易误判的现象**（我踩过）：一个**已经存盘的、当时没算出光的**发光方块不会自己变亮——它没有"变化"就不会触发光照。
要修旧存档里的黑格，用 `/lucistarlink relight <半径>` 或在原地重放一次方块。

## 别丢掉的教训

1. **哨兵值必须证明不可能与真实键冲突**（`-1` 在区块键空间里是合法值）。这类 bug 只影响"那一个区块"，最容易被当成随机现象。
2. **验收的指纹关只跑了 `structure_cube`**（全石头，599,040 格里只有 273 格有方块光）——**没有任何一格包含光源**，
   所以这三个 bug 一个都拦不住。规则已补（`docs/ARCHITECTURE.md` §4）：至少一格含发光方块 + 比**方块光**指纹 + 放置→存盘→重载检查，
   工具在 `tools/emitter-gate/`。
3. **量具会说谎**：`checkBlockCalls` 的计数写在 `batchLimit>1` 的分支里，默认配置下永远是 0。
4. **同一 run 目录不能同时跑客户端和服务端**（抢 `run/logs/latest.log`）→ 已加 `runServerDiag`（独立目录 + 独立端口）。
5. **往 gradle 控制台管道喂多条命令会丢命令**；数据包 + `schedule` 才确定。
6. **测试要打在玩家的坐标上**。(0,150,0) 通过≠玩家的家通过——因为区块键 -1 只影响那一格区块。

## 症状

玩家说法：「光源方块放置后 退出存档 重新进 会不发光了」。服务端可复现的最小形式（专用服务器，控制台）：

```
setblock 0 150 0 minecraft:glowstone
lucistarlink light 0 150 0   ->  block=0 sky=15 raw=15 updBlock=0    ← 萤石自己那格是 0，应该是 15
```

- **可见层与引擎内部层（`updBlock`）都是 0** —— 不是"算了没发布"，是根本没算。
- 换原版引擎（`-Dscalablelux.enabled=false`）同一条命令读到 `block=15` → 测试本身没问题。
- **火把类不透明的方块光 0 的光源不受影响**；受影响的是**不透明度 15 的发光方块**：萤石、海晶灯、红石灯、蛙明灯等。
- 存档后再进：那格仍是 0（存盘存的就是没算出来的值）→ 玩家看到「重进不亮了」。

## 根因一：批量填充的"方块光空写"跳过规则漏了发光判定（真正的元凶）

`StarLightInterface.toBlockPositions(...)`（R5 的 §30 优化）当时写的是：

```java
if (blockLight.getLightValue(pos) == 0) {          // 这一格现在没有方块光
    final BlockState state = world.getBlockState(pos);
    if (opacityOf(state) == 15) {                   // 而且现在完全不透明
        continue;                                   // → 认为对方块光"可证明无影响"，跳过
    }
}
```

推理漏了一步：**萤石正是不透明度 15 的发光方块**。"已经暗 + 现在不透明"这句既描述了石头，也描述了萤石——
而后者自己那格必须变成 15。于是萤石被当成石头跳过，它的光从来没被算过。
（这条规则本来要省的正是 `structure_cube` 的 4096 块石头填充，那个场景完全成立，所以验收看不出来。）

**修法**（`StarLightInterface`，一行）：

```java
if (opacity == 15 && state.getLightEmission(this.world, mutable) == 0) { continue; }
```

## 根因二：攒批的改动没有一个"必定发生"的服务器线程刷新点

R2 就地通道把改动攒在 `lucis$pendingEdits` 里，只在服务器线程上应用。它原本唯一的落地点是
`hasUpdates()`——但那个入口**光照线程也会调用**（被线程守卫正确拒绝），而原版只在**引擎队列里有活**时
才从服务器线程问一次；就地通道恰恰故意不排队列，于是形成自锁式等待。

实测（专用服务器、无玩家）：改动攒了 **5 秒**才被应用。玩家"放完就退出"正好落在这个窗口里 → 存盘存到的是
改动前的光 → 重进是黑的。测试台 100% 正常，因为它的每轮护栏都会从服务器线程主动问一次——**这就是为什么
所有验收都放过了它**。

**修法**：`ServerWorldMixin` 注入 `ServerLevel.tick` 的 HEAD，每 tick 在服务器线程上调用
`StarLightInterface.lucisFlushPendingEdits()`。改动最多晚一个 tick（50 ms）落地，任何存盘都发生在它之后。

## 验证

**玩家场景端到端**（数据包驱动，两段启动，同一存档）：

| 阶段 | 读数 |
|---|---|
| 第一次进服：放萤石 + 存盘 + 停服 | 放下瞬间 `block=0`（同 tick 读，光尚未落地） |
| **重新进服** | **`block=15 sky=0 updBlock=15`** ✓ |

**引擎侧追踪**（`-Dscalablelux.editDebug=true`）：

```
EDITDBG blockChange BlockPos{x=0, y=150, z=0} -> inline lane
FLUSHDBG exit: not the main thread (light) pending=1        ← 修复前的自锁：光照线程的请求被拒
（修复后）
EMITDBG checkBlock 0,150,0 state=Block{minecraft:glowstone} emit=15 before=0
LuciStarlink light 0, 150, 0 block=15 ... updBlock=15
```

**四档回归**：见 CHANGELOG 2.0.2 的记录（本次修复后重跑）。

## 别丢掉的教训

1. **验收的指纹关只跑了 `structure_cube`**（全石头的 4096 格改动，599,040 格里只有 273 格有方块光）——
   **整套验收里没有任何一格包含光源**，所以这个 bug 一次都没被拦住。规则已补：**至少一格工作负载必须含发光方块，
   并且它的方块光指纹也要与 vanilla/ScalableLux 逐位比**；再加一条独立的"放置 → 存盘 → 重载"小协议
   （就是本文档上面那张两段启动表，脚本在 `/tmp/reload-verify.sh`，可复制进仓库）。
2. **量具本身也会说谎**：`checkBlockCalls` 的计数写在 `batchLimit>1` 的分支里，默认配置下永远是 0，让我误读成
   "方块改动从没通知引擎"，白跑了两轮。
3. **同一 run 目录不能同时跑客户端和服务端**（抢 `run/logs/latest.log`），已加 `runServerDiag`（独立 `run-diag`
   目录 + 独立端口）——这是本轮排查反复扑空的真正原因。
4. **往 gradle 控制台管道喂多条命令会丢命令**；改成数据包 + `schedule` 后一切确定（45 秒后执行、自动停服）。
