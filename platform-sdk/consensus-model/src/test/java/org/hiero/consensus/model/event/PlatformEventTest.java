// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.MIN_TRANS_TIMESTAMP_INCR_NANOS;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.EventConsensusData;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.hiero.base.crypto.test.fixtures.EqualsVerifier;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.node.NodeId;
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
        assertThat(copy).describedAs("deserialized version should be the same").isEqualTo(platformEvent);
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
        assertThat(copy).describedAs("deserialized version should be the same").isEqualTo(platformEvent);
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
        assertThat(copy).describedAs("deserialized version should be the same").isEqualTo(platformEvent);
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
        assertThat(copy).describedAs("deserialized version should be the same").isEqualTo(platformEvent);
    }

    @Test
    void validateEqualsHashCode() {
        assertThat(EqualsVerifier.verify(random -> new TestingEventBuilder(random).build()))
                .isTrue();
    }

    @Test
    void validateDescriptor() {
        final Randotron r = Randotron.create();
        final PlatformEvent event = new TestingEventBuilder(r).build();
        event.invalidateHash();
        assertThatThrownBy(event::getDescriptor)
                .describedAs("When the descriptor is not set, an exception should be thrown")
                .isInstanceOf(IllegalStateException.class);
        event.setHash(r.nextHash());
        assertThat(event.getDescriptor())
                .describedAs("When the hash is set, the descriptor should be returned")
                .isNotNull();
    }

    @Test
    void lastStageTimeOfANewEventIsTheTimeItsTransactionsWereTaken() {
        final Instant timeTransactionsTaken = Instant.parse("2026-01-01T00:00:00Z");
        final UnsignedEvent unsignedEvent = new UnsignedEvent(
                NodeId.of(0),
                List.of(),
                1,
                timeTransactionsTaken.minusSeconds(1),
                List.of(),
                0,
                timeTransactionsTaken,
                List.of());
        final PlatformEvent event = new PlatformEvent(unsignedEvent, Bytes.EMPTY, EventOrigin.RUNTIME);
        assertThat(event.getLastStageTime())
                .describedAs("The last stage time of a new event should be the time its transactions were taken")
                .isEqualTo(timeTransactionsTaken);
    }

    @Test
    void lastStageTimeOfAGossipedEventIsTheTimeReceived() {
        final PlatformEvent createdEvent = new TestingEventBuilder(Randotron.create()).build();
        final PlatformEvent gossipedEvent = new PlatformEvent(createdEvent.getGossipEvent(), EventOrigin.GOSSIP);
        assertThat(gossipedEvent.getLastStageTime())
                .describedAs("The last stage time of a gossiped event should be the time it was received")
                .isEqualTo(gossipedEvent.getTimeReceived());
    }

    @Test
    void newEventKeepsTheTimesItsTransactionsWereReceived() {
        final Instant timeTransactionsTaken = Instant.parse("2026-01-01T00:00:00Z");
        final List<Instant> transactionReceivedTimes =
                List.of(timeTransactionsTaken.minusMillis(3), timeTransactionsTaken.minusMillis(1));
        final UnsignedEvent unsignedEvent = new UnsignedEvent(
                NodeId.of(0),
                List.of(),
                1,
                timeTransactionsTaken,
                List.of(Bytes.wrap(new byte[] {1}), Bytes.wrap(new byte[] {2})),
                0,
                timeTransactionsTaken,
                transactionReceivedTimes);
        final PlatformEvent event = new PlatformEvent(unsignedEvent, Bytes.EMPTY, EventOrigin.RUNTIME);
        assertThat(event.getTransactionReceivedTimes())
                .describedAs("A new event should keep the times its transactions were received")
                .isEqualTo(transactionReceivedTimes);
        assertThat(event.copyGossipedData().getTransactionReceivedTimes())
                .describedAs("A copy of the gossiped data should not have the times the transactions were received")
                .isEmpty();
    }

    @Test
    void gossipedEventHasNoTransactionReceivedTimes() {
        final PlatformEvent createdEvent = new TestingEventBuilder(Randotron.create())
                .setAppTransactionCount(2)
                .build();
        final PlatformEvent gossipedEvent = new PlatformEvent(createdEvent.getGossipEvent(), EventOrigin.GOSSIP);
        assertThat(gossipedEvent.getTransactionReceivedTimes())
                .describedAs("A gossiped event should not have the times its transactions were received")
                .isEmpty();
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
        assertThat(event.getTransactions().getFirst().getConsensusTimestamp())
                .describedAs("transaction 0 should be at event consensus time + offset")
                .isEqualTo(expectedFirstTransactionTime);
        assertThat(event.getTransactions().get(1).getConsensusTimestamp())
                .describedAs("transaction 1 should be at event consensus time + offset + "
                        + MIN_TRANS_TIMESTAMP_INCR_NANOS + "ns")
                .isEqualTo(expectedSecondTransactionTime);
        assertThat(event.getTransactions().get(2).getConsensusTimestamp())
                .describedAs("transaction 2 should be at event consensus time + offset + "
                        + MIN_TRANS_TIMESTAMP_INCR_NANOS + "ns")
                .isEqualTo(expectedThirdTransactionTime);
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
        assertThat(event.getTransactions().getFirst().getConsensusTimestamp())
                .describedAs("with zero offset, transaction 0 should be at event consensus time")
                .isEqualTo(expectedFirstTransactionTime);
        assertThat(event.getTransactions().get(1).getConsensusTimestamp())
                .describedAs("with zero offset, transaction 1 should be at event consensus time + "
                        + MIN_TRANS_TIMESTAMP_INCR_NANOS + "ns")
                .isEqualTo(expectedSecondTransactionTime);
        assertThat(event.getTransactions().get(2).getConsensusTimestamp())
                .describedAs("with zero offset, transaction 2 should be at event consensus time + "
                        + (MIN_TRANS_TIMESTAMP_INCR_NANOS * 2) + "ns")
                .isEqualTo(expectedThirdTransactionTime);
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
