# 1.x → 2.0 配置迁移表

* 1.x 的配置在 `config/lucistarlink-common.toml`（NeoForge ModConfigSpec，25 个键）
* 2.0 的配置在 `config/lucistarlink.properties`（NeoForge 的 `config/` 目录下，首次启动自动生成）
* 系统属性仍可用（管理员/量具覆盖用）：`-Dscalablelux.<键>=<值>`，优先级高于配置文件

**一句话结论：1.x 的 25 个键里，只有 `enabled` 有对应物，其余 24 个都是"异步/区域映像引擎"专属的旋钮 —— 2.0 的底座（ScalableLux 同步引擎）根本没有那些概念，所以它们不是"待迁移"，而是"不存在"。** 迁移方式：删掉旧配置文件（2.0 不读它），按下面第二张表配置新键。

## 一、1.x 的 25 个键

| 1.x 键 | 2.0 里的对应 | 说明 |
| --- | --- | --- |
| `enabled` | **`enabled`**（新，默认 true） | 关掉就不装我们的光照引擎（用原版引擎），需要重启。兼容排查与真机 A/B 用 |
| `enableWorldgen` | 不适用 | 1.x 异步引擎的两条路径之一；要做"原版参照"请在量具里用 vanilla 模式 |
| `enableRuntime` | 不适用 | 同上（运行时那条路径） |
| `enableSky` / `enableBlock` | 不适用 | 1.x 把天空光/方块光分开调度；2.0 是一体引擎 |
| `verboseLogging` / `debug` | **`profile`**（新，默认 false） | 2.0 的诊断是 `profile=true` 的计数器 + 遥测行；没有逐事件日志 |
| `experimentalSectionFastPath`、`experimentalSkySeedSkip`、`experimentalDenseIncremental`、`experimentalInlineRuntime`、`experimentalRuntimeAdoption` | 不适用 | 1.x V3 的一系列实验开关（多数已否证），随引擎一起作废 |
| `haloPublish`、`worldgenHaloPublish`、`directSectionInstall`、`piggybackPublish`、`promptRuntimePublish`、`syncRuntimeDrain` | 不适用 | 全是 1.x **发布路径**的旋钮（区域映像→客户端的投递策略）；2.0 是同步引擎，光照跟着服务器 tick 走 |
| `forceLightIncorrectOnSave` | **底座自带**（无需键） | ScalableLux 的两条保存钩子always 生效，语义已逐条核对（见 `PORT-LUCIS-IDEAS.md` §10） |
| `regionChunks`、`haloChunks`、`runtimeHaloChunks`、`maxBatchChunks`、`maxCachedRegions`、`maxCachedRegionMegabytes` | 不适用 | 区域映像/缓存的容量与粒度参数；2.0 按区块存光照，天然有界，没有这些量 |

## 二、2.0 的键（`config/lucistarlink.properties`）

| 键 | 默认 | 含义 |
| --- | --- | --- |
| `enabled` | `true` | 是否安装光照引擎；false = 回到原版引擎（需要重启） |
| `telemetrySeconds` | `30` | 遥测行（`SLTELEM`）间隔秒数；`0` 关闭 |
| `profile` | `false` | 开发用计数器（`SLPROF` 行）；正常游玩保持关闭 |
| `profileIntervalNanos` | `2000000000` | profiler 行的打印间隔（纳秒） |
| `batchLimit` | `1` | **实验性**：每改动挂钩按批合并的批大小，`1` = 底座原行为（不批处理）。实测指标中性，留给 A/B |
| `parallelism` | `-1`（自动） | 光照工作线程数（**来自底座**，键名保持原样） |

对应用户可见行为：2.0 没有"区域/缓存/发布"这类需要调优的量，玩家通常只需要 `parallelism`（多核机器可调大）和 `telemetrySeconds`（想看状态时调小）。

## 三、验证

* 配置文件驱动：把 `telemetrySeconds=2` 写进 `config/lucistarlink.properties` 后启动，日志按 2 秒间隔出现
  `SLTELEM dim=… tasks=… dirty=… poolSky=… poolBlock=…`（**没有**传任何系统属性），已实测。
* 系统属性覆盖：量具传 `-Dscalablelux.telemetrySeconds=N` 时以系统属性为准（已实测）。
