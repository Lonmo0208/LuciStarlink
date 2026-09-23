# BUG（已修复）：放置的光源方块不亮 / 退出重进后不发光

**状态：已修复、已端到端验证。** 两个独立缺陷叠在一起，只修一个都还会复发。

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
