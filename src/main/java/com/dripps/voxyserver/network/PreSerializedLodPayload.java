package com.dripps.voxyserver.network;

public record PreSerializedLodPayload(byte[] data) {
    public static final String CHANNEL = "voxyserver:lod_preserialized";

    public void write(PacketBuffer buf) {
        buf.writeVarInt(data.length);
        buf.writeBytes(data);
    }

    public static PreSerializedLodPayload read(PacketBuffer buf) {
        int len = buf.readVarInt();
        byte[] data = new byte[len];
        buf.readBytes(data);
        return new PreSerializedLodPayload(data);
    }

    public static PreSerializedLodPayload fromBulk(LODBulkPayload bulk) {
        byte[] encodedBulk = bulk.encode();
        return new PreSerializedLodPayload(encodedBulk);
    }

    public LODBulkPayload decodeBulk() {
        PacketBuffer buf = new PacketBuffer(data);
        try {
            return LODBulkPayload.read(buf);
        } finally {
            buf.release();
        }
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
