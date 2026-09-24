package ca.spottedleaf.starlight.common.light;

import ca.spottedleaf.starlight.common.blockstate.ExtendedAbstractBlockState;
import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.debug.LuxProfiler;
import ca.spottedleaf.starlight.common.util.WorldUtil;
import it.unimi.dsi.fastutil.shorts.ShortCollection;
import it.unimi.dsi.fastutil.shorts.ShortIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.Arrays;
import java.util.Set;

public final class SkyStarLightEngine extends StarLightEngine {

    /*
      Specification for managing the initialisation and de-initialisation of skylight nibble arrays:

      Skylight nibble initialisation requires that non-empty chunk sections have 1 radius nibbles non-null.

      This presents some problems, as vanilla is only guaranteed to have 0 radius neighbours loaded when editing blocks.
      However starlight fixes this so that it has 1 radius loaded. Still, we don't actually have guarantees
      that we have the necessary chunks loaded to de-initialise neighbour sections (but we do have enough to de-initialise
      our own) - we need a radius of 2 to de-initialise neighbour nibbles.
      How do we solve this?

      Each chunk will store the last known "emptiness" of sections for each of their 1 radius neighbour chunk sections.
      If the chunk does not have full data, then its nibbles are NOT de-initialised. This is because obviously the
      chunk did not go through the light stage yet - or its neighbours are not lit. In either case, once the last
      known "emptiness" of neighbouring sections is filled with data, the chunk will run a full check of the data
      to see if any of its nibbles need to be de-initialised.

      The emptiness map allows us to de-initialise neighbour nibbles if the neighbour has it filled with data,
      and if it doesn't have data then we know it will correctly de-initialise once it fills up.

      Unlike vanilla, we store whether nibbles are uninitialised on disk - so we don't need any dumb hacking
      around those.
     */

    protected final int[] heightMapBlockChange = new int[16 * 16];
    {
        Arrays.fill(this.heightMapBlockChange, Integer.MIN_VALUE); // clear heightmap
    }

    protected final boolean[] nullPropagationCheckCache;

    public SkyStarLightEngine(final Level world) {
        super(true, world);
        this.nullPropagationCheckCache = new boolean[WorldUtil.getTotalLightSections(world)];
    }

    @Override
    protected void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.getChunkInCache(chunkX, chunkZ) == null) {
            return;
        }
        SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble == null) {
            if (!initRemovedNibbles) {
                throw new IllegalStateException();
            } else {
                this.setNibbleInCache(chunkX, chunkY, chunkZ, nibble = new SWMRNibbleArray(null, true));
            }
        }
        this.initNibble(nibble, chunkX, chunkY, chunkZ, extrude);
    }

    @Override
    protected void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble != null) {
            nibble.setNull();
        }
    }

    protected final void initNibble(final SWMRNibbleArray currNibble, final int chunkX, final int chunkY, final int chunkZ, final boolean extrude) {
        if (!currNibble.isNullNibbleUpdating()) {
            // already initialised
            return;
        }

        final boolean[] emptinessMap = this.getEmptinessMap(chunkX, chunkZ);

        // are we above this chunk's lowest empty section?
        int lowestY = this.minLightSection - 1;
        for (int currY = this.maxSection; currY >= this.minSection; --currY) {
            if (emptinessMap == null) {
                // cannot delay nibble init for lit chunks, as we need to init to propagate into them.
                final LevelChunkSection current = this.getChunkSection(chunkX, currY, chunkZ);
                if (current == null || current.hasOnlyAir()) {
                    continue;
                }
            } else {
                if (emptinessMap[currY - this.minSection]) {
                    continue;
                }
            }

            // should always be full lit here
            lowestY = currY;
            break;
        }

        if (chunkY > lowestY) {
            // we need to set this one to full
            final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
            nibble.setNonNull();
            nibble.setFull();
            return;
        }

        if (extrude) {
            // this nibble is going to depend solely on the skylight data above it
            // find first non-null data above (there does exist one, as we just found it above)
            for (int currY = chunkY + 1; currY <= this.maxLightSection; ++currY) {
                final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, currY, chunkZ);
                if (nibble != null && !nibble.isNullNibbleUpdating()) {
                    currNibble.setNonNull();
                    currNibble.extrudeLower(nibble);
                    break;
                }
            }
        } else {
            currNibble.setNonNull();
        }
    }

    protected final void rewriteNibbleCacheForSkylight(final ChunkAccess chunk) {
        for (int index = 0, max = this.nibbleCache.length; index < max; ++index) {
            final SWMRNibbleArray nibble = this.nibbleCache[index];
            if (nibble != null && nibble.isNullNibbleUpdating()) {
                // stop propagation in these areas
                this.nibbleCache[index] = null;
                nibble.updateVisible();
            }
        }
    }

    // rets whether neighbours were init'd

    protected final boolean checkNullSection(final int chunkX, final int chunkY, final int chunkZ,
                                             final boolean extrudeInitialised) {
        // null chunk sections may have nibble neighbours in the horizontal 1 radius that are
        // non-null. Propagation to these neighbours is necessary.
        // What makes this easy is we know none of these neighbours are non-empty (otherwise
        // this nibble would be initialised). So, we don't have to initialise
        // the neighbours in the full 1 radius, because there's no worry that any "paths"
        // to the neighbours on this horizontal plane are blocked.
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.nullPropagationCheckCache[chunkY - this.minLightSection]) {
            return false;
        }
        this.nullPropagationCheckCache[chunkY - this.minLightSection] = true;

        // check horizontal neighbours
        boolean needInitNeighbours = false;
        neighbour_search:
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                final SWMRNibbleArray nibble = this.getNibbleFromCache(dx + chunkX, chunkY, dz + chunkZ);
                if (nibble != null && !nibble.isNullNibbleUpdating()) {
                    needInitNeighbours = true;
                    break neighbour_search;
                }
            }
        }

        if (needInitNeighbours) {
            for (int dz = -1; dz <= 1; ++dz) {
                for (int dx = -1; dx <= 1; ++dx) {
                    this.initNibble(dx + chunkX, chunkY, dz + chunkZ, (dx | dz) == 0 ? extrudeInitialised : true, true);
                }
            }
        }

        return needInitNeighbours;
    }

    protected final int getLightLevelExtruded(final int worldX, final int worldY, final int worldZ) {
        final int chunkX = worldX >> 4;
        int chunkY = worldY >> 4;
        final int chunkZ = worldZ >> 4;

        SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble != null) {
            return nibble.getUpdating(worldX, worldY, worldZ);
        }

        for (;;) {
            if (++chunkY > this.maxLightSection) {
                return 15;
            }

            nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);

            if (nibble != null) {
                return nibble.getUpdating(worldX, 0, worldZ);
            }
        }
    }

    @Override
    protected boolean[] getEmptinessMap(final ChunkAccess chunk) {
        return ((ExtendedChunk)chunk).scalablelux$getSkyEmptinessMap();
    }

    @Override
    protected void setEmptinessMap(final ChunkAccess chunk, final boolean[] to) {
        ((ExtendedChunk)chunk).scalablelux$setSkyEmptinessMap(to);
    }

    @Override
    protected SWMRNibbleArray[] getNibblesOnChunk(final ChunkAccess chunk) {
        return ((ExtendedChunk)chunk).scalablelux$getSkyNibbles();
    }

    @Override
    protected void setNibbles(final ChunkAccess chunk, final SWMRNibbleArray[] to) {
        ((ExtendedChunk)chunk).scalablelux$setSkyNibbles(to);
    }

    @Override
    protected boolean canUseChunk(final ChunkAccess chunk) {
        // can only use chunks for sky stuff if their sections have been init'd
        return chunk.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT) && (this.isClientSide || chunk.isLightCorrect());
    }

    @Override
    protected void checkChunkEdges(final LightChunkGetter lightAccess, final ChunkAccess chunk, final int fromSection,
                                   final int toSection) {
        Arrays.fill(this.nullPropagationCheckCache, false);
        this.rewriteNibbleCacheForSkylight(chunk);
        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        for (int y = toSection; y >= fromSection; --y) {
            this.checkNullSection(chunkX, y, chunkZ, true);
        }

        super.checkChunkEdges(lightAccess, chunk, fromSection, toSection);
    }

    @Override
    protected void checkChunkEdges(final LightChunkGetter lightAccess, final ChunkAccess chunk, final ShortCollection sections) {
        Arrays.fill(this.nullPropagationCheckCache, false);
        this.rewriteNibbleCacheForSkylight(chunk);
        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        for (final ShortIterator iterator = sections.iterator(); iterator.hasNext();) {
            final int y = (int)iterator.nextShort();
            this.checkNullSection(chunkX, y, chunkZ, true);
        }

        super.checkChunkEdges(lightAccess, chunk, sections);
    }

    @Override
    protected void checkBlock(final LightChunkGetter lightAccess, final int worldX, final int worldY, final int worldZ) {
        // blocks can change opacity
        // blocks can change direction of propagation

        // same logic applies from BlockStarLightEngine#checkBlock

        final int encodeOffset = this.coordinateOffset;

        final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);

        if (currentLevel == 15) {
            // must re-propagate clobbered source
            this.appendToIncreaseQueue(
                    ((worldX + (worldZ << 6) + (worldY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                            | (currentLevel & 0xFL) << (6 + 6 + 16)
                            | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                            | FLAG_HAS_SIDED_TRANSPARENT_BLOCKS // don't know if the block is conditionally transparent
            );
        } else {
            this.setLightLevel(worldX, worldY, worldZ, 0);
        }

        this.appendToDecreaseQueue(
                ((worldX + (worldZ << 6) + (worldY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                        | (currentLevel & 0xFL) << (6 + 6 + 16)
                        | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
        );
    }

    protected final BlockPos.MutableBlockPos recalcCenterPos = new BlockPos.MutableBlockPos();
    protected final BlockPos.MutableBlockPos recalcNeighbourPos = new BlockPos.MutableBlockPos();

    @Override
    protected int calculateLightValue(final LightChunkGetter lightAccess, final int worldX, final int worldY, final int worldZ,
                                      final int expect) {
        if (expect == 15) {
            return expect;
        }

        final int sectionOffset = this.chunkSectionIndexOffset;
        final BlockState centerState = this.getBlockState(worldX, worldY, worldZ);
        int opacity = ((ExtendedAbstractBlockState)centerState).scalablelux$getOpacityIfCached();

        final BlockState conditionallyOpaqueState;
        if (opacity < 0) {
            this.recalcCenterPos.set(worldX, worldY, worldZ);
            opacity = Math.max(1, centerState.getLightBlock(lightAccess.getLevel(), this.recalcCenterPos));
            if (((ExtendedAbstractBlockState)centerState).scalablelux$isConditionallyFullOpaque()) {
                conditionallyOpaqueState = centerState;
            } else {
                conditionallyOpaqueState = null;
            }
        } else {
            conditionallyOpaqueState = null;
            opacity = Math.max(1, opacity);
        }

        int level = 0;

        for (final AxisDirection direction : AXIS_DIRECTIONS) {
            final int offX = worldX + direction.x;
            final int offY = worldY + direction.y;
            final int offZ = worldZ + direction.z;

            final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;

            final int neighbourLevel = this.getLightLevel(sectionIndex, (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8));

            if ((neighbourLevel - 1) <= level) {
                // don't need to test transparency, we know it wont affect the result.
                continue;
            }

            final BlockState neighbourState = this.getBlockState(offX, offY, offZ);

            if (((ExtendedAbstractBlockState)neighbourState).scalablelux$isConditionallyFullOpaque()) {
                // here the block can be conditionally opaque (i.e light cannot propagate from it), so we need to test that
                // we don't read the blockstate because most of the time this is false, so using the faster
                // known transparency lookup results in a net win
                this.recalcNeighbourPos.set(offX, offY, offZ);
                final VoxelShape neighbourFace = neighbourState.getFaceOcclusionShape(lightAccess.getLevel(), this.recalcNeighbourPos, direction.opposite.nms);
                final VoxelShape thisFace = conditionallyOpaqueState == null ? Shapes.empty() : conditionallyOpaqueState.getFaceOcclusionShape(lightAccess.getLevel(), this.recalcCenterPos, direction.nms);
                if (Shapes.faceShapeOccludes(thisFace, neighbourFace)) {
                    // not allowed to propagate
                    continue;
                }
            }

            final int calculated = neighbourLevel - opacity;
            level = Math.max(calculated, level);
            if (level > expect) {
                return level;
            }
        }

        return level;
    }

    @Override
    protected void propagateBlockChanges(final LightChunkGetter lightAccess, final ChunkAccess atChunk, final Set<BlockPos> positions) {
        this.rewriteNibbleCacheForSkylight(atChunk);
        Arrays.fill(this.nullPropagationCheckCache, false);

        final BlockGetter world = lightAccess.getLevel();
        final int chunkX = atChunk.getPos().x;
        final int chunkZ = atChunk.getPos().z;
        final int heightMapOffset = chunkX * -16 + (chunkZ * (-16 * 16));

        // setup heightmap for changes
        for (final BlockPos pos : positions) {
            final int index = pos.getX() + (pos.getZ() << 4) + heightMapOffset;
            final int curr = this.heightMapBlockChange[index];
            if (pos.getY() > curr) {
                this.heightMapBlockChange[index] = pos.getY();
            }
        }

        // note: light sets are delayed while processing skylight source changes due to how
        // nibbles are initialised, as we want to avoid clobbering nibble values so what when
        // below nibbles are initialised they aren't reading from partially modified nibbles

        // now we can recalculate the sources for the changed columns
        for (int index = 0; index < (16 * 16); ++index) {
            final int maxY = this.heightMapBlockChange[index];
            if (maxY == Integer.MIN_VALUE) {
                // not changed
                continue;
            }
            this.heightMapBlockChange[index] = Integer.MIN_VALUE; // restore default for next caller

            final int columnX = (index & 15) | (chunkX << 4);
            final int columnZ = (index >>> 4) | (chunkZ << 4);

            // try and propagate from the above y
            // delay light set until after processing all sources to setup
            final int maxPropagationY = this.tryPropagateSkylight(world, columnX, maxY, columnZ, true, true);

            // maxPropagationY is now the highest block that could not be propagated to

            // remove all sources below that are 15
            final long propagateDirection = AxisDirection.POSITIVE_Y.everythingButThisDirection;
            final int encodeOffset = this.coordinateOffset;

            if (this.getLightLevelExtruded(columnX, maxPropagationY, columnZ) == 15) {
                // ensure section is checked
                this.checkNullSection(columnX >> 4, maxPropagationY >> 4, columnZ >> 4, true);

                for (int currY = maxPropagationY; currY >= (this.minLightSection << 4); --currY) {
                    if ((currY & 15) == 15) {
                        // ensure section is checked
                        this.checkNullSection(columnX >> 4, (currY >> 4), columnZ >> 4, true);
                    }

                    // ensure section below is always checked
                    final SWMRNibbleArray nibble = this.getNibbleFromCache(columnX >> 4, currY >> 4, columnZ >> 4);
                    if (nibble == null) {
                        // advance currY to the the top of the section below
                        currY = (currY) & (~15);
                        // note: this value ^ is actually 1 above the top, but the loop decrements by 1 so we actually
                        // end up there
                        continue;
                    }

                    if (nibble.getUpdating(columnX, currY, columnZ) != 15) {
                        break;
                    }

                    // delay light set until after processing all sources to setup
                    this.appendToDecreaseQueue(
                            ((columnX + (columnZ << 6) + (currY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                    | (15L << (6 + 6 + 16))
                                    | (propagateDirection << (6 + 6 + 16 + 4))
                                    // do not set transparent blocks for the same reason we don't in the checkBlock method
                    );
                }
            }
        }

        // delayed light sets are processed here, and must be processed before checkBlock as checkBlock reads
        // immediate light value
        this.processDelayedIncreases();
        this.processDelayedDecreases();

        for (final BlockPos pos : positions) {
            this.checkBlock(lightAccess, pos.getX(), pos.getY(), pos.getZ());
        }
        this.performLightDecrease(lightAccess);
    }

    /**
     * The sky half of the seeding step, for callers that own the caches and the drain (see
     * {@link StarLightEngine#seedChanges}). The delayed light sets must be processed before the per-position checks,
     * because a check reads the immediate light value — the base's per-chunk routine does the same thing.
     */
    @Override
    public void seedChanges(final LightChunkGetter lightAccess, final Set<BlockPos> positions) {
        this.processDelayedIncreases();
        this.processDelayedDecreases();
        super.seedChanges(lightAccess, positions);
    }

    /** The packed form of the same seeding (see {@link StarLightEngine#seedChanges}). */
    @Override
    public void seedChanges(final LightChunkGetter lightAccess, final long[] packed, final int count) {
        this.processDelayedIncreases();
        this.processDelayedDecreases();
        super.seedChanges(lightAccess, packed, count);
    }

    protected final int[] heightMapGen = new int[32 * 32];

    @Override
    protected void lightChunk(final LightChunkGetter lightAccess, final ChunkAccess chunk, final boolean needsEdgeChecks) {
        this.rewriteNibbleCacheForSkylight(chunk);
        Arrays.fill(this.nullPropagationCheckCache, false);

        final BlockGetter world = lightAccess.getLevel();
        final ChunkPos chunkPos = chunk.getPos();
        final int chunkX = chunkPos.x;
        final int chunkZ = chunkPos.z;

        final LevelChunkSection[] sections = chunk.getSections();

        int highestNonEmptySection = this.maxSection;
        while (highestNonEmptySection == (this.minSection - 1) ||
                sections[highestNonEmptySection - this.minSection] == null || sections[highestNonEmptySection - this.minSection].hasOnlyAir()) {
            this.checkNullSection(chunkX, highestNonEmptySection, chunkZ, false);
            // try propagate FULL to neighbours

            // check neighbours to see if we need to propagate into them
            for (final AxisDirection direction : ONLY_HORIZONTAL_DIRECTIONS) {
                final int neighbourX = chunkX + direction.x;
                final int neighbourZ = chunkZ + direction.z;
                final SWMRNibbleArray neighbourNibble = this.getNibbleFromCache(neighbourX, highestNonEmptySection, neighbourZ);
                if (neighbourNibble == null) {
                    // unloaded neighbour
                    // most of the time we fall here
                    continue;
                }

                // it looks like we need to propagate into the neighbour

                final int incX;
                final int incZ;
                final int startX;
                final int startZ;

                if (direction.x != 0) {
                    // x direction
                    incX = 0;
                    incZ = 1;

                    if (direction.x < 0) {
                        // negative
                        startX = chunkX << 4;
                    } else {
                        startX = chunkX << 4 | 15;
                    }
                    startZ = chunkZ << 4;
                } else {
                    // z direction
                    incX = 1;
                    incZ = 0;

                    if (direction.z < 0) {
                        // negative
                        startZ = chunkZ << 4;
                    } else {
                        startZ = chunkZ << 4 | 15;
                    }
                    startX = chunkX << 4;
                }

                final int encodeOffset = this.coordinateOffset;
                final long propagateDirection = 1L << direction.ordinal(); // we only want to check in this direction

                for (int currY = highestNonEmptySection << 4, maxY = currY | 15; currY <= maxY; ++currY) {
                    for (int i = 0, currX = startX, currZ = startZ; i < 16; ++i, currX += incX, currZ += incZ) {
                        this.appendToIncreaseQueue(
                                ((currX + (currZ << 6) + (currY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                        | (15L << (6 + 6 + 16)) // we know we're at full lit here
                                        | (propagateDirection << (6 + 6 + 16 + 4))
                                        // no transparent flag, we know for a fact there are no blocks here that could be directionally transparent (as the section is EMPTY)
                        );
                    }
                }
            }

            if (highestNonEmptySection-- == (this.minSection - 1)) {
                break;
            }
        }

        if (highestNonEmptySection >= this.minSection) {
            // fill out our other sources
            final int minX = chunkPos.x << 4;
            final int maxX = chunkPos.x << 4 | 15;
            final int minZ = chunkPos.z << 4;
            final int maxZ = chunkPos.z << 4 | 15;
            final int startY = highestNonEmptySection << 4 | 15;
            for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                for (int currX = minX; currX <= maxX; ++currX) {
                    this.tryPropagateSkylight(world, currX, startY + 1, currZ, false, false);
                }
            }
        } // else: apparently the chunk is empty

        if (needsEdgeChecks) {
            // not required to propagate here, but this will reduce the hit of the edge checks
            this.performLightIncrease(lightAccess);

            for (int y = highestNonEmptySection; y >= this.minLightSection; --y) {
                this.checkNullSection(chunkX, y, chunkZ, false);
            }
            // no need to rewrite the nibble cache again
            super.checkChunkEdges(lightAccess, chunk, this.minLightSection, highestNonEmptySection);
        } else {
            for (int y = highestNonEmptySection; y >= this.minLightSection; --y) {
                this.checkNullSection(chunkX, y, chunkZ, false);
            }
            this.propagateNeighbourLevels(lightAccess, chunk, this.minLightSection, highestNonEmptySection);

            this.performLightIncrease(lightAccess);
        }
    }

    protected final void processDelayedIncreases() {
        // copied from performLightIncrease
        final long[] queue = this.increaseQueue;
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;

        for (int i = 0, len = this.increaseQueueInitialLength; i < len; ++i) {
            final long queueValue = queue[i];

            final int posX = ((int)queueValue & 63) + decodeOffsetX;
            final int posZ = (((int)queueValue >>> 6) & 63) + decodeOffsetZ;
            final int posY = (((int)queueValue >>> 12) & ((1 << 16) - 1)) + decodeOffsetY;
            final int propagatedLightLevel = (int)((queueValue >>> (6 + 6 + 16)) & 0xF);

            this.setLightLevel(posX, posY, posZ, propagatedLightLevel);
        }
    }

    protected final void processDelayedDecreases() {
        // copied from performLightDecrease
        final long[] queue = this.decreaseQueue;
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;

        for (int i = 0, len = this.decreaseQueueInitialLength; i < len; ++i) {
            final long queueValue = queue[i];

            final int posX = ((int)queueValue & 63) + decodeOffsetX;
            final int posZ = (((int)queueValue >>> 6) & 63) + decodeOffsetZ;
            final int posY = (((int)queueValue >>> 12) & ((1 << 16) - 1)) + decodeOffsetY;

            this.setLightLevel(posX, posY, posZ, 0);
        }
    }

    // delaying the light set is useful for block changes since they need to worry about initialising nibblearrays
    // while also queueing light at the same time (initialising nibblearrays might depend on nibbles above, so
    // clobbering the light values will result in broken propagation)
    protected final int tryPropagateSkylight(final BlockGetter world, final int worldX, int startY, final int worldZ,
                                             final boolean extrudeInitialised, final boolean delayLightSet) {
        final BlockPos.MutableBlockPos mutablePos = this.mutablePos3;
        final int encodeOffset = this.coordinateOffset;
        final long propagateDirection = AxisDirection.POSITIVE_Y.everythingButThisDirection; // just don't check upwards.

        if (this.getLightLevelExtruded(worldX, startY + 1, worldZ) != 15) {
            return startY;
        }

        // ensure this section is always checked
        this.checkNullSection(worldX >> 4, startY >> 4, worldZ >> 4, extrudeInitialised);

        BlockState above = this.getBlockState(worldX, startY + 1, worldZ);

        for (;startY >= (this.minLightSection << 4); --startY) {
            if (LuxProfiler.enabled()) { LuxProfiler.skyColumnCells++; }
            if ((startY & 15) == 15) {
                // ensure this section is always checked
                this.checkNullSection(worldX >> 4, startY >> 4, worldZ >> 4, extrudeInitialised);
            }
            final BlockState current = this.getBlockState(worldX, startY, worldZ);

            final VoxelShape fromShape;
            if (((ExtendedAbstractBlockState)above).scalablelux$isConditionallyFullOpaque()) {
                this.mutablePos2.set(worldX, startY + 1, worldZ);
                fromShape = above.getFaceOcclusionShape(world, this.mutablePos2, AxisDirection.NEGATIVE_Y.nms);
                if (Shapes.faceShapeOccludes(Shapes.empty(), fromShape)) {
                    // above wont let us propagate
                    break;
                }
            } else {
                fromShape = Shapes.empty();
            }

            final int opacityIfCached = ((ExtendedAbstractBlockState)current).scalablelux$getOpacityIfCached();
            // does light propagate from the top down?
            if (opacityIfCached != -1) {
                if (opacityIfCached != 0) {
                    // we cannot propagate 15 through this
                    break;
                }
                // most of the time it falls here.
                // add to propagate
                // light set delayed until we determine if this nibble section is null
                this.appendToIncreaseQueue(
                        ((worldX + (worldZ << 6) + (startY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                | (15L << (6 + 6 + 16)) // we know we're at full lit here
                                | (propagateDirection << (6 + 6 + 16 + 4))
                );
            } else {
                mutablePos.set(worldX, startY, worldZ);
                long flags = 0L;
                if (((ExtendedAbstractBlockState)current).scalablelux$isConditionallyFullOpaque()) {
                    final VoxelShape cullingFace = current.getFaceOcclusionShape(world, mutablePos, AxisDirection.POSITIVE_Y.nms);

                    if (Shapes.faceShapeOccludes(fromShape, cullingFace)) {
                        // can't propagate here, we're done on this column.
                        break;
                    }
                    flags |= FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
                }

                final int opacity = current.getLightBlock(world, mutablePos);
                if (opacity > 0) {
                    // let the queued value (if any) handle it from here.
                    break;
                }

                // light set delayed until we determine if this nibble section is null
                this.appendToIncreaseQueue(
                        ((worldX + (worldZ << 6) + (startY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                | (15L << (6 + 6 + 16)) // we know we're at full lit here
                                | (propagateDirection << (6 + 6 + 16 + 4))
                                | flags
                );
            }

            above = current;

            if (this.getNibbleFromCache(worldX >> 4, startY >> 4, worldZ >> 4) == null) {
                // we skip empty sections here, as this is just an easy way of making sure the above block
                // can propagate through air.

                // nothing can propagate in null sections, remove the queue entry for it
                --this.increaseQueueInitialLength;

                // advance currY to the the top of the section below
                startY = (startY) & (~15);
                // note: this value ^ is actually 1 above the top, but the loop decrements by 1 so we actually
                // end up there

                // make sure this is marked as AIR
                above = AIR_BLOCK_STATE;
            } else if (!delayLightSet) {
                this.setLightLevel(worldX, startY, worldZ, 15);
            }
        }

        return startY;
    }

    // ----------------------------------------------------------------------------------------------------------------
    /** Scratch buffers for the recompute - the pooled engine instances are thread-confined, so these are fields.
     *  One byte per cell over the whole column: light, material, and the previous light for the raise-only install. */
    private byte[] recomputeScratch;
    private byte[] recomputeMaterialScratch;
    private byte[] recomputeBeforeScratch;
    /** Run bottoms of the chunk plus a one-block halo, indexed (z+1)*18 + (x+1). */
    private int[] recomputeRuns = new int[18 * 18];
    private int[] recomputeQueue;

    // The whole-chunk form of the recompute (recomputeChunkSkyLight) was deleted here: it is superseded by
    // settleSkyWindow below, which runs the same rule over only the y window an edit can reach. The closed attempt -
    // why an array-based rule was necessary, what it cost, and the two halo variants - is recorded in
    // docs/NEW-ENGINE-TEARDOWN.md sections 20-28.

    private byte[] recomputeCells(final int cells) {
        byte[] scratch = this.recomputeScratch;

        if (scratch == null || scratch.length < cells) {
            scratch = this.recomputeScratch = new byte[cells];
        } else {
            Arrays.fill(scratch, 0, cells, (byte) 0);
        }
        return scratch;
    }

    private byte[] recomputeBeforeCells(final int cells) {
        byte[] scratch = this.recomputeBeforeScratch;

        if (scratch == null || scratch.length < cells) {
            scratch = this.recomputeBeforeScratch = new byte[cells];
        }
        return scratch;
    }

    private byte[] recomputeMaterialCells(final int cells) {
        byte[] scratch = this.recomputeMaterialScratch;

        if (scratch == null || scratch.length < cells) {
            scratch = this.recomputeMaterialScratch = new byte[cells];
        } else {
            Arrays.fill(scratch, 0, cells, (byte) 0);
        }
        return scratch;
    }

    private int[] recomputeQueueCells(final int cells) {
        int[] queue = this.recomputeQueue;

        if (queue == null || queue.length < cells * 2) {
            queue = this.recomputeQueue = new int[cells * 2];
        }
        return queue;
    }
    /** One diagnostic line, prefixed so it can be grepped out of a run log. */
    private void logRecomputeDebug(final String message) {
        System.out.println("SKYRECOMPUTE-DEBUG " + message);
    }

    /**
     * The windowed settle: recompute the skylight of one chunk over the <b>y window the edit can reach</b>, instead of
     * over the whole chunk (docs/NEW-ENGINE-TEARDOWN.md sections 26-28).
     *
     * <p><b>Why a window is exact.</b> A cell's light is a level 0..15, and every propagation step costs at least one
     * level, so a change can move the light of a cell at most 15 steps away from where it happened. The window is
     * therefore [lowest change - 16, highest change + 16], clipped to the world: everything outside it cannot have
     * changed, and the cells at its edge are read as sources from the light that is already there.</p>
     *
     * <p><b>What the timers forced.</b> The whole-chunk version measured expand 4.3 ms + install 3.9 ms against a shell
     * of 34 us and a BFS of 98 us - the tight core was already at 1.x's per-pop price, and the cost was two full passes
     * over 98,304 cells. Restricting both passes to the window cuts them by the ratio of window height to world height
     * (~48 of 384 levels here), which is what this method is for.</p>
     */
    public final void settleSkyWindow(final LightChunkGetter lightAccess, final ChunkAccess chunk,
                                      final int changedMinY, final int changedMaxY) {
        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        final int worldX0 = chunkX << 4;
        final int worldZ0 = chunkZ << 4;
        final int worldMinY = WorldUtil.getMinBlockY(this.world);
        final int worldMaxY = WorldUtil.getMaxBlockY(this.world);
        final int yLo = Math.max(worldMinY, changedMinY - 16);
        final int yHi = Math.min(worldMaxY, changedMaxY + 16);
        final int height = yHi - yLo + 1;
        final int cells = 256 * height;
        final int minSection = this.minSection;
        final int maxSection = this.maxSection;

        final byte[] light = this.recomputeCells(cells);
        final byte[] material = this.recomputeMaterialCells(cells);
        final byte[] before = this.recomputeBeforeCells(cells);
        final int[] queue = this.recomputeQueueCells(cells);
        final int[] runs = this.recomputeRuns; // 18x18: the chunk plus a one-block halo, indexed (z+1)*18 + (x+1)
        final long tStart = System.nanoTime();

        // ---- 1) expand the window, column-major: index = col * height + (y - yLo)
        for (int section = minSection; section <= maxSection; section++) {
            final int sectionY0 = section * 16;

            if (sectionY0 + 15 < yLo || sectionY0 > yHi) {
                continue; // this section does not intersect the window
            }
            final int slot = chunkX + 5 * chunkZ + (5 * 5) * section + this.chunkSectionIndexOffset;
            final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, section, chunkZ);
            final byte[] packed = nibble == null ? null : nibble.storageUpdating;
            final int lyFrom = Math.max(0, yLo - sectionY0);
            final int lyTo = Math.min(15, yHi - sectionY0);

            for (int col = 0; col < 256; col++) {
                final int x = col & 15;
                final int z = col >> 4;
                final int outBase = col * height - yLo;

                for (int ly = lyFrom; ly <= lyTo; ly++) {
                    final int local = (ly << 8) | (z << 4) | x;

                    material[outBase + sectionY0 + ly] = (byte) this.opacityOf(slot, local);
                    if (packed != null) {
                        final int b = packed[local >> 1] & 0xFF;

                        light[outBase + sectionY0 + ly] =
                                (byte) (((local & 1) == 0 ? b : (b >>> 4)) & 0x0F);
                    }
                }
            }
        }
        System.arraycopy(light, 0, before, 0, cells);
        final long tExpand = System.nanoTime();

        // ---- 1b) run bottoms for the one-block halo, computed ONCE per settle. The shell asks for a neighbour's run
        // bottom for every column and every direction; doing that per lookup would be 1024 heightmap-guided searches a
        // settle instead of the 64 different columns that actually exist.
        for (int z = -1; z <= 16; z++) {
            for (int x = -1; x <= 16; x++) {
                if (x >= 0 && x <= 15 && z >= 0 && z <= 15) {
                    continue;
                }
                runs[(z + 1) * 18 + (x + 1)] = this.runBottomFromLight(worldX0 + x, worldZ0 + z, worldMinY, worldMaxY);
            }
        }
        final long tHalo = System.nanoTime();

        // ---- 2) sweep: the 15-runs, from the world top down (the window's top edge may sit under a ceiling, so the run
        // bottom has to be found from the material, not assumed to be the window top)
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int col = (z << 4) | x;
                final int windowBase = col * height;
                int runBottom = Integer.MIN_VALUE;

                // The part above the window is UNCHANGED, and 15 requires the cell above to be 15 - so ONE light read at
                // the window's top edge decides whether this column has a run inside the window at all. The earlier
                // version walked the palette from the world top for every column (~250 reads x 256 columns), which was
                // the settle's largest single cost.
                if (this.getLightLevel(worldX0 + x, yHi + 1 > worldMaxY ? worldMaxY : yHi + 1, worldZ0 + z) == 15) {
                    int y = yHi;

                    while (y >= yLo) {
                        final int i = windowBase + (y - yLo);

                        if (material[i] != 0) {
                            break;
                        }
                        light[i] = 15;
                        y--;
                    }
                    runBottom = y + 1 > yHi ? Integer.MIN_VALUE : y + 1;
                }
                runs[(z + 1) * 18 + (x + 1)] = runBottom;
            }
        }
        final long tSweep = System.nanoTime();

        // ---- 3) shell + 4) BFS, inside the window, exactly as the whole-chunk version does
        int tail = 0;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int col = (z << 4) | x;
                final int base = col * height;
                final int runBottom = runs[(z + 1) * 18 + (x + 1)];

                if (runBottom == Integer.MIN_VALUE) {
                    continue;
                }
                // Exact neighbour runs from the cached table (filled above): a neighbour column whose run ends higher - a
                // wall, an overhang, the side of a cube - is dark at heights where this one is lit, and those cells have to
                // be seeded or the light never spills sideways. The first version tested only "is the neighbour lit at MY
                // run bottom", which misses exactly that case.
                final int lowestNeighbour = Math.min(
                        Math.min(runs[(z + 1) * 18 + x], runs[(z + 1) * 18 + (x + 2)]),
                        Math.min(runs[z * 18 + (x + 1)], runs[(z + 2) * 18 + (x + 1)]));

                queue[tail++] = base + (runBottom - yLo);
                for (int y = runBottom + 1; y <= lowestNeighbour && y <= yHi; y++) {
                    final int i = base + (y - yLo);

                    if ((light[i] & 0xFF) == 15) {
                        queue[tail++] = i;
                    }
                }
            }
        }
        int head = 0;

        while (head < tail) {
            final int index = queue[head++];
            final int level = light[index] & 0xFF;

            if (level <= 1) {
                continue;
            }
            final int col = index / height;
            final int yOff = index - col * height;

            for (int dir = 0; dir < 5; dir++) {
                final int nIndex;

                if (dir == 4) {
                    if (yOff == 0) {
                        continue;
                    }
                    nIndex = index - 1;
                } else {
                    final int nCol = col + (dir == 0 ? -1 : dir == 1 ? 1 : dir == 2 ? -16 : 16);

                    if (nCol < 0 || nCol > 255) {
                        continue;
                    }
                    if (dir <= 1 && ((nCol & 15) != (col & 15) + (dir == 0 ? -1 : 1))) {
                        continue; // crossed the chunk edge horizontally
                    }
                    nIndex = nCol * height + yOff;
                }
                final int opacity = material[nIndex] & 0xFF;

                if (opacity == ExtendedChunk.MATERIAL_UNCACHED) {
                    continue;
                }
                final int target = level - Math.max(1, opacity);

                if (target > (light[nIndex] & 0xFF)) {
                    light[nIndex] = (byte) target;
                    if (target > 1) {
                        queue[tail++] = nIndex;
                    }
                }
            }
        }
        final long tBfs = System.nanoTime();

        // ---- 5) install the differences, and push the chunk's boundary changes for the neighbouring chunks
        final long propagateDirection = AxisDirection.POSITIVE_Y.everythingButThisDirection;

        for (int section = minSection; section <= maxSection; section++) {
            final int sectionY0 = section * 16;

            if (sectionY0 + 15 < yLo || sectionY0 > yHi) {
                continue;
            }
            final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, section, chunkZ);
            final byte[] packed = nibble == null ? null : nibble.storageUpdating;
            final int lyFrom = Math.max(0, yLo - sectionY0);
            final int lyTo = Math.min(15, yHi - sectionY0);

            for (int col = 0; col < 256; col++) {
                final int x = col & 15;
                final int z = col >> 4;
                final int windowBase = col * height - yLo;
                final boolean boundary = x == 0 || x == 15 || z == 0 || z == 15;
                final int wx = worldX0 + x;
                final int wz = worldZ0 + z;

                for (int ly = lyFrom; ly <= lyTo; ly++) {
                    final int y = sectionY0 + ly;
                    final int i = windowBase + y;
                    final int target = light[i] & 0xFF;
                    final int old = before[i] & 0xFF;

                    if (target == old) {
                        continue;
                    }
                    final int local = (ly << 8) | (z << 4) | x;

                    if (packed != null) {
                        final int half = local >> 1;
                        final int b = packed[half] & 0xFF;

                        packed[half] = (byte) ((local & 1) == 0
                                ? ((b & 0xF0) | (target & 0x0F))
                                : ((b & 0x0F) | ((target & 0x0F) << 4)));
                    }
                    if (nibble != null) {
                        nibble.updatingDirty = true;
                    }
                    if (!boundary) {
                        continue;
                    }
                    final long encoded = ((wx + (wz << 6) + (y << (6 + 6)) + this.coordinateOffset)
                            & ((1L << (6 + 6 + 16)) - 1)) | (propagateDirection << (6 + 6 + 16 + 4));

                    if (target > old) {
                        this.appendToIncreaseQueue(encoded | ((long) target << (6 + 6 + 16)));
                    } else {
                        this.appendToDecreaseQueue(encoded | ((long) old << (6 + 6 + 16)));
                    }
                }
            }
        }
        if (Boolean.getBoolean("scalablelux.recomputeDebug")) {
            final long tEnd = System.nanoTime();

            this.logRecomputeDebug("WINDOW yLo=" + yLo + " yHi=" + yHi
                    + " expandUs=" + (tExpand - tStart) / 1000
                    + " sweepUs=" + (tSweep - tExpand) / 1000
                    + " seeds=" + tail
                    + " bfsUs=" + (tBfs - tSweep) / 1000
                    + " installUs=" + (tEnd - tBfs) / 1000
                    + " totalUs=" + (tEnd - tStart) / 1000);
        }
    }

    /** Opacity of a cell in the world (used above the window, where no buffer exists). */
    private int opacityOfWorld(final int wx, final int wy, final int wz) {
        final int slot = (wx >> 4) + 5 * (wz >> 4) + (5 * 5) * (wy >> 4) + this.chunkSectionIndexOffset;

        return this.opacityOf(slot, ((wy & 15) << 8) | ((wz & 15) << 4) | (wx & 15));
    }

    /**
     * The lowest lit cell of a column, read from the light that is already there.
     *
     * <p>Exact, and cheap because the search starts where the column's surface is: the heightmap gives a y that is
     * normally inside the lit run, so the walk is a few steps down to the run's bottom (and, for a column that is dark
     * there, a bounded walk up to find the top of its run first). The naive version scanned from the world top for every
     * column - ~250 reads each, which was 1 ms of the settle.</p>
     */
    private int runBottomFromLight(final int wx, final int wz, final int minY, final int maxY) {
        int y = Math.max(minY, Math.min(maxY, this.world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, wx, wz)));

        if (this.getLightLevel(wx, y, wz) != 15) {
            // not inside a lit run: walk up to the top of one (bounded by the world), or report none
            while (y <= maxY && this.getLightLevel(wx, y, wz) != 15) {
                y++;
            }
            if (y > maxY) {
                return Integer.MIN_VALUE;
            }
        }
        while (y > minY && this.getLightLevel(wx, y - 1, wz) == 15) {
            y--;
        }
        return y;
    }

    /** Opacity of one cell, straight from the section's palette (used when no material table is enabled). */
    private int opacityOf(final int slot, final int local) {
        final BlockState state = this.getBlockState(slot, local);

        if (state == null) {
            return ExtendedChunk.MATERIAL_UNCACHED;
        }
        final int opacity = ((ExtendedAbstractBlockState) state).scalablelux$getOpacityIfCached();

        return opacity >= 0 ? opacity : ExtendedChunk.MATERIAL_UNCACHED;
    }
}
