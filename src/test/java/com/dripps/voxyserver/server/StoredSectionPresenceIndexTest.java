package com.dripps.voxyserver.server;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLongArray;
import static org.junit.jupiter.api.Assertions.*;

public class StoredSectionPresenceIndexTest {

    @Test
    public void testPresenceIndexOperations() {
        StoredSectionPresenceIndex index = new StoredSectionPresenceIndex();
        assertFalse(index.isReady());

        assertTrue(index.tryScheduleBuild());
        AtomicLongArray filter = index.createBuildFilter();

        index.addTo(filter, 1001L);
        index.addTo(filter, 2002L);

        index.completeBuild(filter);
        assertTrue(index.isReady());

        assertTrue(index.mayContain(1001L));
        assertTrue(index.mayContain(2002L));
    }
}
