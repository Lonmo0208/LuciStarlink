package dev.lucistarlink.light.util;

/**
 * 客户端优化的核心判据：一个 section 的**六个面**里，哪些面真的有格子变了。
 *
 * <p>为什么需要它：原版（以及我们目前的默认）在发布一个 section 时会把 3×3×3 共 27 个 section 都标成
 * 「光照变了」，而这些标记会让服务端给客户端发光照数据 —— 实测通知量因此是必要的 7.1 倍
 * （10015 → 1401），第一轮客户端实测进服两分钟就收到 11,729 个 section（约 24 MB）。
 *
 * <p>规则**构造上正确**：邻区的光照与网格只可能因为与它共享的那一层变化而变化。所以「没变的面 → 不通知那个
 * 邻区」永远不会漏掉必需的通知（比直接关掉扇出安全），而「变了的面 → 照旧扇出」也永远不会少通知（保守一侧）。
 *
 * <p>实现按 DataLayer 的打包方式（索引 {@code (y<<8)|(z<<4)|x}，偶索引占低四位）读固定的 128 字节子区间。
 * -X/+X 两面各占同一字节的一半，该字节的另一半是 x=1 / x=14 的格子、不属于这两个面，所以这两面必须带半字节
 * 掩码；-Y/+Y/-Z/+Z 四面里字节的两半都属于该面（半边是 x 偶、半边是 x 奇），所以按整字节比较。用错掩码的
 * 后果是**漏报**（该通知的邻区不通知）或误报，两者都被 {@code SectionFaceMaskTest} 的逐格穷举钉住。
 */
public final class SectionFaceMask {
    /** 1=-X 2=+X 4=-Y 8=+Y 16=-Z 32=+Z */
    public static final int ALL_FACES = 63;

    /** 1=-X */
    public static final int NEGATIVE_X = 1;
    /** 2=+X */
    public static final int POSITIVE_X = 2;
    /** 4=-Y */
    public static final int NEGATIVE_Y = 4;
    /** 8=+Y */
    public static final int POSITIVE_Y = 8;
    /** 16=-Z */
    public static final int NEGATIVE_Z = 16;
    /** 32=+Z */
    public static final int POSITIVE_Z = 32;

    private static final int LAYER_BYTES = 2048;
    private static final int LOW_NIBBLE = 15;
    private static final int HIGH_NIBBLE = 240;

    private SectionFaceMask() {
    }

    /**
     * @return 位掩码 1=-X 2=+X 4=-Y 8=+Y 16=-Z 32=+Z；{@code before} 为 null 或字节数不足时返回
     *         {@link #ALL_FACES}（全变，按最保守处理）
     */
    public static int changedFaces(byte[] before, byte[] after) {
        if (before == null || after == null
                || before.length < LAYER_BYTES || after.length < LAYER_BYTES) {
            return ALL_FACES;
        }
        int mask = 0;
        for (int y = 0; y < 16; y++) {
            int row = y << 7;
            for (int z = 0; z < 16; z++) {
                int index = row + (z << 3);
                if ((before[index] & LOW_NIBBLE) != (after[index] & LOW_NIBBLE)) {
                    mask |= NEGATIVE_X;
                }
                if ((before[index | 7] & HIGH_NIBBLE) != (after[index | 7] & HIGH_NIBBLE)) {
                    mask |= POSITIVE_X;
                }
            }
            for (int x = 0; x < 16; x += 2) {
                int index = row + (x >> 1);
                if (before[index] != after[index]) {
                    mask |= NEGATIVE_Z;
                }
                if (before[index + 120] != after[index + 120]) {
                    mask |= POSITIVE_Z;
                }
            }
        }
        for (int z = 0; z < 16; z++) {
            int base = z << 3;
            for (int x = 0; x < 16; x += 2) {
                int index = base + (x >> 1);
                if (before[index] != after[index]) {
                    mask |= NEGATIVE_Y;
                }
                if (before[index + 1920] != after[index + 1920]) {
                    mask |= POSITIVE_Y;
                }
            }
        }
        return mask;
    }
}
