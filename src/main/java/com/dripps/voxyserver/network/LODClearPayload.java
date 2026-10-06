package com.dripps.voxyserver.network;

public record LODClearPayload(String dimension) {
    public static final String CHANNEL = "voxyserver:lod_clear";

    public void write(PacketBuffer buf) {
        buf.writeUtf(dimension);
    }

    public static LODClearPayload read(PacketBuffer buf) {
        String dimension = buf.readUtf();
        return new LODClearPayload(dimension);
    }

    public byte[] encode() {
        PacketBuffer buf = new PacketBuffer();
        try {
            write(buf);
            return buf.toArray();
        } finally {
            buf.release();
        }
    }
}
