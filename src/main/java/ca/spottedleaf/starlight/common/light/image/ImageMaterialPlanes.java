package ca.spottedleaf.starlight.common.light.image;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

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
            return existing;
        }

        if (PLANES.size() >= MAX_SECTIONS) {
            PLANES.clear();
        }

        final Plane plane = new Plane(level, section, chunkX, sectionY, chunkZ);

        // Homogeneity certificates, the same rule the per-cell path uses: air needs no bytes written (the plane
        // starts zeroed), and a palette that certifies position-independent full-opacity zero-emission states is
        // filled in bulk. Everything else is read cell by cell.
        if (!section.hasOnlyAir()
                && section.maybeHas(state -> !(state.getLightEmission() == 0
                        && !state.useShapeForLightOcclusion()
                        && state.getLightBlock(level, BlockPos.ZERO) == 15))) {
            for (int i = 0; i < 4096; i++) {
                final int x = i & 15, z = (i >>> 4) & 15, y = (i >>> 8) & 15;
                final BlockState state = section.getBlockState(x, y, z);
                final int packed = materialCache.lookupLight(level, state, plane.worldPos(i, pos));

                plane.opacity[i] = ImageMaterial.opacity(packed);
                plane.emission[i] = ImageMaterial.emission(packed);
            }
        } else if (!section.hasOnlyAir()) {
            java.util.Arrays.fill(plane.opacity, (byte) 15);
        }

        PLANES.put(section, plane);
        return plane;
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
