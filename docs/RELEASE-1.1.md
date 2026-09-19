# 1.1 发布内容与验证状态

发布线 `master` 上的下一版。所有改动都在**同一条测量链**里各自独立验证，不做捆绑声称。

## 已包含

### 1. 改方块路径的守卫开销（性能，dense 档）

诊断与证据在 `docs/TASK-PERF-DENSE.md`。要点：dense 的差距不在光照引擎，在**每次改方块被问了 2~3 次**
的守卫（内部含 `System.nanoTime()`、ThreadLocal 查找、Sable 查询），加上两段纯仪表注入也各问一次。

* 三段 `checkBlock` 注入合并为一段 HEAD，一次调用只问一次守卫；原版计时的哨兵同时收紧。
* 背压判定不再无条件读时钟：期限为 0 时直接返回，`tickRuntime` 把过期期限清零。
  顺带修掉「期限为 0 且 `nanoTime()` 为负时误判为正在背压」的隐患。
* 世界生成抑制抽成 `WorldgenWriteScope`：语义仍按线程，但先读全局计数快路径；
  不成对的退出把计数钳在 0（否则之后真正的作用域会被减到 0 而在开着的时候解除压制）。
* `LuxCompat.isSablePlotChunk` 先查 Sable 是否存在，未安装时不再解析 getter。

**效果**：同场 5 轮交错的 dense 从修复前的 1.83×（我方 2500 µs / SL 1368 µs）收到
**1.22×**（我方中位 2595 µs / SL 中位约 2125 µs）。修复的严格证明另跑 jar-A/B（旧 jar 与新 jar 交错）
填在 `docs/verify-baseline.txt` 旁边。

### 2. 发布光源按线程（正确性）

`LuxPublishEngine` 的「本 section 是否没变」光源原本是**单个 volatile 字段**。发布由作业线程完成，但同一
时刻可以有多个作业在跑，多维度服务器上还可能是不同 level 的作业：B 线程会读到 A 线程或另一个维度刚设进去的
光源，身份比较于是拿到**别的世界**的字节 —— 相等就会**跳过一次本来必须的发布（丢光）**。
改成按线程，并在采集结束后 `remove()`，避免工作线程的 ThreadLocal 钉住已卸载维度的光照引擎。

后续项（未做，因为会改变 worldgen 路径的跳过行为、污染正在测的表）：把光源作为**显式参数**沿
`publishRegion`/`publishChunk`/`collect` 传下去，那样连 worldgen 路径也能享受「没变就跳过」，且不需要任何隐藏状态。

### 3. 测试

新增 `WorldgenWriteScopeTest` 5 条：作用域内压制、嵌套作用域、不成对退出、别的线程的作用域不影响本线程、
别的线程退出不结束本线程仍开着的作用域（最后两条正是「把 volatile 快路径写成朴素布尔」会犯的错）。
总数 **29 通过 / 0 失败 / 1 继承跳过**（原 24 + 新 5）。

## 发布前必须做的两件事（尚未做）

1. **`dist/` 重新构建且元数据必须是 `incompatible`**：当前 `dist` 里那只是带上 `-PbenchmarkAllowScalableLux`
   的 rig 变体（`type = "discouraged"`），发布版必须是**不带任何 `-PbenchmarkAllow*`** 的 `./gradlew build`。
   同时装了 ScalableLux 的玩家拿到 `discouraged` 只会看到警告然后**启动崩溃**（两者都替换光照引擎）。
   做法：`./gradlew clean build`，然后 `unzip -p build/libs/<jar> META-INF/neoforge.mods.toml | grep -A1 'modId = "scalablelux"'`
   必须是 `type = "incompatible"`。
2. **重记 md5**：`docs/verify-baseline.txt` 里的 md5 与文件名要更新到新构建。

## 已测但不算 1.1 的功能承诺

* **C2ME 互操作**（用户提供的 `c2me-neoforge-mc1.21.1-0.4.0-alpha.0.122.jar`）：同一 `gen` 场景同 seed 跑两次
  （无 C2ME / 有 C2ME），**天空指纹三组逐位相同**、无崩溃、存档与关闭正常、我们的 mixin 全部应用（无
  `mixin apply failed`）。纯 worldgen 区域的 block 指纹也相同；含场景自带编辑的两组 block 不同 —— 按已确立的
  方法学 block 不是判据（原版自己也不稳），天空才是。细节与保留意见写在 `docs/HANDOVER.md` 未解决项 4。
  这是一次"能一起跑且光照一致"的测量，不是对 C2ME 加速管线的一般性承诺。
* **Generator Accelerator**：jar 不在手，仍未测。

## 明确不在 1.1 里

* 客户端光照接管、作业内并行（FlowSched 式）：功能工作，未开始。
* `sky_hole` 同步计算路径：与大方向（不阻塞服务器线程）相反，需要单独决策，见 `docs/TASK-PERF-SKY.md`。
* `experimentalBoundaryDeltas` + `BorderDeltaSupport` 的删除：默认关闭的死代码，删除本身无行为影响，
  但要一次专门提交 + 探针，不塞进 1.1。
* 发布光源改成**显式参数**（连 worldgen 路径也能享受"没变就跳过"）：会改变正在测的行为，留给 1.2。
