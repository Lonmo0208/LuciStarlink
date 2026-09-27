package ca.spottedleaf.starlight.common.light.image;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The chunk-level material plane: one section's opacity/emission bytes, extracted from the section's block states
 * ONCE per section and reused by every region that needs it. This is what the border workload required: a
 * scattered change reaches sections that up to nine neighbouring 1-chunk regions each materialized separately, and
 * each pass reaches new y bands, so per-region extraction walked the same section repeatedly (measured: ~760
 * sections a pass, ~10 ms of the settle).
 *
 * <p><b>Exactness.</b> A plane is only valid while it mirrors the section's states. Two mechanisms keep that true:
 * <ul>
 *   <li><b>Write-through</b>: {@code LevelChunkSection.setBlockState} is the funnel every block write passes
 *       through (features, structures, worldgen stages and gameplay alike - the finding behind TEARDOWN §18), and
 *       the mixin on it updates the plane's single cell with the new state's material. So a plane follows writes
 *       exactly, not approximately.</li>
 *   <li><b>Identity + drop</b>: a plane is bound to the {@link LevelChunkSection} instance it was read from; a
 *       section object replaced by a chunk reload no longer matches and is rebuilt. Anything that rewrites states
 *       without going through the funnel ({@code recalcBlockCounts}, or any path the mixin cannot see) drops the
 *       plane instead of trusting it.</li>
 * </ul>
 * The store is bounded: past {@link #MAX_SECTIONS} it is cleared wholesale, which costs one re-extraction per
 * section and can never be silently wrong.</p>
 */
public final class ImageMaterialPlanes {

    /** 8 KB per section (two byte planes of 4096); 4096 sections is 32 MB, the default bound. */
    public static final int MAX_SECTIONS = Integer.getInteger("scalablelux.imageLaneMaterialPlanes", 4096);

    private static final ConcurrentHashMap<LevelChunkSection, Plane> PLANES = new ConcurrentHashMap<>();

    private ImageMaterialPlanes() {}

    /** One section's materials. {@code opacity}/{@code emission} hold 4096 cells in vanilla's (y<<8)|(z<<4)|x order. */
    public static final class Plane {
        public final Level level;
        public final int chunkX;
        public final int chunkZ;
        public final int sectionY;
        public final LevelChunkSection section;
        public volatile long lastUseNanos = System.nanoTime();
        public final byte[] opacity = new byte[4096];
        public final byte[] emission = new byte[4096];

        Plane(final Level level, final LevelChunkSection section, final int chunkX, final int sectionY, final int chunkZ) {
            this.level = level;
            this.section = section;
            this.chunkX = chunkX;
            this.sectionY = sectionY;
            this.chunkZ = chunkZ;
        }

        /** World position of a local cell, for the material lookup's conditional-opacity queries. */
        BlockPos worldPos(final int localIndex, final BlockPos.MutableBlockPos mutable) {
            return mutable.set((this.chunkX << 4) | (localIndex & 15),
                    (this.sectionY << 4) | ((localIndex >>> 8) & 15),
                    (this.chunkZ << 4) | ((localIndex >>> 4) & 15));
        }
    }

    /** The plane for this section, extracted on first use (or after a drop). Never null for a loaded section. */
    public static Plane get(final Level level, final LevelChunkSection section,
                           final int chunkX, final int sectionY, final int chunkZ,
                           final ImageMaterialCache materialCache, final BlockPos.MutableBlockPos pos) {
        final Plane existing = PLANES.get(section);

        if (existing != null && existing.section == section) {
            existing.lastUseNanos = System.nanoTime();
            return existing;
        }

        if (PLANES.size() >= MAX_SECTIONS) {
            evictOldestHalf();
        }

        final Plane plane = new Plane(level, section, chunkX, sectionY, chunkZ);

        // Homogeneity certificates, the same rule the per-cell path uses: air needs no bytes written (the plane
        // starts zeroed), and a palette that certifies position-independent full-opacity zero-emission states is
        // filled in bulk. Everything else goes through the packed extraction below.
        if (!section.hasOnlyAir()
                && section.maybeHas(state -> !(state.getLightEmission() == 0
                        && !state.useShapeForLightOcclusion()
                        && state.getLightBlock(level, BlockPos.ZERO) == 15))) {
            if (!section.maybeHas(BlockState::useShapeForLightOcclusion)) {
                extractPacked(level, section, plane, materialCache, pos);
            } else {
                // A shape-occluding state's material depends on the cell it is asked about, so there is nothing to
                // share between cells and the per-cell path is the only correct one.
                for (int i = 0; i < 4096; i++) {
                    final int x = i & 15, z = (i >>> 4) & 15, y = (i >>> 8) & 15;
                    final BlockState state = section.getBlockState(x, y, z);
                    final int packed = materialCache.lookupLight(level, state, plane.worldPos(i, pos));

                    plane.opacity[i] = ImageMaterial.opacity(packed);
                    plane.emission[i] = ImageMaterial.emission(packed);
                }
            }
        } else if (!section.hasOnlyAir()) {
            java.util.Arrays.fill(plane.opacity, (byte) 15);
        }

        PLANES.put(section, plane);
        return plane;
    }

    /** Set to compare the packed extraction against the per-cell one, and to print both timings, once per section. */
    private static final boolean EXTRACT_VERIFY = Boolean.getBoolean("scalablelux.planeExtractVerify");

    /**
     * Handles to {@code PalettedContainer.data} (a private volatile field) and to the {@code storage}/{@code palette}
     * accessors of {@code PalettedContainer$Data} (a package-private record).
     *
     * <p>Why handles rather than a mixin: an {@code @Accessor} can only be declared with an <b>exact</b> field type -
     * Mixin rejects a supertype with "No candidates were found matching data:Ljava/lang/Object;" - and that record
     * cannot be named in a signature at all because it is package-private. Both accessor variants were tried; the
     * record is in docs/HANDOVER.md 10.16. Reflection resolved once into MethodHandles costs a few nanoseconds a call
     * and is used once per section. If resolution fails, {@link #PACKED_EXTRACT} stays false and every extraction
     * takes the per-cell path: a lost optimisation, never a correctness or startup risk.</p>
     */
    private static final boolean PACKED_EXTRACT;
    private static final java.lang.invoke.MethodHandle PACKED_DATA_GET;
    private static final java.lang.invoke.MethodHandle PACKED_STORAGE_GET;
    private static final java.lang.invoke.MethodHandle PACKED_PALETTE_GET;

    static {
        java.lang.invoke.MethodHandle dataGet = null;
        java.lang.invoke.MethodHandle storageGet = null;
        java.lang.invoke.MethodHandle paletteGet = null;

        try {
            final Class<?> dataClass = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");
            final java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                    PalettedContainer.class, java.lang.invoke.MethodHandles.lookup());

            dataGet = lookup.findGetter(PalettedContainer.class, "data", dataClass);
            storageGet = lookup.findVirtual(dataClass, "storage",
                    java.lang.invoke.MethodType.methodType(net.minecraft.util.BitStorage.class));
            paletteGet = lookup.findVirtual(dataClass, "palette",
                    java.lang.invoke.MethodType.methodType(net.minecraft.world.level.chunk.Palette.class));
        } catch (final Throwable throwable) {
            System.out.println("PLANEXTRACT packed extraction unavailable, falling back to per-cell: " + throwable);
        }

        PACKED_DATA_GET = dataGet;
        PACKED_STORAGE_GET = storageGet;
        PACKED_PALETTE_GET = paletteGet;
        PACKED_EXTRACT = dataGet != null && storageGet != null && paletteGet != null;
    }

    private static final BlockState[] EXTRACT_STATE_BY_INDEX = new BlockState[64];
    private static final byte[] EXTRACT_OPACITY_BY_INDEX = new byte[64];
    private static final byte[] EXTRACT_EMISSION_BY_INDEX = new byte[64];
    private static byte[] EXTRACT_SCRATCH_OPACITY;
    private static byte[] EXTRACT_SCRATCH_EMISSION;

    /**
     * Reads a whole section at the <b>packed</b> level: {@code storage.get(cell)} gives a palette index, and the
     * material is looked up once per <i>distinct index</i> rather than once per cell. A section holds a handful of
     * distinct states (2-3 for the shapes this engine is measured on), so 4096 virtual palette lookups collapse into
     * 4096 array reads plus a few lookups.
     *
     * <p>Why it matters beyond the lane: an engine that owns its storage - the direction the remaining engine-axis gap
     * points at - has to populate a dense image when a chunk loads, and the per-cell path costs ~185 µs a section
     * there (the palette API alone is ~45 ns a cell), which is 4.4 ms for one chunk's 24 sections. This path is
     * ~20-30 µs a section, i.e. ~0.4-0.7 ms a chunk, which is what makes populating on load viable at all.</p>
     *
     * <p>The caller has already established that no state in this section is shape-occluding, so a state's material is
     * a pure function of the state and the per-index table is exact. Indices are cached in a fixed table of 64; a
     * section whose palette is wider than that (a {@code GlobalPalette} section, i.e. one that has seen many states)
     * falls back to the per-cell path.</p>
     */
    private static void extractPacked(final Level level, final LevelChunkSection section, final Plane plane,
                                      final ImageMaterialCache materialCache, final BlockPos.MutableBlockPos pos) {
        final PalettedContainer<BlockState> container = section.states;
        final long startNanos = EXTRACT_VERIFY ? System.nanoTime() : 0L;
        final byte[] opacity = plane.opacity;
        final byte[] emission = plane.emission;
        final BlockState[] stateByIndex = EXTRACT_STATE_BY_INDEX;
        final byte[] opacityByIndex = EXTRACT_OPACITY_BY_INDEX;
        final byte[] emissionByIndex = EXTRACT_EMISSION_BY_INDEX;
        final net.minecraft.util.BitStorage storage;
        final net.minecraft.world.level.chunk.Palette<BlockState> palette;

        if (!PACKED_EXTRACT) {
            perCellExtract(level, section, plane, opacity, emission, materialCache, pos);
            return;
        }
        try {
            final Object data = PACKED_DATA_GET.invoke(container);

            storage = (net.minecraft.util.BitStorage) PACKED_STORAGE_GET.invoke(data);
            palette = (net.minecraft.world.level.chunk.Palette<BlockState>) PACKED_PALETTE_GET.invoke(data);
        } catch (final Throwable throwable) {
            perCellExtract(level, section, plane, opacity, emission, materialCache, pos);
            return;
        }

        java.util.Arrays.fill(stateByIndex, null);

        for (int i = 0; i < 4096; i++) {
            final int index = storage.get(i);

            if (index >= stateByIndex.length) {
                // palette wider than the table: not worth sharing, and this section is a rarity
                perCellExtract(level, section, plane, opacity, emission, materialCache, pos);
                return;
            }
            BlockState state = stateByIndex[index];

            if (state == null) {
                state = palette.valueFor(index);
                final int packed = materialCache.lookupLight(level, state, plane.worldPos(i, pos));

                stateByIndex[index] = state;
                opacityByIndex[index] = (byte) ImageMaterial.opacity(packed);
                emissionByIndex[index] = (byte) ImageMaterial.emission(packed);
            }
            opacity[i] = opacityByIndex[index];
            emission[i] = emissionByIndex[index];
        }

        if (EXTRACT_VERIFY) {
            verify(level, section, plane, materialCache, pos, System.nanoTime() - startNanos);
        }
    }

    private static void perCellExtract(final Level level, final LevelChunkSection section, final Plane plane,
                                       final byte[] opacityOut, final byte[] emissionOut,
                                       final ImageMaterialCache materialCache, final BlockPos.MutableBlockPos pos) {
        for (int i = 0; i < 4096; i++) {
            final int x = i & 15, z = (i >>> 4) & 15, y = (i >>> 8) & 15;
            final BlockState state = section.getBlockState(x, y, z);
            final int packed = materialCache.lookupLight(level, state, plane.worldPos(i, pos));

            opacityOut[i] = ImageMaterial.opacity(packed);
            emissionOut[i] = ImageMaterial.emission(packed);
        }
    }

    /** Runs the per-cell extraction over the same section and compares, printing both timings once per section. */
    private static void verify(final Level level, final LevelChunkSection section, final Plane plane,
                               final ImageMaterialCache materialCache, final BlockPos.MutableBlockPos pos,
                               final long packedNanos) {
        if (EXTRACT_SCRATCH_OPACITY == null) {
            EXTRACT_SCRATCH_OPACITY = new byte[4096];
            EXTRACT_SCRATCH_EMISSION = new byte[4096];
        }
        final long startNanos = System.nanoTime();

        perCellExtract(level, section, plane, EXTRACT_SCRATCH_OPACITY, EXTRACT_SCRATCH_EMISSION, materialCache, pos);
        final long perCellNanos = System.nanoTime() - startNanos;
        int mismatches = 0;
        int firstMismatch = -1;

        for (int i = 0; i < 4096; i++) {
            if (plane.opacity[i] != EXTRACT_SCRATCH_OPACITY[i] || plane.emission[i] != EXTRACT_SCRATCH_EMISSION[i]) {
                if (firstMismatch < 0) {
                    firstMismatch = i;
                }
                mismatches++;
            }
        }
        System.out.println("PLANEXTRACT section " + plane.chunkX + "," + plane.sectionY + "," + plane.chunkZ
                + " packed=" + packedNanos / 1000 + "us perCell=" + perCellNanos / 1000 + "us"
                + (mismatches == 0 ? " identical" : " MISMATCH n=" + mismatches + " first=" + firstMismatch
                        + " packed[o=" + (plane.opacity[firstMismatch] & 0xFF) + ",e=" + (plane.emission[firstMismatch] & 0xFF)
                        + "] perCell[o=" + (EXTRACT_SCRATCH_OPACITY[firstMismatch] & 0xFF)
                        + ",e=" + (EXTRACT_SCRATCH_EMISSION[firstMismatch] & 0xFF) + "]"));
    }

    /**
     * Evicts the older half of the store instead of clearing it wholesale. Clearing cost one re-extraction per
     * section on the next use, and with worldgen continuously filling the bound the store was clearing often
     * enough that a pass re-extracted ~32 mixed sections (border measured 3.2 ms of materialization a pass with
     * the planes nominally in place). Evicting half keeps the working set alive.
     */
    private static void evictOldestHalf() {
        final java.util.ArrayList<Plane> all = new java.util.ArrayList<>(PLANES.values());

        all.sort(java.util.Comparator.comparingLong(plane -> plane.lastUseNanos));

        final int toRemove = Math.max(1, all.size() / 2);

        for (int i = 0; i < toRemove; i++) {
            PLANES.remove(all.get(i).section, all.get(i));
        }
    }

    /**
     * The write funnel (mixin call site): one cell of the section changed. If a plane is live, update that cell -
     * exact write-through, so the plane never goes stale for a write anyone can see.
     */
    public static void onSectionWrite(final LevelChunkSection section, final int x, final int y, final int z,
                                      final BlockState newState, final ImageMaterialCache materialCache,
                                      final BlockPos.MutableBlockPos pos) {
        final Plane plane = PLANES.get(section);

        if (plane == null || plane.section != section) {
            return; // nothing cached: nothing to keep in step
        }

        final int index = (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
        final int packed = materialCache.lookupLight(plane.level, newState, plane.worldPos(index, pos));

        plane.opacity[index] = ImageMaterial.opacity(packed);
        plane.emission[index] = ImageMaterial.emission(packed);
    }

    /** Drops a section's plane: for rewrites the funnel cannot see (bulk recalcs, section replacement). */
    public static void drop(final LevelChunkSection section) {
        PLANES.remove(section);
    }

    /** Drops every plane of one chunk (external engine writes, chunk unload). */
    public static void dropChunk(final int chunkX, final int chunkZ) {
        PLANES.values().removeIf(plane -> plane.chunkX == chunkX && plane.chunkZ == chunkZ);
    }
}
