# 审阅：V2 阶段 1 在制品（另一窗口，2026-09-19 03:25-03:30）

范围：`git status` 里未提交的 6 个修改 + 2 个新文件 + 1 个新测试（`SectionStoreTest`）。审阅方式：读 diff + 跑套件。
**结论：设计正确，方向与 `docs/V2-PLAN.md` 阶段 1 一致，可以继续。** 两个隐患 + 一处注释不准确，见下。

## 一、做得对的（建议保持）

1. **"两种模式读同一份字节"被做成了代码性质**：`SectionStores.readEngineLayer` 是唯一的引擎读取点，两个适配器都走它 ——
   于是阶段 1 的验收（storage 与 region 读数逐 section 一致）**不需要靠测试去维持**，这是比"写个一致性测试"更强的做法。
2. **`EngineSectionStore.write` 在阶段 1 只测量不写**：`storage.shadow.sections` / `.identical.sections` 两个计数，
   行为与 region 模式完全一致 ✓ 这正是计划里"影子对比"的要求，且顺带给阶段 2 提供了它需要的量：
   **"算出来的 section 里有多少引擎已经有了"** —— 这个比例直接决定阶段 2 的"只通知真正变化的 section"能省多少。
3. 配置告警文案已按阶段 1 更新 ✓（不再是"未实现"，而是"读走接缝、写仍走 region 且只测量"）。

## 二、必须处理的两个隐患

### 隐患 1（阶段 2 会踩）：`SectionStores` 的每 level 缓存只按 level 作键
`SectionStores.of(level, publisher)` 用 `computeIfAbsent` 缓存，**先到的调用者决定 publisher**：
- 只读调用点（如 `adoptLayer`）传 `null` ✓ 若它先到，缓存里就是**带 null publisher 的 `RegionSectionStore`** ✗
- 之后任何经该 store 的 `write`（阶段 2 会真的用上）会因 publisher 为 null 而静默失效 ✗

今天不会爆（region 模式的写仍走旧的逐段发布路径），但阶段 2 一接上就是"某些 level 的写入静默消失"这类最难查的 bug。
**建议**：缓存键加上 publisher 是否存在的区分，或把 publisher 延迟解析（写时才取），或在 `write` 里显式 `Objects.requireNonNull(publisher)` 让它**快速失败**而不是静默。

### 隐患 2（现在就有代价）：`adoptLayer` 每 section 解析一次 store
`adoptLayer` 从"直接用传入的 `lightEngine`"改成"每调用一次就 `SectionStores.of(level, null)`" ✗ ——
而 `adoptLayer` 在热路径上（物化一个区域可达数千个 section，实测一轮 4680 次），于是**每个 section 多一次
`ConcurrentHashMap` 查找 + 一次模式字符串比较** ✗。
**建议**：在作业开始处解析一次 store，然后按参数传下去（或在 `LuxRelighter` 里按 level 缓存），把查找从"每 section"降到"每作业"。

## 三、一处注释不准确（小事）

`LuxRuntimeManager.storageShadowLine()` 的注释说这个比例"在 region 模式也会打印（打印成 0）以便两模式直接可比" ✗ ——
实际是 `asked == 0` 时**返回空串**，也就是 region 模式下**根本不做测量**（影子写入只在 storage 模式触发）。
两种处理都行，但要让它们一致：要么把注释改成"只在 storage 模式测"，要么让 region 模式也跑一遍比较（只读操作，代价可忽略），
后者更省事 —— 那样不必为了拿基线比例而切模式。

## 四、我这边同时补的东西（不与其重叠）

- **阶段 1 的验收工具** `mc-smoketest/storage-parity.sh`：同一份世界快照上，先用 region 模式跑一遍运行时边界编辑并
  导出逐格光照平面（`dumpplane` 输出 `LUCIS_LIGHT_PLANE` 每行一个 z ✓ 可逐格 diff），再用 storage 模式跑同一场景，
  然后**逐行比较两份导出**。判据是"完全一致" —— 这正是阶段 1（读路径统一、不写）应有的结果。
- 运行前后各插一次健康对照对（这台机器的健康窗口只有 ~10~15 分钟，见 V2-PLAN 的协议补充）。

## 五、给实施窗口的一句总结

阶段 1 的读路径可以按现状继续；请先把隐患 1 的"快速失败/延迟解析"和隐患 2 的"每作业解析一次"处理掉，
再进阶段 2 的写路径 —— 阶段 2 是主要收益，也是熔断点（`sky_hole` 改善 <15% 就停，见 V2-PLAN §5）。

## 七、修复后重验 + 影子计数的真相（2026-09-19 04:10，我接手后）

**两个隐患已修 + 注释已改**（提交 `1344716`）：publisher 改为从 level 解析（拿不到就抛，快速失败）、
`SectionStores.of(level)` 去掉参数、"缓存里可能存到 null-publisher 实例"这一整类 bug 消失；
`LuxRelighter` 加了单槽 store 缓存，把 `adoptLayer` 的开销从"每 section 一次 CHM 查找 + 字符串比较"降到每作业一次。

**修复后 parity 再次通过** ✓（`storage-parity.sh p2`）：指纹 identical=yes、16 行逐格 identical=yes、差异行空；
日志自证两轮分别是 `lightEngineMode=region` / `=storage` ✓。前后健康对照 我们 3.7/4.9、原版 4.3/3.7 ——
原版后测 3.7（低于带下限 4.2 约 12%），但 **parity 的判据是"逐格相等"，与机器快慢无关**，故结论不受影响 ✓。

### ⚠️ 影子计数测的不是阶段 2 需要的东西（重要更正）

| 模式 | `storage.shadow.sections`（被要求写） | 其中引擎已有 | `publish_direct.sections` | `skippedIdentical` |
|---|---|---|---|---|
| region | 0（不触发） | — | 23 | **86** |
| storage | **4222** | **30（0.7%）** | 41 | 88 |

`storage.shadow.*` 的 4222/30 看起来吓人，但它是**在发布之前**比较的：此刻引擎里是**旧**数据，
我们的新值当然几乎全不同 —— **接近同义反复，不能用来判断"交付里有多少是浪费"** ✗。

真正有用的是**本来就存在**的 `publish.skippedIdentical.sections`：**86/109 ≈ 79% 的脏 section 与引擎当前值完全相同**
→ **我们的实际交付量已经只剩约 21%** ✓。

**对阶段 2 的含义（写进计划）**：
- 交付**量**已经没有多少可省的 ✗ → 阶段 2 的收益**只能来自"去掉那趟往返"**（谁在光照线程、谁持有真相），
  不可能来自"少交 section" ✓ 这与之前 14 个交付侧杠杆全部无效的结论一致 ✓；
- 因此阶段 2 的验收必须直接看 `sky_hole` 的 per-pass min（同场交错 + 分块 + 前后对照对），
  **不能用"省了多少 section"当替代指标** ✗。

工具 `mc-smoketest/storage-parity.sh p1`（同一份场景世界快照 → region 模式跑一遍运行时边界编辑 → 恢复快照 →
storage 模式跑同一场景 → 逐行 diff 导出），2026-09-19 03:50：

| 检查 | 结果 |
|---|---|
| 前后健康对照 | 前：我们 wall=4.4 / 原版 4.5；后：我们 3.4 / 原版 4.5 —— **都在健康区间** ✓ |
| 实际生效的模式 | A 轮 `lightEngineMode=region` ✓、B 轮 `lightEngineMode=storage` ✓（日志自证） |
| `LUCIS_LIGHT_FINGERPRINT`（y=90~112 气层带） | A=2 / B=2 行，**identical=yes** ✓ |
| `LUCIS_LIGHT_PLANE`（每行一个 z 的逐格十六进制） | A=16 / B=16 行，**identical=yes** ✓✓ 逐格一致 |
| 差异行 | **空** ✓ |
| 基准计数导出 | 两侧均 0 行（该场景未开基准），不影响判据 |

**结论：阶段 1 的读路径统一没有改变任何光值 —— 判据是"完全一致"，实际也是"完全一致"。**
按计划这就是阶段 1 应有的结果（只读、不写），因此**阶段 1 的功能验收成立**。两点补充：
① 这个证据来自**含它未提交在制品**的构建（rig md5 `99a96c0a0803cb74639d413e2601beaf`），所以提交前后要再跑一次；
② 它新增的 `SectionStores` 影子计数只在 storage 模式触发（见 §三），所以"算出来的 section 里有多少引擎已经有了"
这个关键比例，只在 storage 模式的运行里能读到 —— 阶段 2 的大小评估就靠它。
