package dev.lucistarlink.light.reference;

import java.util.Arrays;

/**
 * Straightforward, deliberately slow reference implementation of vanilla-equivalent
 * block light and skylight semantics on a plain material grid. It recomputes the
 * whole volume from scratch and exists only as ground truth for differential tests.
 *
 * Material encoding matches LightMaterial: opacity 0..15, emission 0..15.
 * Queue entries pack (index << 4) | level.
 */
public final class ReferenceLightEngine {
    private static final int LEVEL_BITS = 4;

    private final int width;
    private final int depth;
    private final int height;
    private final int area;
    private final int volume;
    private final byte[] opacity;
    private final byte[] emission;
    public final byte[] blockLight;
    public final byte[] skyLight;

    public ReferenceLightEngine(int width, int depth, int height, byte[] opacity, byte[] emission) {
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.area = width * depth;
        this.volume = area * height;
        this.opacity = opacity;
        this.emission = emission;
        this.blockLight = new byte[volume];
        this.skyLight = new byte[volume];
    }

    public int index(int x, int y, int z) {
        return x + z * width + y * area;
    }

    public byte[] opacityArray() {
        return opacity;
    }

    public byte[] emissionArray() {
        return emission;
    }

    public void recompute() {
        recomputeBlock();
        recomputeSky();
    }

    private void recomputeBlock() {
        Arrays.fill(blockLight, (byte) 0);
        int[] queue = new int[volume];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < volume; i++) {
            int e = emission[i] & 0xF;
            blockLight[i] = (byte) e;
            if (e > 1) {
                queue[tail++] = (i << LEVEL_BITS) | e;
            }
        }
        while (head < tail) {
            int packed = queue[head++];
            int index = packed >>> LEVEL_BITS;
            int level = packed & 0xF;
            if ((blockLight[index] & 0xF) > level) {
                continue;
            }
            int x = index % width;
            int rem = index - x;
            int z = (rem / width) % depth;
            int y = index / area;
            tail = spreadBlock(queue, tail, x + 1, y, z, level);
            tail = spreadBlock(queue, tail, x - 1, y, z, level);
            tail = spreadBlock(queue, tail, x, y, z + 1, level);
            tail = spreadBlock(queue, tail, x, y, z - 1, level);
            tail = spreadBlock(queue, tail, x, y + 1, z, level);
            tail = spreadBlock(queue, tail, x, y - 1, z, level);
        }
    }

    private int spreadBlock(int[] queue, int tail, int x, int y, int z, int level) {
        if (x < 0 || x >= width || z < 0 || z >= depth || y < 0 || y >= height) {
            return tail;
        }
        int index = index(x, y, z);
        int candidate = Math.max(0, level - Math.max(1, opacity[index] & 0xF));
        if (candidate > (blockLight[index] & 0xF)) {
            blockLight[index] = (byte) candidate;
            if (candidate > 1) {
                queue[tail] = (index << LEVEL_BITS) | candidate;
                return tail + 1;
            }
        }
        return tail;
    }

    private void recomputeSky() {
        Arrays.fill(skyLight, (byte) 0);
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int level = 15;
                for (int y = height - 1; y >= 0 && level > 0; y--) {
                    int index = index(x, y, z);
                    int o = opacity[index] & 0xF;
                    if (o == 0 && level == 15) {
                        skyLight[index] = 15;
                    } else {
                        level = Math.max(0, level - Math.max(1, o));
                        skyLight[index] = (byte) level;
                    }
                }
            }
        }
        int[] queue = new int[volume];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < volume; i++) {
            if ((skyLight[i] & 0xF) > 1) {
                queue[tail++] = (i << LEVEL_BITS) | (skyLight[i] & 0xF);
            }
        }
        while (head < tail) {
            int packed = queue[head++];
            int index = packed >>> LEVEL_BITS;
            int level = packed & 0xF;
            if ((skyLight[index] & 0xF) > level) {
                continue;
            }
            if (level <= 1) {
                continue;
            }
            int x = index % width;
            int rem = index - x;
            int z = (rem / width) % depth;
            int y = index / area;
            tail = spreadSky(queue, tail, x + 1, y, z, level);
            tail = spreadSky(queue, tail, x - 1, y, z, level);
            tail = spreadSky(queue, tail, x, y, z + 1, level);
            tail = spreadSky(queue, tail, x, y, z - 1, level);
            tail = spreadSky(queue, tail, x, y + 1, z, level);
            tail = spreadSky(queue, tail, x, y - 1, z, level);
        }
    }

    private int spreadSky(int[] queue, int tail, int x, int y, int z, int level) {
        if (x < 0 || x >= width || z < 0 || z >= depth || y < 0 || y >= height) {
            return tail;
        }
        int index = index(x, y, z);
        int candidate = Math.max(0, level - Math.max(1, opacity[index] & 0xF));
        if (candidate > (skyLight[index] & 0xF)) {
            skyLight[index] = (byte) candidate;
            if (candidate > 1) {
                queue[tail] = (index << LEVEL_BITS) | candidate;
                return tail + 1;
            }
        }
        return tail;
    }
}
