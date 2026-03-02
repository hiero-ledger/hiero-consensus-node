// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.Test;

class PbjStreamHasherTest {

    private static final Random RANDOM = RandomUtils.getRandomPrintSeed();

    @Test
    void reusableAfterTransactionException() {
        final PbjStreamHasher hasher = new PbjStreamHasher();

        // 1. Hash a valid event and record the expected hash
        final PlatformEvent validEvent = new TestingEventBuilder(RANDOM).build();
        hasher.hashEvent(validEvent);
        final Hash expectedHash = validEvent.getHash();
        assertNotNull(expectedHash);

        // 2. Hash a poisoned event — a transaction that returns null triggers NPE mid-hash,
        //    dirtying both eventDigest and transactionDigest
        final PlatformEvent poisonEvent = new TestingEventBuilder(RANDOM).build();
        final TransactionWrapper poison = mock(TransactionWrapper.class);
        when(poison.getApplicationTransaction()).thenReturn(null);

        final PlatformEvent badEvent = mock(PlatformEvent.class);
        when(badEvent.getEventCore()).thenReturn(poisonEvent.getEventCore());
        when(badEvent.getGossipEvent()).thenReturn(poisonEvent.getGossipEvent());
        when(badEvent.getTransactions()).thenReturn(List.of(poison));

        assertThrows(NullPointerException.class, () -> hasher.hashEvent(badEvent));

        // 3. Hash the valid event again — must produce the same hash, proving the digests were reset
        hasher.hashEvent(validEvent);
        assertEquals(expectedHash, validEvent.getHash());
    }

    @Test
    void reusableAfterParentException() {
        final PbjStreamHasher hasher = new PbjStreamHasher();

        // 1. Hash a valid event and record the expected hash
        final PlatformEvent validEvent = new TestingEventBuilder(RANDOM).build();
        hasher.hashEvent(validEvent);
        final Hash expectedHash = validEvent.getHash();
        assertNotNull(expectedHash);

        // 2. Construct a GossipEvent with a null in the parents list.
        //    EventCore is written first, then during parent iteration the null triggers NPE,
        //    dirtying only eventDigest (transactionDigest is untouched).
        final PlatformEvent sourceEvent = new TestingEventBuilder(RANDOM).build();
        final ArrayList<EventDescriptor> parents = new ArrayList<>();
        parents.add(null);
        final GossipEvent poisonGossip = new GossipEvent(
                sourceEvent.getEventCore(),
                sourceEvent.getSignature(),
                sourceEvent.getGossipEvent().transactions(),
                parents);

        final PlatformEvent badEvent = mock(PlatformEvent.class);
        when(badEvent.getEventCore()).thenReturn(sourceEvent.getEventCore());
        when(badEvent.getGossipEvent()).thenReturn(poisonGossip);
        when(badEvent.getTransactions()).thenReturn(sourceEvent.getTransactions());

        assertThrows(NullPointerException.class, () -> hasher.hashEvent(badEvent));

        // 3. Hash the valid event again — if eventDigest wasn't reset, the hash would be corrupted
        hasher.hashEvent(validEvent);
        assertEquals(expectedHash, validEvent.getHash());
    }
}
