package ca.spottedleaf.starlight.common.light.image;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class ImageMaterialCache {
    private static final VarHandle INT_ARRAY = MethodHandles.arrayElementVarHandle(int[].class);
    private static final int UNCACHED = 0;
    private static final int DYNAMIC_OPACITY = 1 << 31;
    private static final int ENCODED_DYNAMIC_MARKER = 1;
    private static final int DYNAMIC_EMISSION_SHIFT = 1;
    private static final int DYNAMIC_FLAGS_SHIFT = 5;

    // each slot publishes one self-contained int
    // a stale/uncached read can only cause a duplicate recompute, not wrong light data
    private final int[] lightCache = new int[Block.BLOCK_STATE_REGISTRY.size()];

    // ----------------------------------------------------------------------------------------------------------
    // The (old, new) material pair of the last change, for the uniform bursts a bulk placement produces: a /fill or
    // a structure writes the SAME transition (air -> glowstone) thousands of times, and every lookup costs a
    // registry id, a cache probe and then either a bit-unpack or the attribute reads behind material(...). The
    // lane's capture sits on every change of the measured path, so two fields of memo are worth it.
    //
    // Only pairs whose materials are position-independent get memoized: a shape-occluding state's material depends
    // on the position asked about, and the cache already records that distinction per state id (DYNAMIC_OPACITY).
    // A memoized pair is exact because a static encoding is a pure function of the state.
    private BlockState memoOldState;
    private BlockState memoNewState;
    private int memoOldPacked;
    private int memoNewPacked;

    /**
     * The packed materials of {@code oldState -> newState}, memoized across calls. Returns true when the memo
     * answered - the only thing the caller needs to know.
     */
    public boolean lookupLightPair(final BlockGetter level, final BlockPos pos, final BlockState oldState,
                                   final BlockState newState, final int[] out) {
        if (oldState == this.memoOldState && newState == this.memoNewState) {
            out[0] = this.memoOldPacked;
            out[1] = this.memoNewPacked;
            return true;
        }

        final int oldPacked = this.lookupLight(level, oldState, pos);
        final int newPacked = this.lookupLight(level, newState, pos);

        out[0] = oldPacked;
        out[1] = newPacked;

        if (this.isStaticMaterial(oldState) && this.isStaticMaterial(newState)) {
            this.memoOldState = oldState;
            this.memoNewState = newState;
            this.memoOldPacked = oldPacked;
            this.memoNewPacked = newPacked;
        }

        return false;
    }

    private boolean isStaticMaterial(final BlockState state) {
        final int cached = (int) INT_ARRAY.getOpaque(this.lightCache, Block.getId(state));

        return cached != UNCACHED && (cached & DYNAMIC_OPACITY) == 0;
    }

    public int lookupLight(BlockGetter level, BlockState state, BlockPos pos) {
        int id = Block.getId(state);
        int cached = (int) INT_ARRAY.getOpaque(lightCache, id);
        if (cached != UNCACHED) {
            return unpackCachedMaterial(level, state, pos, cached);
        }

        int emission = clampLight(state.getLightEmission());
        int staticFlags = staticFlags(state);
        if (state.useShapeForLightOcclusion()) {
            INT_ARRAY.setOpaque(lightCache, id, encodeDynamic(emission, staticFlags));
            return material(level, state, pos, emission, staticFlags);
        }

        int packed = material(level, state, pos, emission, staticFlags);
        INT_ARRAY.setOpaque(lightCache, id, encodeStatic(packed));
        return packed;
    }

    private int unpackCachedMaterial(BlockGetter level, BlockState state, BlockPos pos, int cached) {
        if ((cached & DYNAMIC_OPACITY) != 0) {
            return material(level, state, pos, dynamicEmission(cached), dynamicFlags(cached));
        }
        return decodeStatic(cached);
    }

    private static int material(BlockGetter level, BlockState state, BlockPos pos, int emission, int staticFlags) {
        boolean foliage = (staticFlags & ImageMaterial.FLAG_FOLIAGE) != 0;
        boolean glass = (staticFlags & ImageMaterial.FLAG_GLASS) != 0;
        int opacity = glass ? 0 : clampLight(state.getLightBlock(level, pos));
        if (foliage && opacity == 0) {
            opacity = 1;
        }

        int flags = staticFlags;
        if (glass || state.propagatesSkylightDown(level, pos)) {
            flags |= ImageMaterial.FLAG_SKYLIGHT_DOWN;
        }
        return ImageMaterial.pack(opacity, emission, flags);
    }

    private static int staticFlags(BlockState state) {
        int flags = 0;
        if (state.isAir()) {
            flags |= ImageMaterial.FLAG_AIR;
        }
        if (state.canOcclude()) {
            flags |= ImageMaterial.FLAG_OCCLUDES;
        }
        if (isTransparentGlass(state)) {
            flags |= ImageMaterial.FLAG_GLASS | ImageMaterial.FLAG_SKYLIGHT_DOWN;
        }
        if (state.is(BlockTags.LEAVES)) {
            flags |= ImageMaterial.FLAG_FOLIAGE;
        }
        return flags;
    }

    private static boolean isTransparentGlass(BlockState state) {
        return (state.is(Tags.Blocks.GLASS_BLOCKS) || state.is(Tags.Blocks.GLASS_PANES))
                && !state.is(Tags.Blocks.GLASS_BLOCKS_TINTED);
    }

    private static int encodeStatic(int packed) {
        return (packed & ImageMaterial.MATERIAL_MASK) + 1;
    }

    private static int decodeStatic(int cached) {
        return (cached - 1) & ImageMaterial.MATERIAL_MASK;
    }

    private static int encodeDynamic(int emission, int flags) {
        return DYNAMIC_OPACITY | ENCODED_DYNAMIC_MARKER
                | ((emission & 0xF) << DYNAMIC_EMISSION_SHIFT)
                | ((flags & 0xFF) << DYNAMIC_FLAGS_SHIFT);
    }

    private static int dynamicEmission(int cached) {
        return (cached >>> DYNAMIC_EMISSION_SHIFT) & 0xF;
    }

    private static int dynamicFlags(int cached) {
        return (cached >>> DYNAMIC_FLAGS_SHIFT) & 0xFF;
    }

    private static int clampLight(int light) {
        if (light <= 0) {
            return 0;
        }
        return Math.min(light, ImageConstants.MAX_LIGHT);
    }
}
