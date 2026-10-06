package com.dripps.voxyserver.network;

import java.util.ArrayList;
import java.util.List;

public record LODBulkPayload(List<PreSerializedLodPayload> payloads) {
    public static final String CHANNEL = "voxyserver:lod_bulk";

    public void write(PacketBuffer buf) {
        buf.writeVarInt(payloads.size());
        for (PreSerializedLodPayload payload : payloads) {
            buf.writeVarInt(payload.data().length);
            buf.writeBytes(payload.data());
        }
    }

    public static LODBulkPayload read(PacketBuffer buf) {
        int count = buf.readVarInt();
        List<PreSerializedLodPayload> payloads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int len = buf.readVarInt();
            byte[] data = new byte[len];
            buf.readBytes(data);
            payloads.add(new PreSerializedLodPayload(data));
        }
        return new LODBulkPayload(payloads);
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
