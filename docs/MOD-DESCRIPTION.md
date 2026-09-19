# LuciStarlink — mod description (for mod pages, metadata and README headers)

Written 2026-09-19 against **1.2.6**. Every number below is from the repository's own interleaved
measurements (`README.md` standing table, `docs/TASK-PERF-SKY.md`, `docs/FOURWAY-PLAYER-TABLE.md`); the honest
caveats are part of the description on purpose - the project's whole measurement history is public in `docs/`.

---

## 1. Metadata one-liner (`mod_description`, mod list, ~200 characters)

> LuciStarlink is a server-side light engine for Minecraft 1.21.1 / NeoForge. It owns the per-region light
> computation and hands only changed sections back to the vanilla engine, so light updates stay correct across
> chunk borders and saves, while block-edit and structure workloads get measurably faster.

## 2. Short blurb (mod page summary, ~700 characters)

> **LuciStarlink** replaces Minecraft's light engine on the server with a region-owned one: it extracts a region's
> materials as a single image, propagates light with the exact same rules vanilla uses, and publishes only the
> sections that actually changed. Nothing needs to be installed on the client.
>
> Measured against **ScalableLux** in same-session interleaved runs (engines alternate round by round, median of
> per-pass minima, exact Mann-Whitney p): **1.78× faster on border-heavy edits** and **1.36× faster on structure
> builds**, while **`dense_chunk_patch` is 1.25× slower** and **`sky_hole` 1.26× slower**. Against **vanilla** it is
> 1.5-4.9× faster, with parity on `sky_hole`. Two workloads decisively ahead, two behind - that is the honest
> summary, and `docs/` shows every measurement behind it.

## 3. Full description (Modrinth / CurseForge / GitHub page)

### What it is

A **server-side light engine** for Minecraft 1.21.1 / NeoForge. It is not a scheduler on top of vanilla's light
tasks and not a fork of Starlight: it owns the computation per region (a material image plus light propagation with
vanilla's exact rules) and publishes only the sections whose light actually changed back into the vanilla engine,
through the vanilla path, so clients, saves and other mods see light exactly as they would without it.

### What it does

* **Region-owned light images** with coalescing and worker jobs, so a burst of block changes becomes one region job
  instead of hundreds of small ones.
* **Certified bulk extraction** - whole sections that are air or fully opaque are filled in bulk by certificate
  instead of cell by cell.
* **Adoption-backed region init**: a region starts from the engine's current light instead of recomputing it, which
  removes the bootstrap cost on chunk load.
* **Exact incremental skylight repair** with two queues (increase/decrease), so a skylight change is repaired
  exactly, not approximately.
* **Cross-border correctness**: a job's image carries a halo, and the halo's changed sections are published too,
  so light a change pushed across a chunk border is visible on the other side immediately - verified with a client:
  a light placed on a chunk border appears on **both** sides of the border in a connected client within 2 s,
  without reconnecting or reloading.
* **Memory-bounded and leak-hardened**: the region cache is bounded by bytes as well as entries, bulk-write scopes
  are reaped if a caller leaks one, and verbose mode prints a memory telemetry line so you can watch it stay
  bounded.
* **Saves are safe**: a chunk written while the engine still has work for it is marked light-incorrect, so it is
  relit on load instead of coming back with light permanently missing (the 1.1.5 restart-truncation fix).
* **Half the light traffic to clients** (1.2.5): only the neighbours whose shared face actually changed are told,
  cutting the sections sent per session from 11,729 to 5,783 (≈24 MB → ≈11.8 MB per two minutes) with the display
  identical.
* **Diagnostics**: `/lucistarlink status`, `/lucistarlink flags`, and light-dump commands (`dumplight`,
  `dumpplane`) for reproducing a light question numerically instead of by eye.

### Performance, honestly

Measured **in the same session, interleaved** (our engine and the peer alternate run by run, ≥5 reps each, statistic
= median of per-pass minima, exact two-sided Mann-Whitney p). Absolute numbers drift up to ~40 % between groups of
one session, so only same-run comparisons count:

| workload | LuciStarlink | ScalableLux | vs ScalableLux | p |
|---|---|---|---|---|
| `block_toggle_border` (light on a chunk border) | **1.308 ms** | 2.323 ms | **1.78× faster** | 0.0079 |
| `structure_cube` (16³ build/dismantle) | **2.753 ms** | 3.749 ms | **1.36× faster** | 0.69 |
| `dense_chunk_patch` (16×16×8 dense edit) | 2.595 ms | **2.069 ms** | 1.25× slower | 0.0317 |
| `sky_hole` (small edit in the sky) | 0.719 ms | **0.573 ms** | 1.26× slower | 0.056 |

Against **vanilla** the whole-run times are 1.5-4.9× faster, with parity on `sky_hole`.

**Two workloads decisively ahead, two behind.** We are not claiming more than that: the small-edit workload pays an
inherent async hand-off cost of roughly 0.15 ms per edit because we hand sections to the vanilla engine instead of
replacing it, and every mechanism that could close that gap was implemented and measured before being rejected
(synchronous publishing, direct section install, prompt dispatch, storage ownership - see
`docs/TASK-PERF-SKY.md`, `docs/ARCH-V3-SYNC-STORAGE.md`). Several things the predecessors use were measured here and
*rejected*: a heightmap/column-driven sky path, intra-job parallelism, and taking over client-side lighting (the
probe showed a vanilla client does no light computation at all).

### Compatibility

* **Server-side only** - install it on the server; clients need nothing (it works with vanilla clients).
* **Mutually exclusive** with Starlight, ScalableLux, Lucis and anything else that replaces the lighting pipeline:
  the mod metadata declares those as incompatible, because two engines owning the same vanilla internals would
  crash at startup rather than cooperate.
* Minecraft **1.21.1**, NeoForge (built and tested against 21.1.x), licensed **LGPLv3**.

### Where it comes from

LuciStarlink fuses the three strongest public designs in this space, with attribution and an LGPL change list in
`NOTICE`:

* **Lucis** (DenisMasterHerobrine / Team Argentum, LGPLv3) - the region-owned engine core: region images, worker
  jobs, homogeneity certificates, uniform-source skylight skipping, adoption-backed init, the differential test
  suite and the benchmark harness.
* **Starlight / ScalableLux** (Spottedleaf, ishland, RelativityMC, LGPLv3) - ideas, several of which were measured
  here and rejected; their byte-budgeted resource accounting was adopted after our own leak audit.
* **LuciStarlink** - the hardening and the measurement discipline: byte-bounded caches, leaked-scope reaping, the
  save-safety hook, the worldgen border guard, the client-traffic cut, and the interleaved-measurement protocol
  every number above comes from.

---

## 4. 中文版（给中文社区 / 玩家）

> **LuciStarlink 是一个纯服务端的光照引擎**（Minecraft 1.21.1 / NeoForge），客户端不需要装任何东西。
> 它以"区域"为单位自己算光：把一块区域的方块材质抽成一张映像，用与原版完全相同的规则传播光照，只把**真正变化了
> 的 section** 交回原版引擎——所以客户端、存档和其它模组看到的光照，和没有它时一样。
>
> **实测（同场交错、逐轮交替、中位数、附精确 p 值）**：面对 **ScalableLux**，边界类编辑快 **1.78×**、
> 结构建造类快 **1.36×**；但密集批量编辑慢 **1.25×**、天空小改动慢 **1.26×**。面对**原版**整体快 1.5–4.9×。
> 一句话：**两个负载明确领先、两个落后**，我们不夸大。
>
> 其余要点：跨区块边界的光照正确（边界放光，已连接客户端 2 秒内在**两侧**都亮，无需重连）、存档安全（1.1.5 修掉
> 了重启丢光）、内存有界并自带遥测、发给客户端的光照流量在 1.2.5 砍掉一半（每 2 分钟 11,729 → 5,783 个 section）。
> 与 Starlight / ScalableLux / Lucis **互斥**（同装会在启动时崩溃，模组元数据已声明不兼容）。
> 许可 LGPLv3，署名与变更清单见 `NOTICE`。
