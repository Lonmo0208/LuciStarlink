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
}
