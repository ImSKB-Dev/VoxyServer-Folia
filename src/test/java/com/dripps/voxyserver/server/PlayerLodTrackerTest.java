package com.dripps.voxyserver.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PlayerLodTrackerTest {

    @Test
    public void testPlayerTrackerStateAndPreferences() {
        PlayerLodTracker tracker = new PlayerLodTracker();
        assertFalse(tracker.isReady());

        tracker.setReady(true);
        assertTrue(tracker.isReady());

        tracker.setPreferredRadius(128);
        assertEquals(128, tracker.getEffectiveRadius(256));
        assertEquals(100, tracker.getEffectiveRadius(100));

        tracker.setPreferredMaxSections(20);
        assertEquals(20, tracker.getEffectiveMaxSections(50));
    }

    @Test
    public void testScanSequence() {
        PlayerLodTracker tracker = new PlayerLodTracker();
        tracker.setReady(true);

        boolean prepared = tracker.prepareScan(0, 0, 4, 0, 4, 1L, 20L);
        assertTrue(prepared);

        long key1 = tracker.nextSectionKeyToScan(1L, 20L);
        assertNotEquals(PlayerLodTracker.NO_SECTION_KEY, key1);

        tracker.markSent(key1, 1);
        assertTrue(tracker.hasSent(key1, 1));
        assertFalse(tracker.hasSent(key1, 2));
    }
}
