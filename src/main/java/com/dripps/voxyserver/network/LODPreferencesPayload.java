package com.dripps.voxyserver.network;

public record LODPreferencesPayload(
        boolean enabled,
        int lodStreamRadius,
        int maxSectionsPerTick
) {
    public static final String CHANNEL = "voxyserver:lod_preferences";

    public void write(PacketBuffer buf) {
        buf.writeBoolean(enabled);
        buf.writeVarInt(lodStreamRadius);
        buf.writeVarInt(maxSectionsPerTick);
    }

    public static LODPreferencesPayload read(PacketBuffer buf) {
        boolean enabled = buf.readBoolean();
        int lodStreamRadius = buf.readVarInt();
        int maxSectionsPerTick = buf.readVarInt();
        return new LODPreferencesPayload(enabled, lodStreamRadius, maxSectionsPerTick);
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
