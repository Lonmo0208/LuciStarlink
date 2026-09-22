package ca.spottedleaf.starlight.common.chunk;

import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;

public interface ExtendedChunk {

    public SWMRNibbleArray[] scalablelux$getBlockNibbles();
    public void scalablelux$setBlockNibbles(final SWMRNibbleArray[] nibbles);

    public SWMRNibbleArray[] scalablelux$getSkyNibbles();
    public void scalablelux$setSkyNibbles(final SWMRNibbleArray[] nibbles);

    public boolean[] scalablelux$getSkyEmptinessMap();
    public void scalablelux$setSkyEmptinessMap(final boolean[] emptinessMap);

    public boolean[] scalablelux$getBlockEmptinessMap();
    public void scalablelux$setBlockEmptinessMap(final boolean[] emptinessMap);

    public boolean scalablelux$usingStarlight();

    @Deprecated(forRemoval = true)
    default  SWMRNibbleArray[] getBlockNibbles() {
        return scalablelux$getBlockNibbles();
    }
    @Deprecated(forRemoval = true)
    default void setBlockNibbles(final SWMRNibbleArray[] nibbles) {
        scalablelux$setBlockNibbles(nibbles);
    }

    @Deprecated(forRemoval = true)
    default SWMRNibbleArray[] getSkyNibbles() {
        return scalablelux$getSkyNibbles();
    }
    @Deprecated(forRemoval = true)
    default void setSkyNibbles(final SWMRNibbleArray[] nibbles) {
        scalablelux$setSkyNibbles(nibbles);
    }

    @Deprecated(forRemoval = true)
    default boolean[] getSkyEmptinessMap() {
        return scalablelux$getSkyEmptinessMap();
    }
    @Deprecated(forRemoval = true)
    default void setSkyEmptinessMap(final boolean[] emptinessMap) {
        scalablelux$setSkyEmptinessMap(emptinessMap);
    }

    @Deprecated(forRemoval = true)
    default boolean[] getBlockEmptinessMap() {
        return scalablelux$getBlockEmptinessMap();
    }
    @Deprecated(forRemoval = true)
    default void setBlockEmptinessMap(final boolean[] emptinessMap) {
        scalablelux$setBlockEmptinessMap(emptinessMap);
    }

    /**
     * R5: the flat byte-per-cell mirror of this chunk's light, one {@code byte[4096]} per light section, indexed exactly
     * like {@link #scalablelux$getSkyNibbles()} / {@link #scalablelux$getBlockNibbles()}.
     *
     * <p><b>Why it lives here.</b> The first attempt kept the mirror in the engine's cache slots and measured 27.8 s and
     * then 322 s for a pass that takes 4.6 ms: those slots are recycled across chunks, so the mirror was rebuilt on
     * nearly every access. Ownership has to follow the light data - allocated with the nibble array, dropped when that
     * array is replaced (chunk reload), one entry built on first touch and reused for the chunk's lifetime. Entries are
     * {@code null} until built, and a {@code null} array or entry means "no mirror", in which case callers use the
     * nibble path. That is why this can only change the cost, never the semantics.</p>
     */
    default byte[][] scalablelux$getSkyFlat() {
        return null;
    }

    default void scalablelux$setSkyFlat(final byte[][] flat) {
    }

    default byte[][] scalablelux$getBlockFlat() {
        return null;
    }

    default void scalablelux$setBlockFlat(final byte[][] flat) {
    }

    /**
     * R5: per-cell opacity of this chunk's blocks, one byte per cell (0 = fully transparent, 1..15 = the cached
     * opacity, {@link #MATERIAL_UNCACHED} = the block state's opacity is not cached and the shape path is needed),
     * one {@code byte[4096]} per light section, indexed like the nibble arrays.
     *
     * <p>Material is what the propagation actually consults - it needs the opacity of the neighbour it is about to
     * write into - and asking the palette for it costs ~20 ns per call (measured, 8,790 calls per pass on
     * structure_cube). Unlike light, material <b>cannot change during one propagation</b>, so a table here needs no
     * write-through and no versioning: an entry is dropped when a block in that section changes, and rebuilt on next
     * use. It also takes the section's 32 KB of palette references out of the propagation loop's working set.</p>
     */
    byte MATERIAL_UNCACHED = 16;

    default byte[][] scalablelux$getMaterial() {
        return null;
    }

    default void scalablelux$setMaterial(final byte[][] material) {
    }
}
