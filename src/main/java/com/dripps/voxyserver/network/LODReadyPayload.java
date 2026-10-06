package com.dripps.voxyserver.network;

public record LODReadyPayload() {
    public static final String CHANNEL = "voxyserver:lod_ready";

    public void write(PacketBuffer buf) {
        // empty payload
    }

    public static LODReadyPayload read(PacketBuffer buf) {
        return new LODReadyPayload();
    }

    public byte[] encode() {
        return new byte[0];
    }
}
