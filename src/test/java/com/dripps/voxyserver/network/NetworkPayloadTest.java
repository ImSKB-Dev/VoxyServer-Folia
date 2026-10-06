package com.dripps.voxyserver.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NetworkPayloadTest {

    @Test
    public void testSectionPayloadEncoding() {
        LODSectionPayload payload = new LODSectionPayload(
                "minecraft:overworld",
                12345L,
                new int[]{1, 2, 3},
                new int[]{10, 20, 30},
                new byte[]{15, 12, 0},
                new short[]{0, 1, 2, 1, 0}
        );

        byte[] encoded = payload.encode();
        PacketBuffer buf = new PacketBuffer(encoded);
        LODSectionPayload decoded = LODSectionPayload.read(buf);

        assertEquals(payload.dimension(), decoded.dimension());
        assertEquals(payload.sectionKey(), decoded.sectionKey());
        assertArrayEquals(payload.lutBlockStateIds(), decoded.lutBlockStateIds());
        assertArrayEquals(payload.lutBiomeIds(), decoded.lutBiomeIds());
        assertArrayEquals(payload.lutLight(), decoded.lutLight());
        assertArrayEquals(payload.indexArray(), decoded.indexArray());
    }

    @Test
    public void testPreferencesPayloadEncoding() {
        LODPreferencesPayload payload = new LODPreferencesPayload(true, 128, 50);
        byte[] encoded = payload.encode();
        PacketBuffer buf = new PacketBuffer(encoded);
        LODPreferencesPayload decoded = LODPreferencesPayload.read(buf);

        assertTrue(decoded.enabled());
        assertEquals(128, decoded.lodStreamRadius());
        assertEquals(50, decoded.maxSectionsPerTick());
    }
}
