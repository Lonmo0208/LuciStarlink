package dev.lucistarlink.light.util;

import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionLightData;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 探针：{@link SectionFaceMask} 判断「哪个面变了」必须与「哪一格变了」这个事实一致。
 *
 * <p>对照方是两条独立的判据，不是同一份推导的复述：
 * 期望值由**格子坐标的面归属**直接推出（x==0 → -X、x==15 → +X、其余同理），完全不涉及字节布局；
 * 输入字节由另一段生产代码 {@link NibbleSectionPacker} 从平面数据打出，所以一旦 {@code SectionFaceMask}
 * 对打包方式的理解与它不一致，测试就会失败。
 */
class SectionFaceMaskTest {
    private static final int NEGATIVE_X = 1;
    private static final int POSITIVE_X = 2;
    private static final int NEGATIVE_Y = 4;
    private static final int POSITIVE_Y = 8;
    private static final int NEGATIVE_Z = 16;
    private static final int POSITIVE_Z = 32;

    @Test
    void missingOrShortLayersAreTreatedAsEveryFaceChanged() {
        byte[] full = new byte[2048];
        assertEquals(SectionFaceMask.ALL_FACES, SectionFaceMask.changedFaces(null, full));
        assertEquals(SectionFaceMask.ALL_FACES, SectionFaceMask.changedFaces(full, null));
        assertEquals(SectionFaceMask.ALL_FACES, SectionFaceMask.changedFaces(new byte[7], full));
        assertEquals(SectionFaceMask.ALL_FACES, SectionFaceMask.changedFaces(full, new byte[0]));
        assertEquals(0, SectionFaceMask.changedFaces(full, full));
    }

    @Test
    void everySingleCellChangeReportsExactlyTheFacesThatCellBelongsTo() {
        RegionLightData data = randomRegion(new Random(0x5EEDL));
        byte[] before = pack(data);
        assertNotNull(before);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    byte[] after = pack(singleEdit(data, x, y, z, 7));
                    assertNotNull(after, "cell (" + x + "," + y + "," + z + ")");
                    assertEquals(facesOf(x, y, z), SectionFaceMask.changedFaces(before, after),
                            "cell (" + x + "," + y + "," + z + ")");
                }
            }
        }
    }

    @Test
    void interiorCellChangesRaiseNoFace() {
        RegionLightData data = randomRegion(new Random(0xABBAL));
        byte[] before = pack(data);
        for (int y = 1; y < 15; y++) {
            for (int z = 1; z < 15; z++) {
                for (int x = 1; x < 15; x++) {
                    byte[] after = pack(singleEdit(data, x, y, z, 5));
                    assertNotNull(after, "cell (" + x + "," + y + "," + z + ")");
                    assertEquals(0, SectionFaceMask.changedFaces(before, after),
                            "cell (" + x + "," + y + "," + z + ")");
                }
            }
        }
    }

    @Test
    void multiCellEditsReportTheUnionOfTouchedFaces() {
        Random random = new Random(0xC0FFEEL);
        for (int sample = 0; sample < 200; sample++) {
            RegionLightData data = randomRegion(random);
            byte[] before = pack(data);
            RegionLightData edited = copyOf(data);
            int expected = 0;
            int edits = 1 + random.nextInt(24);
            for (int edit = 0; edit < edits; edit++) {
                int x = random.nextInt(16);
                int y = random.nextInt(16);
                int z = random.nextInt(16);
                int index = edited.localIndex(x, y, z);
                edited.blockLight[index] = (byte) ((edited.blockLight[index] + 9) & 15);
                expected |= facesOf(x, y, z);
            }
            byte[] after = pack(edited);
            assertNotNull(after, "sample " + sample);
            assertEquals(expected, SectionFaceMask.changedFaces(before, after), "sample " + sample);
        }
    }

    @Test
    void eachFaceSweepMatchesTheCellBasedExpectation() {
        RegionLightData data = randomRegion(new Random(0xFACE1L));
        byte[] before = pack(data);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                assertEquals(facesOf(0, y, z), sweep(data, before, 0, y, z), "negative X (" + y + "," + z + ")");
                assertEquals(facesOf(15, y, z), sweep(data, before, 15, y, z), "positive X (" + y + "," + z + ")");
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                assertEquals(facesOf(x, 0, z), sweep(data, before, x, 0, z), "negative Y (" + x + "," + z + ")");
                assertEquals(facesOf(x, 15, z), sweep(data, before, x, 15, z), "positive Y (" + x + "," + z + ")");
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                assertEquals(facesOf(x, y, 0), sweep(data, before, x, y, 0), "negative Z (" + x + "," + y + ")");
                assertEquals(facesOf(x, y, 15), sweep(data, before, x, y, 15), "positive Z (" + x + "," + y + ")");
            }
        }
    }

    private static int facesOf(int x, int y, int z) {
        int mask = 0;
        if (x == 0) {
            mask |= NEGATIVE_X;
        }
        if (x == 15) {
            mask |= POSITIVE_X;
        }
        if (y == 0) {
            mask |= NEGATIVE_Y;
        }
        if (y == 15) {
            mask |= POSITIVE_Y;
        }
        if (z == 0) {
            mask |= NEGATIVE_Z;
        }
        if (z == 15) {
            mask |= POSITIVE_Z;
        }
        return mask;
    }

    private static int sweep(RegionLightData base, byte[] before, int x, int y, int z) {
        byte[] after = pack(singleEdit(base, x, y, z, 3));
        assertNotNull(after, "cell (" + x + "," + y + "," + z + ")");
        return SectionFaceMask.changedFaces(before, after);
    }

    private static RegionLightData singleEdit(RegionLightData base, int x, int y, int z, int delta) {
        RegionLightData edited = copyOf(base);
        int index = edited.localIndex(x, y, z);
        edited.blockLight[index] = (byte) ((edited.blockLight[index] + delta) & 15);
        return edited;
    }

    private static byte[] pack(RegionLightData data) {
        return NibbleSectionPacker.packSectionBytes(data, data.blockLight, 0, 0, 0);
    }

    private static RegionLightData emptyRegion() {
        return new RegionLightData(singleSectionBounds());
    }

    private static RegionLightData copyOf(RegionLightData source) {
        RegionLightData copy = emptyRegion();
        System.arraycopy(source.blockLight, 0, copy.blockLight, 0, source.blockLight.length);
        return copy;
    }

    private static RegionLightData randomRegion(Random random) {
        RegionLightData data = emptyRegion();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    data.blockLight[data.localIndex(x, y, z)] = (byte) random.nextInt(16);
                }
            }
        }
        return data;
    }

    private static RegionBounds singleSectionBounds() {
        return new RegionBounds(0, 0, 1, 0, 16, 16,
                0, 16, 0, 1, 16, 256, 4096);
    }
}
