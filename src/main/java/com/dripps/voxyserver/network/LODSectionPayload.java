package com.dripps.voxyserver.network;

public record LODSectionPayload(
        String dimension,
        long sectionKey,
        int[] lutBlockStateIds,
        int[] lutBiomeIds,
        byte[] lutLight,
        short[] indexArray
) {
    public static final String CHANNEL = "voxyserver:lod_section";

    public void write(PacketBuffer buf) {
        buf.writeUtf(dimension);
        buf.writeLong(sectionKey);

        int lutLen = lutBlockStateIds.length;
        buf.writeVarInt(lutLen);
        for (int i = 0; i < lutLen; i++) {
            buf.writeVarInt(lutBlockStateIds[i]);
            buf.writeVarInt(lutBiomeIds[i]);
            buf.writeByte(lutLight[i]);
        }

        int bitsPerEntry = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(lutLen - 1, 0)));
        int entriesPerLong = 64 / bitsPerEntry;
        int longCount = (indexArray.length + entriesPerLong - 1) / entriesPerLong;

        buf.writeVarInt(indexArray.length);
        buf.writeByte(bitsPerEntry);
        for (int li = 0; li < longCount; li++) {
            long packed = 0L;
            int base = li * entriesPerLong;
            for (int ei = 0; ei < entriesPerLong && base + ei < indexArray.length; ei++) {
                packed |= ((long) (indexArray[base + ei] & 0xFFFF)) << (ei * bitsPerEntry);
            }
            buf.writeLong(packed);
        }
    }

    public static LODSectionPayload read(PacketBuffer buf) {
        String dimension = buf.readUtf();
        long sectionKey = buf.readLong();

        int lutLen = buf.readVarInt();
        int[] blockStateIds = new int[lutLen];
        int[] biomeIds = new int[lutLen];
        byte[] light = new byte[lutLen];
        for (int i = 0; i < lutLen; i++) {
            blockStateIds[i] = buf.readVarInt();
            biomeIds[i] = buf.readVarInt();
            light[i] = buf.readByte();
        }

        int indexLen = buf.readVarInt();
        int bitsPerEntry = buf.readByte() & 0xFF;
        int entriesPerLong = 64 / bitsPerEntry;
        long mask = (1L << bitsPerEntry) - 1;
        short[] indexArray = new short[indexLen];
        int idx = 0;
        while (idx < indexLen) {
            long packed = buf.readLong();
            for (int ei = 0; ei < entriesPerLong && idx < indexLen; ei++, idx++) {
                indexArray[idx] = (short) ((packed >> (ei * bitsPerEntry)) & mask);
            }
        }

        return new LODSectionPayload(dimension, sectionKey, blockStateIds, biomeIds, light, indexArray);
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
