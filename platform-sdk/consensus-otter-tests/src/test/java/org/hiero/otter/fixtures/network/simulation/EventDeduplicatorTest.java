// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Random;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EventDeduplicator Test")
class EventDeduplicatorTest {

    private final EventDeduplicator deduplicator = new EventDeduplicator();

    @Test
    @DisplayName("a node submitting the same event again is a duplicate")
    void sameNodeIsDuplicate() {
        final PlatformEvent event = event();

        assertThat(deduplicator.addIfUnique(event, 0)).isTrue();
        assertThat(deduplicator.addIfUnique(event, 0)).isFalse();
        assertThat(deduplicator.addIfUnique(event.copyGossipedData(), 0)).isFalse();
    }

    @Test
    @DisplayName("each node submitting an event is unique the first time")
    void eachNodeIsUniqueOnce() {
        final PlatformEvent event = event();

        assertThat(deduplicator.addIfUnique(event, 0)).isTrue();
        assertThat(deduplicator.addIfUnique(event, 3)).isTrue();
        assertThat(deduplicator.addIfUnique(event, 70)).isTrue();
        assertThat(deduplicator.addIfUnique(event, 3)).isFalse();
        assertThat(deduplicator.addIfUnique(event, 70)).isFalse();
        assertThat(deduplicator.addIfUnique(event, 0)).isFalse();
    }

    @Test
    @DisplayName("the same descriptor with another signature is a different event")
    void otherSignatureIsUnique() {
        final PlatformEvent event = event();
        final PlatformEvent resigned = new PlatformEvent(
                event.getGossipEvent()
                        .copyBuilder()
                        .signature(Bytes.wrap(new byte[] {1, 2, 3}))
                        .build(),
                EventOrigin.GOSSIP);
        new DefaultEventHasher().hashEvent(resigned);

        assertThat(deduplicator.addIfUnique(event, 0)).isTrue();
        assertThat(deduplicator.addIfUnique(resigned, 0)).isTrue();
        assertThat(deduplicator.addIfUnique(resigned, 0)).isFalse();
        assertThat(deduplicator.addIfUnique(event, 0)).isFalse();
    }

    @Test
    @DisplayName("an event that is expired for every node is always unique")
    void expiredEventIsUnique() {
        final PlatformEvent event = event();
        deduplicator.setOldestEventWindow(new EventWindow(100, 101, 100, 100));

        assertThat(deduplicator.addIfUnique(event, 0)).isTrue();
        assertThat(deduplicator.addIfUnique(event, 0)).isTrue();
    }

    private static PlatformEvent event() {
        final PlatformEvent event =
                new TestingEventBuilder(new Random(1)).setBirthRound(5).build();
        new DefaultEventHasher().hashEvent(event);
        return event;
    }
}
