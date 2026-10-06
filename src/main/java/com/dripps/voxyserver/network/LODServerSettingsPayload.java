package com.dripps.voxyserver.network;

public record LODServerSettingsPayload(
        int maxLodStreamRadius,
        int maxSectionsPerTick
) {
    public static final String CHANNEL = "voxyserver:lod_server_settings";

    public void write(PacketBuffer buf) {
        buf.writeVarInt(maxLodStreamRadius);
        buf.writeVarInt(maxSectionsPerTick);
    }

    public static LODServerSettingsPayload read(PacketBuffer buf) {
        int maxLodStreamRadius = buf.readVarInt();
        int maxSectionsPerTick = buf.readVarInt();
        return new LODServerSettingsPayload(maxLodStreamRadius, maxSectionsPerTick);
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
