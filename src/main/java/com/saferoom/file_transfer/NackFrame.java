package com.saferoom.file_transfer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class NackFrame {
    public static final int SIZE = 28; // 20 + 8 bytes for timestamp

    private final ByteBuffer buf;

    public NackFrame() {
        this.buf = ByteBuffer.allocateDirect(SIZE).order(ByteOrder.BIG_ENDIAN);
    }

    public ByteBuffer buffer() { return buf; }

    public void fill(long fileId, int baseSeq, long mask64) {
        buf.clear();
        buf.putLong(0, fileId);         // 0-7: fileId
        buf.putInt (8, baseSeq);        // 8-11: base sequence
        buf.putLong(12, mask64);        // 12-19: bitmask
        buf.putLong(20, System.nanoTime()); // 20-27: NACK send timestamp
        buf.limit(SIZE);
        buf.position(0);
    }

    public void resetForRetry() { buf.position(0).limit(SIZE); }

    public static long  fileId(ByteBuffer b)     { return b.getLong(0); }
    public static int   baseSeq(ByteBuffer b)    { return b.getInt(8); }
    public static long  mask64(ByteBuffer b)     { return b.getLong(12); }
    public static long  timestamp(ByteBuffer b)  { return b.getLong(20); }
}
