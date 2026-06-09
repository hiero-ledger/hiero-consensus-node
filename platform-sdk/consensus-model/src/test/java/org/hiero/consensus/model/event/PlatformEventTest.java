// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import static org.hiero.consensus.model.hashgraph.ConsensusConstants.MIN_TRANS_TIMESTAMP_INCR_NANOS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.EventConsensusData;
import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.platform.test.fixtures.utils.EqualsVerifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Random;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformEventTest {

    @Test
    @DisplayName("Serialize and deserialize event with 2 app payloads")
    void serializeDeserializeAppPayloads() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformEvent platformEvent = new TestingEventBuilder(random).build();
        final PlatformEvent copy = serializeDeserializePlatformEvent(platformEvent);
        assertEquals(platformEvent, copy, "deserialized version should be the same");
    }

    @Test
    @DisplayName("Serialize and deserialize event with no payloads")
    void serializeDeserializeNoPayloads() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformEvent platformEvent = new TestingEventBuilder(random)
                .setSystemTransactionCount(0)
                .setAppTransactionCount(0)
                .build();
        final PlatformEvent copy = serializeDeserializePlatformEvent(platformEvent);
        assertEquals(platformEvent, copy, "deserialized version should be the same");
    }

    @Test
    @DisplayName("Serialize and deserialize event with 2 system payloads")
    void serializeDeserializeSystemPayloads() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformEvent platformEvent = new TestingEventBuilder(random)
                .setAppTransactionCount(0)
                .setSystemTransactionCount(2)
                .build();
        final PlatformEvent copy = serializeDeserializePlatformEvent(platformEvent);
        assertEquals(platformEvent, copy, "deserialized version should be the same");
    }

    @Test
    @DisplayName("Serialize and deserialize event with 2 system payloads and 2 app payloads")
    void serializeDeserializeAppAndSystemPayloads() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformEvent platformEvent = new TestingEventBuilder(random)
                .setAppTransactionCount(2)
                .setSystemTransactionCount(2)
                .build();
        final PlatformEvent copy = serializeDeserializePlatformEvent(platformEvent);
        assertEquals(platformEvent, copy, "deserialized version should be the same");
    }

    @Test
    void validateEqualsHashCode() {
        assertTrue(EqualsVerifier.verify(random -> new TestingEventBuilder(random).build()));
    }

    @Test
    void validateDescriptor() {
        final Randotron r = Randotron.create();
        final PlatformEvent event = new TestingEventBuilder(r).build();
        event.invalidateHash();
        assertThrows(
                IllegalStateException.class,
                event::getDescriptor,
                "When the descriptor is not set, an exception should be thrown");
        event.setHash(r.nextHash());
        assertNotNull(event.getDescriptor(), "When the hash is set, the descriptor should be returned");
    }

    @Test
    void validateSetConsensusTimestampsOnTransactions() {
        final Randotron r = Randotron.create();
        final PlatformEvent event =
                new TestingEventBuilder(r).setAppTransactionCount(3).build();
        final Timestamp eventConsensusTime = Timestamp.newBuilder()
                .seconds(r.nextPositiveLong(1_000_000_000L))
                .nanos(r.nextPositiveInt(1_000_000_000))
                .build();
        final EventConsensusData consensusData = EventConsensusData.newBuilder()
                .consensusTimestamp(eventConsensusTime)
                .build();
        event.setConsensusData(consensusData);
        final long transactionOffsetNanos = r.nextPositiveLong(1_000L);
        event.setConsensusTimestampsOnTransactions(transactionOffsetNanos);
        final Instant expectedFirstTransactionTime = Instant.ofEpochSecond(
                eventConsensusTime.seconds(), eventConsensusTime.nanos() + (int) transactionOffsetNanos);
        final Instant expectedSecondTransactionTime =
                expectedFirstTransactionTime.plusNanos(MIN_TRANS_TIMESTAMP_INCR_NANOS);
        final Instant expectedThirdTransactionTime =
                expectedSecondTransactionTime.plusNanos(MIN_TRANS_TIMESTAMP_INCR_NANOS);
        assertEquals(
                expectedFirstTransactionTime,
                event.getTransactions().getFirst().getConsensusTimestamp(),
                "transaction 0 should be at event consensus time + offset");
        assertEquals(
                expectedSecondTransactionTime,
                event.getTransactions().get(1).getConsensusTimestamp(),
                "transaction 1 should be at event consensus time + offset + " + MIN_TRANS_TIMESTAMP_INCR_NANOS + "ns");
        assertEquals(
                expectedThirdTransactionTime,
                event.getTransactions().get(2).getConsensusTimestamp(),
                "transaction 2 should be at event consensus time + offset + " + MIN_TRANS_TIMESTAMP_INCR_NANOS + "ns");
    }

    @Test
    void validateSetConsensusTimestampsOnTransactionsWithZeroOffset() {
        final Randotron r = Randotron.create();
        final PlatformEvent event =
                new TestingEventBuilder(r).setAppTransactionCount(3).build();
        final Timestamp eventConsensusTime = Timestamp.newBuilder()
                .seconds(r.nextPositiveLong(1_000_000_000L))
                .nanos(r.nextPositiveInt(1_000_000_000))
                .build();
        final EventConsensusData consensusData = EventConsensusData.newBuilder()
                .consensusTimestamp(eventConsensusTime)
                .build();
        event.setConsensusData(consensusData);
        event.setConsensusTimestampsOnTransactions(0L);
        final Instant expectedFirstTransactionTime =
                Instant.ofEpochSecond(eventConsensusTime.seconds(), eventConsensusTime.nanos());
        final Instant expectedSecondTransactionTime =
                expectedFirstTransactionTime.plusNanos(MIN_TRANS_TIMESTAMP_INCR_NANOS);
        final Instant expectedThirdTransactionTime =
                expectedSecondTransactionTime.plusNanos(MIN_TRANS_TIMESTAMP_INCR_NANOS);
        assertEquals(
                expectedFirstTransactionTime,
                event.getTransactions().getFirst().getConsensusTimestamp(),
                "with zero offset, transaction 0 should be at event consensus time");
        assertEquals(
                expectedSecondTransactionTime,
                event.getTransactions().get(1).getConsensusTimestamp(),
                "with zero offset, transaction 1 should be at event consensus time + " + MIN_TRANS_TIMESTAMP_INCR_NANOS
                        + "ns");
        assertEquals(
                expectedThirdTransactionTime,
                event.getTransactions().get(2).getConsensusTimestamp(),
                "with zero offset, transaction 2 should be at event consensus time + "
                        + (MIN_TRANS_TIMESTAMP_INCR_NANOS * 2) + "ns");
    }
    /**
     * Serialize and then deserialize the given {@link PlatformEvent}.
     *
     * @param original the original event
     * @return the deserialized event
     * @throws IOException if an I/O error occurs
     */
    @NonNull
    private static PlatformEvent serializeDeserializePlatformEvent(@NonNull final PlatformEvent original)
            throws IOException {
        try (final ByteArrayOutputStream io = new ByteArrayOutputStream()) {
            final SerializableDataOutputStream out = new SerializableDataOutputStream(io);
            out.writePbjRecord(original.getGossipEvent(), GossipEvent.PROTOBUF);
            out.flush();
            final SerializableDataInputStream in =
                    new SerializableDataInputStream(new ByteArrayInputStream(io.toByteArray()));
            return new PlatformEvent(in.readPbjRecord(GossipEvent.PROTOBUF), original.getOrigin());
        }
    }
}
