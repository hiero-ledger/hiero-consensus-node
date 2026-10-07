// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_PAYLOAD_TOO_LARGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_QUEUE_FULL;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprConfigUpdate;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.clpr.ClprControlMessage;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.ClprServiceApiImpl;
import com.hedera.node.app.service.clpr.impl.ReadableChannelStoreImpl;
import com.hedera.node.app.service.clpr.impl.ReadableLedgerConfigurationStoreImpl;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.clpr.impl.WritableMessageQueueStore;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.testfixtures.ClprConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClprServiceApiImplTest {

    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final Bytes CONNECTOR_CONTRACT = Bytes.wrap(new byte[20]);
    private static final Bytes TARGET_APP = Bytes.wrap(new byte[] {10, 20, 30});
    private static final Bytes SENDER = Bytes.wrap(new byte[20]);
    private static final Bytes MESSAGE_DATA = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
    private static final Bytes SOURCE_CONNECTOR_ADDRESS = Bytes.wrap(new byte[] {99, 88, 77});
    private static final Bytes INITIAL_RUNNING_HASH = Bytes.wrap(new byte[32]);
    private static final Timestamp CONFIG_TIMESTAMP =
            Timestamp.newBuilder().seconds(1000).nanos(0).build();

    @Mock
    private ReadableChannelStoreImpl channelReadStore;

    @Mock
    private WritableChannelStore channelWriteStore;

    @Mock
    private WritableConnectorStore connectorStore;

    @Mock
    private ReadableLedgerConfigurationStoreImpl configStore;

    @Mock
    private WritableMessageQueueStore messageQueueStore;

    @Mock
    private Configuration configuration;

    private ClprServiceApiImpl subject;

    private static final ClprConfig DEFAULT_CLPR_CONFIG = ClprConfigBuilder.newBuilder()
            .enabled(true)
            .chainId("hiero:localnetb")
            .slashBasePenalty(1_000_000L)
            .discoveryIntervalSeconds(60)
            .build();

    @BeforeEach
    void setUp() {
        given(configuration.getConfigData(ClprConfig.class)).willReturn(DEFAULT_CLPR_CONFIG);
        subject = new ClprServiceApiImpl(
                channelReadStore, channelWriteStore, connectorStore, configStore, messageQueueStore, configuration);
    }

    @Test
    @DisplayName("should reject when CLPR is disabled")
    void rejectsWhenClprDisabled() {
        given(configuration.getConfigData(ClprConfig.class))
                .willReturn(ClprConfigBuilder.newBuilder().enabled(false).build());

        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("should reject when channel is not found")
    void rejectsWhenChannelNotFound() {
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(null);

        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CHANNEL_NOT_FOUND));
    }

    @Test
    @DisplayName("should reject when channel status is not ACTIVE")
    void rejectsWhenChannelNotActive() {
        final var pendingChannel = createChannel(ClprChannelStatus.PENDING);
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(pendingChannel);

        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CHANNEL_STATUS));
    }

    @Test
    @DisplayName("should reject when connector is not found")
    void rejectsWhenConnectorNotFound() {
        final var channel = createChannel(ClprChannelStatus.ACTIVE);
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 100));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(null);

        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CONNECTOR_NOT_FOUND));
    }

    @Test
    @DisplayName("should reject when payload exceeds maximum size")
    void rejectsWhenPayloadTooLarge() {
        final var channel = createChannel(ClprChannelStatus.ACTIVE);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 3, 100));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        // MESSAGE_DATA is 5 bytes, max is 3
        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_PAYLOAD_TOO_LARGE));
    }

    @Test
    @DisplayName("should validate payload size against peer's limit, not local")
    void rejectsWhenPayloadExceedsPeerLimit() {
        // Local config allows 1000 bytes; peer only allows 10. An 11-byte payload must be rejected.
        final var peerThrottles = ClprThrottles.newBuilder()
                .maxMessagePayloadBytes(10)
                .maxQueueDepth(100)
                .build();
        final var channel = ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .chainId("hedera:testnet")
                .serviceAddress(Bytes.EMPTY)
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(0)
                .ackedMessageId(0)
                .sentRunningHash(INITIAL_RUNNING_HASH)
                .lastConfigTimestamp(CONFIG_TIMESTAMP)
                .peerThrottles(peerThrottles)
                .build();
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1000, 100));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        final var elevenBytes = Bytes.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});
        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, elevenBytes))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_PAYLOAD_TOO_LARGE));
    }

    @Test
    @DisplayName("should reject when queue is full")
    void rejectsWhenQueueFull() {
        final var channel = createChannelWithQueue(ClprChannelStatus.ACTIVE, 10, 0);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 10));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        // nextMessageId(10) - ackedMessageId(0) = 10 >= maxQueueDepth(10)
        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_QUEUE_FULL));
    }

    @Test
    @DisplayName("should enqueue message and return assigned message ID on happy path")
    void happyPathEnqueuesMessageAndReturnsId() {
        final var channel = createChannelWithQueue(ClprChannelStatus.ACTIVE, 5, 3);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 100));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        final var result = subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA);

        // Should return the current nextMessageId (5)
        assertThat(result).isEqualTo(5L);

        // Should store exactly one message (no config update since timestamps match)
        verify(messageQueueStore, times(1)).put(eq(CHANNEL_ID), eq(5L), any(ClprMessageValue.class));

        // Should update channel with incremented nextMessageId and new running hash
        final var channelCaptor = ArgumentCaptor.forClass(ClprChannel.class);
        verify(channelWriteStore).put(channelCaptor.capture());
        final var updated = channelCaptor.getValue();
        assertThat(updated.nextMessageId()).isEqualTo(6L);
        assertThat(updated.sentRunningHash()).isNotEqualTo(INITIAL_RUNNING_HASH);
        assertThat(updated.sentRunningHash().length()).isEqualTo(32); // SHA-256 output
    }

    @Test
    @DisplayName("should store sender from parameter, not extract it from messageData bytes")
    void senderIsStampedFromCaller() {
        final var channel = createChannelWithQueue(ClprChannelStatus.ACTIVE, 0, 0);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 100));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        final var customSender =
                Bytes.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20});
        subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, customSender, MESSAGE_DATA);

        // The message is stored — we verify indirectly through the stored value's payload
        final var valueCaptor = ArgumentCaptor.forClass(ClprMessageValue.class);
        verify(messageQueueStore).put(eq(CHANNEL_ID), eq(0L), valueCaptor.capture());
        final var storedValue = valueCaptor.getValue();
        assertThat(storedValue.payload().message().sender()).isEqualTo(customSender);
    }

    @Test
    @DisplayName("should enqueue config update when channel config is stale")
    void enqueuesConfigUpdateWhenStale() {
        final var staleTimestamp = Timestamp.newBuilder().seconds(500).nanos(0).build();
        final var channel = createChannelWithTimestamp(ClprChannelStatus.ACTIVE, 5, 3, staleTimestamp);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 100));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        final var result = subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA);

        // Config update gets messageId 5, data message gets messageId 6
        assertThat(result).isEqualTo(6L);

        // Two messages stored: config update at 5, data message at 6
        verify(messageQueueStore, times(2)).put(eq(CHANNEL_ID), any(Long.class), any(ClprMessageValue.class));
        verify(messageQueueStore).put(eq(CHANNEL_ID), eq(5L), any(ClprMessageValue.class));
        verify(messageQueueStore).put(eq(CHANNEL_ID), eq(6L), any(ClprMessageValue.class));

        // Channel updated with nextMessageId = 7, and lastConfigTimestamp updated
        final var channelCaptor = ArgumentCaptor.forClass(ClprChannel.class);
        verify(channelWriteStore).put(channelCaptor.capture());
        final var updated = channelCaptor.getValue();
        assertThat(updated.nextMessageId()).isEqualTo(7L);
        assertThat(updated.lastConfigTimestamp()).isEqualTo(CONFIG_TIMESTAMP);
    }

    @Test
    @DisplayName("should not enqueue config update when channel config is current")
    void doesNotEnqueueConfigUpdateWhenCurrent() {
        // Same timestamp — not stale
        final var channel = createChannelWithTimestamp(ClprChannelStatus.ACTIVE, 5, 3, CONFIG_TIMESTAMP);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 100));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        final var result = subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA);

        // Only the data message at messageId 5
        assertThat(result).isEqualTo(5L);
        verify(messageQueueStore, times(1)).put(eq(CHANNEL_ID), eq(5L), any(ClprMessageValue.class));
    }

    @Test
    @DisplayName("should compute correct running hash chain")
    void runningHashChainIsCorrect() throws Exception {
        final var channel = createChannelWithQueue(ClprChannelStatus.ACTIVE, 0, 0);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 100));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA);

        // Verify the stored message's running hash matches the channel's updated hash
        final var valueCaptor = ArgumentCaptor.forClass(ClprMessageValue.class);
        verify(messageQueueStore).put(eq(CHANNEL_ID), eq(0L), valueCaptor.capture());
        final var storedValue = valueCaptor.getValue();

        final var channelCaptor = ArgumentCaptor.forClass(ClprChannel.class);
        verify(channelWriteStore).put(channelCaptor.capture());
        final var updated = channelCaptor.getValue();

        // Consistency: stored message hash == channel's updated sentRunningHash
        assertThat(storedValue.runningHashAfterProcessing()).isEqualTo(updated.sentRunningHash());
        // And it should be a 32-byte SHA-256 hash
        assertThat(storedValue.runningHashAfterProcessing().length()).isEqualTo(32);

        // Formula pin (QUEUE-H-01): SHA-256(prevHash || SHA-256(protobuf-serialized payload)).
        final var expectedPayload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .connectorId(SOURCE_CONNECTOR_ADDRESS)
                        .targetApplication(TARGET_APP)
                        .sender(SENDER)
                        .messageData(MESSAGE_DATA)
                        .build())
                .build();
        final var payloadHash = MessageDigest.getInstance("SHA-256")
                .digest(ClprMessagePayload.PROTOBUF.toBytes(expectedPayload).toByteArray());
        final var outer = MessageDigest.getInstance("SHA-256");
        outer.update(INITIAL_RUNNING_HASH.toByteArray());
        outer.update(payloadHash);
        assertThat(storedValue.runningHashAfterProcessing()).isEqualTo(Bytes.wrap(outer.digest()));
    }

    @Test
    @DisplayName("should reject PAUSED channel status")
    void rejectsPausedChannel() {
        final var channel = createChannel(ClprChannelStatus.PAUSED);
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);

        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CHANNEL_STATUS));
    }

    @Test
    @DisplayName("should reject CLOSING channel status")
    void rejectsClosingChannel() {
        final var channel = createChannel(ClprChannelStatus.CLOSING);
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);

        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CHANNEL_STATUS));
    }

    @Test
    @DisplayName("should reject DRAINED channel status")
    void rejectsDrainedChannel() {
        final var channel = createChannel(ClprChannelStatus.DRAINED);
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);

        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CHANNEL_STATUS));
    }

    @Test
    @DisplayName("should reject CLOSED channel status")
    void rejectsClosedChannel() {
        final var channel = createChannel(ClprChannelStatus.CLOSED);
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);

        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CHANNEL_STATUS));
    }

    @Test
    @DisplayName("should allow message when queue has room at boundary")
    void allowsMessageWhenQueueHasRoom() {
        // nextMessageId(9) - ackedMessageId(0) = 9 < maxQueueDepth(10) — should succeed
        final var channel = createChannelWithQueue(ClprChannelStatus.ACTIVE, 9, 0);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 10));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        final var result = subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA);
        assertThat(result).isEqualTo(9L);
    }

    @Test
    @DisplayName("stale config: should reject when queueDepth = maxQueueDepth-1 — control update needs the last slot")
    void rejectsWhenQueueAlmostFullWithStaleConfig() {
        // queueDepth(9) + reservedSlots(1) = 10 >= maxQueueDepth(10) → QUEUE_FULL
        final var staleTimestamp = Timestamp.newBuilder().seconds(500).nanos(0).build();
        final var channel = createChannelWithTimestamp(ClprChannelStatus.ACTIVE, 9, 0, staleTimestamp);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 10));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_QUEUE_FULL));
    }

    @Test
    @DisplayName("stale config: should allow when queueDepth = maxQueueDepth-2 — room for both control and data")
    void allowsWhenQueueHasRoomForStaleConfigUpdate() {
        // queueDepth(8) + reservedSlots(1) = 9 < maxQueueDepth(10) → ALLOW; control at 8, data at 9
        final var staleTimestamp = Timestamp.newBuilder().seconds(500).nanos(0).build();
        final var channel = createChannelWithTimestamp(ClprChannelStatus.ACTIVE, 8, 0, staleTimestamp);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 10));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        final var result = subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA);
        assertThat(result).isEqualTo(9L);
    }

    @Test
    @DisplayName("should preserve all channel fields when updating")
    void preservesChannelFieldsOnUpdate() {
        final var channel = ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .chainId("hedera:mainnet")
                .serviceAddress(Bytes.wrap(new byte[] {42}))
                .peerConfigTimestamp(Timestamp.newBuilder().seconds(999).build())
                .verifierFingerprint(Bytes.wrap(new byte[] {77}))
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(5)
                .ackedMessageId(3)
                .sentRunningHash(INITIAL_RUNNING_HASH)
                .receivedMessageId(10)
                .receivedRunningHash(Bytes.wrap(new byte[] {88}))
                .lastConfigTimestamp(CONFIG_TIMESTAMP)
                .build();
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 100));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA);

        final var channelCaptor = ArgumentCaptor.forClass(ClprChannel.class);
        verify(channelWriteStore).put(channelCaptor.capture());
        final var updated = channelCaptor.getValue();

        // All non-updated fields should be preserved
        assertThat(updated.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(updated.chainId()).isEqualTo("hedera:mainnet");
        assertThat(updated.serviceAddress()).isEqualTo(Bytes.wrap(new byte[] {42}));
        assertThat(updated.peerConfigTimestamp().seconds()).isEqualTo(999);
        assertThat(updated.verifierFingerprint()).isEqualTo(Bytes.wrap(new byte[] {77}));
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.ACTIVE);
        assertThat(updated.ackedMessageId()).isEqualTo(3L);
        assertThat(updated.receivedMessageId()).isEqualTo(10L);
        assertThat(updated.receivedRunningHash()).isEqualTo(Bytes.wrap(new byte[] {88}));

        // Updated fields
        assertThat(updated.nextMessageId()).isEqualTo(6L);
        assertThat(updated.sentRunningHash()).isNotEqualTo(INITIAL_RUNNING_HASH);
    }

    @Test
    @DisplayName("should use connector's connectorId in the stored message")
    void usesConnectorSourceAddress() {
        final var channel = createChannelWithQueue(ClprChannelStatus.ACTIVE, 0, 0);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 1024, 100));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA);

        final var valueCaptor = ArgumentCaptor.forClass(ClprMessageValue.class);
        verify(messageQueueStore).put(eq(CHANNEL_ID), eq(0L), valueCaptor.capture());
        final var storedMessage = valueCaptor.getValue().payload().message();
        assertThat(storedMessage.connectorId()).isEqualTo(SOURCE_CONNECTOR_ADDRESS);
        assertThat(storedMessage.targetApplication()).isEqualTo(TARGET_APP);
        assertThat(storedMessage.messageData()).isEqualTo(MESSAGE_DATA);
    }

    // ---- Queue monopolization protection tests (CLPR-3.5) ----

    @Test
    @DisplayName("running hash must chain through config-update control message, not skip it")
    void runningHashChainedThroughConfigUpdate() throws Exception {
        final var staleTimestamp = Timestamp.newBuilder().seconds(500).nanos(0).build();
        final var channel = createChannelWithTimestamp(ClprChannelStatus.ACTIVE, 5, 3, staleTimestamp);
        final var ledgerConfig = createLedgerConfig(CONFIG_TIMESTAMP, 1024, 100);
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(ledgerConfig);
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA);

        final var controlCaptor = ArgumentCaptor.forClass(ClprMessageValue.class);
        final var dataCaptor = ArgumentCaptor.forClass(ClprMessageValue.class);
        verify(messageQueueStore).put(eq(CHANNEL_ID), eq(5L), controlCaptor.capture());
        verify(messageQueueStore).put(eq(CHANNEL_ID), eq(6L), dataCaptor.capture());

        // Independently compute control message hash per spec §4.1:
        // SHA-256(initialHash || SHA-256(proto(controlPayload))).
        final var configUpdate =
                ClprConfigUpdate.newBuilder().configuration(ledgerConfig).build();
        final var controlPayload = ClprMessagePayload.newBuilder()
                .control(ClprControlMessage.newBuilder()
                        .configUpdate(configUpdate)
                        .build())
                .build();
        final var controlPayloadHash = MessageDigest.getInstance("SHA-256")
                .digest(ClprMessagePayload.PROTOBUF.toBytes(controlPayload).toByteArray());
        final var outerControl = MessageDigest.getInstance("SHA-256");
        outerControl.update(INITIAL_RUNNING_HASH.toByteArray());
        outerControl.update(controlPayloadHash);
        final var expectedControlHash = Bytes.wrap(outerControl.digest());

        assertThat(controlCaptor.getValue().runningHashAfterProcessing()).isEqualTo(expectedControlHash);

        // Data message hash must chain from control hash, not from INITIAL_RUNNING_HASH.
        final var dataPayload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .connectorId(SOURCE_CONNECTOR_ADDRESS)
                        .targetApplication(TARGET_APP)
                        .sender(SENDER)
                        .messageData(MESSAGE_DATA)
                        .build())
                .build();
        final var dataPayloadHash = MessageDigest.getInstance("SHA-256")
                .digest(ClprMessagePayload.PROTOBUF.toBytes(dataPayload).toByteArray());
        final var outerData = MessageDigest.getInstance("SHA-256");
        outerData.update(expectedControlHash.toByteArray());
        outerData.update(dataPayloadHash);
        final var expectedDataHash = Bytes.wrap(outerData.digest());

        assertThat(dataCaptor.getValue().runningHashAfterProcessing()).isEqualTo(expectedDataHash);

        // Explicit anti-regression: data hash must NOT be SHA-256(initialHash || SHA-256(dataPayload))
        // — that would mean the control message hash was never threaded in.
        final var wrongOuter = MessageDigest.getInstance("SHA-256");
        wrongOuter.update(INITIAL_RUNNING_HASH.toByteArray());
        wrongOuter.update(dataPayloadHash);
        assertThat(dataCaptor.getValue().runningHashAfterProcessing()).isNotEqualTo(Bytes.wrap(wrongOuter.digest()));
    }

    @Test
    @DisplayName("zero local maxMessagePayloadBytes blocks all non-empty messages when peer throttle is also unset")
    void zeroLocalPayloadLimitBlocksAllMessages() {
        // peerThrottles.maxMessagePayloadBytes = 0 (unset) → falls back to local
        // localThrottles.maxMessagePayloadBytes = 0 → effectiveMaxPayload = 0
        // MESSAGE_DATA.length() = 5 > 0 → PAYLOAD_TOO_LARGE
        // This documents a misconfiguration trap: setting localMax=0 silently kills all messaging.
        final var channel = ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .chainId("hedera:testnet")
                .serviceAddress(Bytes.EMPTY)
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(0)
                .ackedMessageId(0)
                .sentRunningHash(INITIAL_RUNNING_HASH)
                .lastConfigTimestamp(CONFIG_TIMESTAMP)
                .peerThrottles(ClprThrottles.newBuilder()
                        .maxMessagePayloadBytes(0)
                        .maxQueueDepth(100)
                        .build())
                .build();
        final var connector = createConnector();
        given(channelReadStore.getChannel(CHANNEL_ID)).willReturn(channel);
        given(configStore.getConfiguration()).willReturn(createLedgerConfig(CONFIG_TIMESTAMP, 0, 100));
        given(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_CONTRACT)))
                .willReturn(connector);

        assertThatThrownBy(() -> subject.sendMessage(CHANNEL_ID, CONNECTOR_CONTRACT, TARGET_APP, SENDER, MESSAGE_DATA))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_PAYLOAD_TOO_LARGE));
    }

    // ---- Helper methods ----

    private ClprChannel createChannel(@NonNull final ClprChannelStatus status) {
        return createChannelWithQueue(status, 0, 0);
    }

    private ClprChannel createChannelWithQueue(
            @NonNull final ClprChannelStatus status, final long nextMessageId, final long ackedMessageId) {
        return createChannelWithTimestamp(status, nextMessageId, ackedMessageId, CONFIG_TIMESTAMP);
    }

    private ClprChannel createChannelWithTimestamp(
            @NonNull final ClprChannelStatus status,
            final long nextMessageId,
            final long ackedMessageId,
            final Timestamp lastConfigTimestamp) {
        return ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .chainId("hedera:testnet")
                .serviceAddress(Bytes.EMPTY)
                .status(status)
                .nextMessageId(nextMessageId)
                .ackedMessageId(ackedMessageId)
                .sentRunningHash(INITIAL_RUNNING_HASH)
                .lastConfigTimestamp(lastConfigTimestamp)
                .build();
    }

    private ClprConnector createConnector() {
        return createConnectorWithBalance();
    }

    private ClprConnector createConnectorWithBalance() {
        return ClprConnector.newBuilder()
                .connectorId(SOURCE_CONNECTOR_ADDRESS)
                .channelId(CHANNEL_ID)
                .build();
    }

    private ClprLedgerConfiguration createLedgerConfig(
            final Timestamp timestamp, final int maxPayloadBytes, final int maxQueueDepth) {
        return ClprLedgerConfiguration.newBuilder()
                .timestamp(timestamp)
                .throttles(ClprThrottles.newBuilder()
                        .maxMessagePayloadBytes(maxPayloadBytes)
                        .maxQueueDepth(maxQueueDepth)
                        .build())
                .build();
    }
}
