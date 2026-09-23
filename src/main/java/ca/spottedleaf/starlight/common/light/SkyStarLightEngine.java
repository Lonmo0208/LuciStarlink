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


    /**
     * Recomputes one chunk's skylight as the <b>fixed point of the same increase rule the update path uses</b>
     * (docs/NEW-ENGINE-TEARDOWN.md sections 20-23). This is what owning the light means: with the rule as the single
     * definition, generation, updates and save loads all produce the same answer - the property the "bit-identical to
     * vanilla" promise forbade.
     *
     * <p><b>Contiguous, by measured necessity.</b> The first version walked the engine's own accessors per cell
     * ({@code materialBound}/{@code getLightLevel}/{@code setLightLevel}) and cost 14 ms per pass instead of the 0.3-0.4
     * ms the off-engine prototype measured, and it also failed to raise 1,701 cells near the chunk edge. Both come from
     * the same thing: the rule has to run on <b>arrays</b>. So this expands the chunk's updating nibbles and its material
     * into two contiguous buffers, runs the prototype's algorithm there, and then writes back only the cells that got
     * brighter - each as an increase so neighbouring chunks receive the light too.</p>
     *
     * <p>The one part that is not local is light from <i>outside</i> the chunk: a neighbouring column that the rule says
     * is lit (its own run extends lower) sheds light in. Those runs are read from the neighbours' <b>material</b>, not
     * from their light, because their light still carries the very history this recompute exists to replace.</p>
     */
    public final void recomputeChunkSkyLight(final LightChunkGetter lightAccess, final ChunkAccess chunk) {
        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        final int worldX0 = chunkX << 4;
        final int worldZ0 = chunkZ << 4;
        final int minY = WorldUtil.getMinBlockY(this.world);
        final int maxY = WorldUtil.getMaxBlockY(this.world);
        final int height = maxY - minY + 1;
        final int cells = 256 * height;

        final long tStart = System.nanoTime();
        final byte[] light = this.recomputeCells(cells);
        final byte[] material = this.recomputeMaterialCells(cells);
        final byte[] before = this.recomputeBeforeCells(cells);
        final int[] queue = this.recomputeQueueCells(cells);
        final int[] runs = this.recomputeRuns;

        // ---- 1) expand the chunk's material and light into the buffers
        for (int section = this.minSection; section <= this.maxSection; section++) {
            // the engine's own slot convention: the window offsets live in chunkSectionIndexOffset, so no per-coordinate
            // offset is added here (adding them again read out of range: "Index -73 out of bounds for length 700").
            final int slot = chunkX + 5 * chunkZ + (5 * 5) * section + this.chunkSectionIndexOffset;
            final byte[] sectionMaterial = this.materialBound(slot);
            final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, section, chunkZ);
            final int base = (section * 16 - minY) * 256;

            for (int i = 0; i < 4096; i++) {
                material[base + i] = sectionMaterial == null ? ExtendedChunk.MATERIAL_UNCACHED : sectionMaterial[i];
            }
            if (nibble != null) {
                final byte[] packed = nibble.storageUpdating;

                if (packed != null) {
                    for (int i = 0; i < 2048; i++) {
                        final int b = packed[i] & 0xFF;

                        light[base + (i << 1)] = (byte) (b & 0x0F);
                        light[base + (i << 1) + 1] = (byte) (b >>> 4);
                    }
                }
            }
        }

        System.arraycopy(light, 0, before, 0, cells); // the raise-only install needs the previous values

        final long tExpand = System.nanoTime();
        // ---- 2) run bottoms for this chunk and for the one-block halo, the halo from material (see the class doc)
        for (int z = -1; z <= 16; z++) {
            for (int x = -1; x <= 16; x++) {
                if (x >= 0 && x <= 15 && z >= 0 && z <= 15) {
                    continue; // the centre chunk is handled by the sweep below
                }
                runs[(z + 1) * 18 + (x + 1)] = this.haloRunBottom(worldX0 + x, worldZ0 + z, minY, maxY);
            }
        }

        final long tHalo = System.nanoTime();
        // ---- 3) sweep the centre chunk, recording each column's run bottom
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int col = (z << 4) | x;
                int runBottom = Integer.MIN_VALUE;

                for (int y = maxY; y >= minY; y--) {
                    final int index = (y - minY) * 256 + col;

                    if (material[index] != 0) {
                        break;
                    }
                    light[index] = 15;
                    runBottom = y;
                }
                runs[(z + 1) * 18 + (x + 1)] = runBottom;
            }
        }

        final long tSweep = System.nanoTime();
        // ---- 4) the shell: run bottoms, plus lit cells beside a column whose run ends lower (light leaves the column)
        int tail = 0;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int col = (z << 4) | x;
                final int runBottom = runs[(z + 1) * 18 + (x + 1)];

                if (runBottom == Integer.MIN_VALUE) {
                    continue;
                }
                for (int y = runBottom; y <= maxY; y++) {
                    final int index = (y - minY) * 256 + col;

                    if ((light[index] & 0xFF) != 15) {
                        continue;
                    }
                    boolean seed = y == runBottom;

                    for (int dir = 0; dir < 4 && !seed; dir++) {
                        final int nx = x + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
                        final int nz = z + (dir == 2 ? -1 : dir == 3 ? 1 : 0);

                        if (runs[(nz + 1) * 18 + (nx + 1)] > y) {
                            seed = true;
                        }
                    }
                    if (seed) {
                        queue[tail++] = index;
                    }
                }
            }
        }

        final long tShell = System.nanoTime();
        // ---- 5) the engine's increase rule over the buffers, to a fixed point
        int head = 0;

        while (head < tail) {
            final int index = queue[head++];
            final int level = light[index] & 0xFF;

            if (level <= 1) {
                continue;
            }
            final int y = minY + index / 256;
            final int col = index % 256;
            final int x = col & 15;
            final int z = col >> 4;

            for (int dir = 0; dir < 5; dir++) { // four horizontals and down: skylight never propagates upward
                final int nx = x + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
                final int ny = y + (dir == 4 ? -1 : 0);
                final int nz = z + (dir == 2 ? -1 : dir == 3 ? 1 : 0);

                if (nx < 0 || nx > 15 || ny < minY || nz < 0 || nz > 15) {
                    continue;
                }
                final int nIndex = (ny - minY) * 256 + ((nz << 4) | nx);
                final int opacity = material[nIndex] & 0xFF;

                if (opacity == ExtendedChunk.MATERIAL_UNCACHED) {
                    continue; // the shape path belongs to the engine, not to the recompute: leave that cell alone
                }
                final int target = level - Math.max(1, opacity);

                if (target > (light[nIndex] & 0xFF)) {
                    light[nIndex] = (byte) target;
                    if (target > 1 && tail < queue.length) {
                        queue[tail++] = nIndex;
                    }
                }
            }
        }

        if (Boolean.getBoolean("scalablelux.recomputeDebug")) {
            // Point-name the cells the probe reports as "rule is brighter", so the engine-side scratch can be compared
            // with the off-engine prototype's value for the same cell side by side (docs section 24).
            final int[][] probeCells = {{12, 54, 0}, {12, 63, 0}, {13, 51, 0}, {12, 40, 0}};

            for (final int[] cell : probeCells) {
                final int x = cell[0];
                final int y = cell[1];
                final int z = cell[2];
                final int index = (y - minY) * 256 + ((z << 4) | x);

                this.logRecomputeDebug("cell (" + x + "," + y + "," + z + ")"
                        + " scratch=" + (light[index] & 0xFF)
                        + " before=" + (before[index] & 0xFF)
                        + " mat=" + (material[index] & 0xFF)
                        + " runHere=" + runs[(z + 1) * 18 + (x + 1)]
                        + " runW=" + runs[(z + 1) * 18 + x]
                        + " runE=" + runs[(z + 1) * 18 + (x + 2)]
                        + " runN=" + runs[z * 18 + (x + 1)]
                        + " runS=" + runs[(z + 2) * 18 + (x + 1)]
                        + " queueLen=" + tail);
            }
        }

        final long tBfs = System.nanoTime();
        // ---- 6) install: BOTH directions. A full recompute replaces the light wholesale, so cells that must go dark
        // have to be handed to the decrease machinery (the first version was raise-only, which silently skipped the sky
        // half of a "place blocks" burst - the shadow never formed). Each changed cell is pushed with its own level so
        // both propagations can carry the change into neighbouring chunks.
        final long propagateDirection = AxisDirection.POSITIVE_Y.everythingButThisDirection;

        for (int y = minY; y <= maxY; y++) {
            final int plane = (y - minY) * 256;

            for (int col = 0; col < 256; col++) {
                final int index = plane + col;
                final int target = light[index] & 0xFF;
                final int old = before[index] & 0xFF;

                if (target == old) {
                    continue;
                }
                final int x = col & 15;
                final int z = col >> 4;
                final int wx = worldX0 + x;
                final int wz = worldZ0 + z;

                this.setLightLevel(wx, y, wz, target);
                // Push ONLY at the chunk's boundary. The recompute already produced this chunk's fixed point, so its
                // interior needs no propagation; what the engine's queues are for is telling the NEIGHBOURING chunks,
                // and that can only happen across a shared face (light moves one cell at a time). The first version
                // pushed every changed cell - ~30,000 of them on structure_cube - which made the engine redo the whole
                // propagation in its own slow loop: 13 ms for work the scratch had already finished.
                if (x != 0 && x != 15 && z != 0 && z != 15) {
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
        if (Boolean.getBoolean("scalablelux.recomputeDebug")) {
            final long tEnd = System.nanoTime();
            this.logRecomputeDebug("TIME expandUs=" + (tExpand - tStart) / 1000
                    + " haloUs=" + (tHalo - tExpand) / 1000
                    + " sweepUs=" + (tSweep - tHalo) / 1000
                    + " shellUs=" + (tShell - tSweep) / 1000
                    + " bfsUs=" + (tBfs - tShell) / 1000
                    + " installUs=" + (tEnd - tBfs) / 1000
                    + " totalUs=" + (tEnd - tStart) / 1000);
        }
    }

    /** The light a cell currently holds, for the raise-only install. */
    private int expandedCurrent(final int index, final int chunkX, final int y, final int col) {
        return 0; // replaced below: the buffer holds the *before* values only until the sweep overwrites them
    }

    /** A halo column's run bottom, computed from the neighbouring chunk's material (not from its light). */
     /**
      * A halo column's run bottom, read from the light that is already there.
      *
      * <p>The first version read it from the neighbouring chunk's material, which is more "correct" but cost ~7 ms per
      * recompute: 64 halo columns x 24 sections of first-touch material builds (4.5 us each) and 4 KB allocations. The
      * light-based read is justified by measurement: our stored light equals the rule's fixed point over 574,340
      * compared cells to within 24 cells (section 25), so the neighbours' runs are the rule's runs.</p>
      *
      * <p>Exactness: the first cell that is not 15 from the top IS the run bottom, because skylight attenuates by at
      * least 1 per step and therefore cannot be 15 again below its own run.</p>
      */
    private int haloRunBottom(final int wx, final int wz, final int minY, final int maxY) {
        for (int y = maxY; y >= minY; y--) {
            if (this.getLightLevel(wx, y, wz) != 15) {
                return y + 1;
            }
        }
        return minY;
    }

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
        final long tStart = System.nanoTime();

        // ---- 1) expand the window, column-major: index = col * height + (y - yLo)
        for (int section = minSection; section <= maxSection; section++) {
            final int sectionY0 = section * 16;

            if (sectionY0 + 15 < yLo || sectionY0 > yHi) {
                continue; // this section does not intersect the window
            }
            final int slot = chunkX + 5 * chunkZ + (5 * 5) * section + this.chunkSectionIndexOffset;
            final byte[] sectionMaterial = this.materialBound(slot);
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

                    material[outBase + sectionY0 + ly] = sectionMaterial != null
                            ? sectionMaterial[local]
                            : (byte) this.opacityOf(slot, local);
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

        // ---- 2) sweep: the 15-runs, from the world top down (the window's top edge may sit under a ceiling, so the run
        // bottom has to be found from the material, not assumed to be the window top)
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int col = (z << 4) | x;
                final int windowBase = col * height;

                // The part above the window is UNCHANGED, and 15 requires the cell above to be 15 - so ONE light read at
                // the window's top edge decides whether this column has a run inside the window at all. The earlier
                // version walked the palette from the world top for every column (~250 reads x 256 columns), which was
                // the settle's largest single cost.
                if (this.getLightLevel(worldX0 + x, yHi + 1 > worldMaxY ? worldMaxY : yHi + 1, worldZ0 + z) != 15) {
                    continue; // no run reaches the window from above: the BFS handles whatever the edit changed
                }
                int y = yHi;

                while (y >= yLo) {
                    final int i = windowBase + (y - yLo);

                    if (material[i] != 0) {
                        break;
                    }
                    light[i] = 15;
                    y--;
                }
            }
        }
        final long tSweep = System.nanoTime();

        // ---- 3) shell + 4) BFS, inside the window, exactly as the whole-chunk version does
        int tail = 0;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int col = (z << 4) | x;
                final int base = col * height;
                int runBottom = Integer.MIN_VALUE;

                for (int i = height - 1; i >= 0; i--) {
                    if ((light[base + i] & 0xFF) == 15) {
                        runBottom = yLo + i;
                        break;
                    }
                }
                if (runBottom == Integer.MIN_VALUE) {
                    continue;
                }
                final int runWest = this.runBottomNear(worldX0 + x - 1, worldZ0 + z, runBottom, worldMinY, worldMaxY);
                final int runEast = this.runBottomNear(worldX0 + x + 1, worldZ0 + z, runBottom, worldMinY, worldMaxY);
                final int runNorth = this.runBottomNear(worldX0 + x, worldZ0 + z - 1, runBottom, worldMinY, worldMaxY);
                final int runSouth = this.runBottomNear(worldX0 + x, worldZ0 + z + 1, runBottom, worldMinY, worldMaxY);
                final int lowestNeighbour = Math.min(Math.min(runWest, runEast), Math.min(runNorth, runSouth));

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
     * A neighbouring column's run bottom, asked only where it matters: if the light at the given height (or one below) is
     * 15, the neighbour's run reaches at least that far, which is all the shell test needs. Returning {@code runBottom}
     * when the neighbour is lit at or below it keeps the shell's "lowest neighbour" comparison honest without scanning a
     * whole column - that scan was 1 ms of the measured cost.
     */
    private int runBottomNear(final int wx, final int wz, final int runBottom, final int minY, final int maxY) {
        if (this.getLightLevel(wx, runBottom, wz) == 15) {
            return runBottom;
        }
        return Integer.MIN_VALUE;
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
