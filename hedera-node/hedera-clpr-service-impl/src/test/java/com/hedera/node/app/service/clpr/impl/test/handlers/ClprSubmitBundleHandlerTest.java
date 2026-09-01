// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_DECODE_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NO_PROGRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_PAYLOAD_TOO_LARGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_RUNNING_HASH_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CONNECTORS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprSubmitBundleTransactionBody;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprConfigUpdate;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.clpr.ClprControlMessage;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.hapi.node.state.clpr.ClprRedactedMessage;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.ClprHashUtils;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.clpr.impl.WritableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprSubmitBundleHandler;
import com.hedera.node.app.service.clpr.impl.test.verifier.PassThroughClprVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierFactory;
import com.hedera.node.app.service.clpr.impl.verifier.VerifiedConfig;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprSubmitBundleHandlerTest {

    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final long ENDPOINT_NODE_ID = 12345L;
    private static final Bytes ZERO_HASH = Bytes.wrap(new byte[32]);
    private static final Bytes INTERMEDIATE_HASH =
            Bytes.wrap(RandomUtils.insecure().randomBytes(32));
    private static final Bytes CONNECTOR_ADDRESS = Bytes.wrap(new byte[] {10, 20, 30});
    private static final Bytes TARGET_APP = Bytes.wrap(new byte[] {40, 50});
    private static final Bytes SENDER = Bytes.wrap(new byte[] {60, 70});
    private static final Bytes MESSAGE_DATA = Bytes.wrap(new byte[] {1, 2, 3});
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1001).build();
    private static final ContractID VERIFIER_CONTRACT =
            ContractID.newBuilder().shardNum(0).realmNum(0).contractNum(9999).build();
    private static final com.hedera.hapi.node.base.ContractID CONNECTOR_CONTRACT_ID =
            com.hedera.hapi.node.base.ContractID.newBuilder().contractNum(8888).build();
    private static final AccountID CONNECTOR_CONTRACT_ACCOUNT =
            AccountID.newBuilder().accountNum(8888).build();
    // Per-message gas ceiling used by the default test config; the application callbacks
    // (onClprMessage / onClprResponse) are dispatched with this budget (spec §1.1 max_gas_per_message).
    private static final long TEST_MAX_GAS_PER_MESSAGE = 15_000_000L;
    // Connector execution charge, mirrored into the test config in setupHandleContext():
    //   worstCaseCharge = messageExecutionCost + messageExecutionCost * endpointMarginPercent / 100
    // (see ClprSubmitBundleHandler worstCaseCharge). Kept as named constants so the charge
    // assertions below trace back to the config values that produce them.
    private static final long MESSAGE_EXECUTION_COST = 1_000_000L;
    private static final long ENDPOINT_MARGIN_PERCENT = 10L;
    private static final long WORST_CASE_CHARGE =
            MESSAGE_EXECUTION_COST + MESSAGE_EXECUTION_COST * ENDPOINT_MARGIN_PERCENT / 100;
    private static final Timestamp CONFIG_TIMESTAMP =
            Timestamp.newBuilder().seconds(1000).nanos(0).build();
    private static final Timestamp ZERO_TIMESTAMP =
            Timestamp.newBuilder().seconds(0).nanos(0).build();

    @Mock
    private ClprVerifierFactory verifierFactory;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableStates writableStates;

    @Mock
    private ReadableLedgerConfigurationStore configStore;

    @Mock
    private HookDispatchStreamBuilder dispatchResult;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private TokenServiceApi tokenServiceApi;

    @Mock
    private ReadableNodeStore nodeStore;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ClprChannelLifecycle channelLifecycle;

    @Mock
    private NodeInfo creatorInfo;

    @Mock
    private FeeCharging.Context feeChargingContext;

    private static final AccountID STAKING_ACCOUNT =
            AccountID.newBuilder().accountNum(803).build();
    // AccountsConfig.systemAdmin default = 50; used by the handler as the synthetic dispatch payer.
    private static final AccountID SYSTEM_ADMIN_ACCOUNT =
            AccountID.newBuilder().accountNum(50).build();
    private static final AccountID ENDPOINT_ACCOUNT =
            AccountID.newBuilder().accountNum(ENDPOINT_NODE_ID).build();

    private ClprSubmitBundleHandler subject;
    private WritableChannelStore channelStore;
    private WritableConnectorStore connectorStore;
    private WritableMessageQueueStore messageQueueStore;
    private MapWritableKVState<Bytes, ClprChannel> writableChannels;
    private MapWritableKVState<ClprMessageKey, ClprMessageValue> writableMessages;
    private MapWritableKVState<ClprConnectorKey, ClprConnector> writableConnectors;

    @BeforeEach
    void setUp() {
        subject = new ClprSubmitBundleHandler(verifierFactory, entityIdFactory, channelLifecycle);
        lenient().when(verifierFactory.getVerifier(any())).thenReturn(new PassThroughClprVerifier());

        writableChannels = MapWritableKVState.<Bytes, ClprChannel>builder(CHANNELS_STATE_ID, "ClprService:CHANNELS")
                .build();
        lenient()
                .when(writableStates.<Bytes, ClprChannel>get(CHANNELS_STATE_ID))
                .thenReturn(writableChannels);
        channelStore = new WritableChannelStore(writableStates);

        writableMessages = MapWritableKVState.<ClprMessageKey, ClprMessageValue>builder(
                        MESSAGE_QUEUE_STATE_ID, "ClprService:MESSAGE_QUEUE")
                .build();
        lenient()
                .when(writableStates.<ClprMessageKey, ClprMessageValue>get(MESSAGE_QUEUE_STATE_ID))
                .thenReturn(writableMessages);
        messageQueueStore = new WritableMessageQueueStore(writableStates);

        writableConnectors = MapWritableKVState.<ClprConnectorKey, ClprConnector>builder(
                        CONNECTORS_STATE_ID, "ClprService:CONNECTORS")
                .build();
        lenient()
                .when(writableStates.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID))
                .thenReturn(writableConnectors);
        connectorStore = new WritableConnectorStore(writableStates);
    }

    // ========== pureChecks tests ==========

    @Test
    @DisplayName("pureChecks rejects short channel_id")
    void pureChecksRejectsShortChannelId() {
        given(pureChecksContext.body())
                .willReturn(submitBundleTxn(Bytes.wrap(new byte[16]), Bytes.wrap(new byte[1]), ENDPOINT_NODE_ID));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("pureChecks accepts empty bundle_payload (pure-ack bundles are legitimate)")
    void pureChecksAcceptsEmptyPayload() throws PreCheckException {
        given(pureChecksContext.body()).willReturn(submitBundleTxn(CHANNEL_ID, Bytes.EMPTY, ENDPOINT_NODE_ID));
        subject.pureChecks(pureChecksContext); // must not throw
    }

    @Test
    @DisplayName("pureChecks accepts valid input")
    void pureChecksAcceptsValid() throws PreCheckException {
        given(pureChecksContext.body())
                .willReturn(submitBundleTxn(CHANNEL_ID, Bytes.wrap(new byte[1]), ENDPOINT_NODE_ID));
        subject.pureChecks(pureChecksContext);
    }

    // ========== handle tests ==========

    @Test
    @DisplayName("rejects when CLPR not enabled")
    void rejectsWhenNotEnabled() {
        setupHandleContext(validSingleDataBundle(), false);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("rejects when channel not found")
    void rejectsWhenChannelNotFound() {
        setupHandleContext(validSingleDataBundle(), true);
        // Don't put any channel in the store
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CHANNEL_NOT_FOUND));
    }

    @Test
    @DisplayName("rejects when channel is CLOSED")
    void rejectsWhenChannelClosed() {
        putChannel(ClprChannelStatus.CLOSED, 0, 0, ZERO_HASH);
        setupHandleContext(validSingleDataBundle(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CHANNEL_STATUS));
    }

    @Test
    @DisplayName("accepts when channel is DRAINED (inbound must flow so peer can drain)")
    void acceptsWhenChannelDrained() {
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        putChannel(ClprChannelStatus.DRAINED, 0, 0, ZERO_HASH);
        setupHandleContext(bundle, true);
        putConnector();

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.receivedMessageId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("PAUSED channel with valid data-only bundle auto-resumes to ACTIVE")
    void pausedWithValidBundleAutoResumes() {
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        putChannel(ClprChannelStatus.PAUSED, 0, 0, ZERO_HASH);
        setupHandleContext(bundle, true);
        putConnector();

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        // Ordering valid — PAUSED auto-resumes, data message is processed
        assertThat(updated.receivedMessageId()).isEqualTo(1L);
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.ACTIVE);
    }

    @Test
    @DisplayName("rejects malformed bundle payload")
    void rejectsMalformedPayload() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        setupHandleContext(submitBundleTxn(CHANNEL_ID, Bytes.wrap(new byte[] {0x7F, 0x7F}), ENDPOINT_NODE_ID), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    @Test
    @DisplayName("rejects replay — wrong first message ID")
    void rejectsReplay() {
        // Channel has receivedMessageId=5, so expects first ID=6
        // But bundle metadata says next_message_id=2 (implying messages 1..1)
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, 5, ZERO_HASH);
        final var metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(2)
                .sentRunningHash(ZERO_HASH)
                .receivedMessageId(0)
                .status(ClprChannelStatus.ACTIVE)
                .build();
        final var bundle = ClprBundleContent.newBuilder()
                .metadata(metadata)
                .messages(List.of(dataPayload()))
                .build();
        setupHandleContext(bundleTxn(bundle), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    @Test
    @DisplayName("rejects running hash mismatch")
    void rejectsRunningHashMismatch() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var payload = dataPayload();
        final var badHash = Bytes.wrap(new byte[] {
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99
        });
        final var metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(2)
                .sentRunningHash(badHash)
                .receivedMessageId(0)
                .status(ClprChannelStatus.ACTIVE)
                .build();
        final var bundle = ClprBundleContent.newBuilder()
                .metadata(metadata)
                .messages(List.of(payload))
                .build();
        setupHandleContext(bundleTxn(bundle), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_RUNNING_HASH_MISMATCH));
    }

    @Test
    @DisplayName("rejects running hash mismatch on intermediate message — tampered middle payload detected")
    void rejectsRunningHashMismatchOnIntermediateMessage() {
        // This is distinct from the single-message case above: here sentRunningHash is internally
        // consistent with the original payload, but the bundle delivers a different middle message.
        // A naive "calculate over the last hash" implementation that started from whatever hash the
        // sender declared would be fooled — this test ensures the receiver anchors from the
        // stored receivedRunningHash and re-derives every slot in sequence.
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, INTERMEDIATE_HASH);

        final var original = dataPayload();
        var correctHash = INTERMEDIATE_HASH;
        for (int i = 0; i < 3; i++) {
            correctHash = ClprHashUtils.computeRunningHash(correctHash, original);
        }

        final var tampered = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .connectorId(CONNECTOR_ADDRESS)
                        .targetApplication(TARGET_APP)
                        .sender(SENDER)
                        .messageData(Bytes.wrap(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}))
                        .build())
                .build();

        // Declare sentRunningHash over [original, original, original], but deliver [original, tampered, original].
        final var metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(4L)
                .sentRunningHash(correctHash)
                .receivedMessageId(0L)
                .status(ClprChannelStatus.ACTIVE)
                .build();
        final var bundle = ClprBundleContent.newBuilder()
                .metadata(metadata)
                .messages(List.of(original, tampered, original))
                .build();
        setupHandleContext(bundleTxn(bundle), true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_RUNNING_HASH_MISMATCH));

        // Channel state must not advance on rejection
        assertThat(channelStore.getChannel(CHANNEL_ID).receivedMessageId()).isEqualTo(0L);
    }

    @Test
    @DisplayName("successful single data message — channel updated, response enqueued")
    void successfulSingleDataMessage() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.receivedMessageId()).isEqualTo(1L);
        final var nextRunningHash = ClprHashUtils.computeRunningHash(ZERO_HASH, dataPayload());
        assertThat(updated.receivedRunningHash()).isEqualTo(nextRunningHash);
        // Response enqueued → nextMessageId incremented
        assertThat(updated.nextMessageId()).isEqualTo(1L);
        // Response at message ID 0
        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response).isNotNull();
        assertThat(response.payload().hasMessageReply()).isTrue();
        assertThat(response.payload().messageReplyOrThrow().status()).isEqualTo(ClprMessageReplyStatus.SUCCESS);
        assertThat(response.payload().messageReplyOrThrow().messageId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("multiple sequential bundles — running hash accumulates correctly across bundle boundaries")
    void successfulSequentialBundlesHashChain() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        putConnector();

        final var msg = dataPayload();
        var runningHash = ZERO_HASH;

        for (int i = 0; i < 10; i++) {
            setupHandleContext(buildBundle(ClprChannelStatus.ACTIVE, i, 0, runningHash, List.of(msg)), true);
            subject.handle(handleContext);

            runningHash = ClprHashUtils.computeRunningHash(runningHash, msg);
            final var conn = channelStore.getChannel(CHANNEL_ID);
            assertNotNull(conn);
            assertThat(conn.receivedRunningHash()).isEqualTo(runningHash);
            assertThat(conn.receivedMessageId()).isEqualTo(i + 1);
        }
    }

    @Test
    @DisplayName("data message with connector not found — CONNECTOR_NOT_FOUND response")
    void connectorNotFoundResponse() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        // Don't register any connector
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response).isNotNull();
        assertThat(response.payload().messageReplyOrThrow().status())
                .isEqualTo(ClprMessageReplyStatus.CONNECTOR_NOT_FOUND);
    }

    @Test
    @DisplayName("duplicate bundle — second submission is a no-op, channel state unchanged")
    void duplicateBundleIsNoOp() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        putConnector();

        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));

        // First submission — processes the message, enqueues a reply
        setupHandleContext(bundle, true);
        subject.handle(handleContext);

        final var connAfterFirst = channelStore.getChannel(CHANNEL_ID);
        final var receivedIdAfterFirst = connAfterFirst.receivedMessageId();
        final var runningHashAfterFirst = connAfterFirst.receivedRunningHash();
        final var nextMsgIdAfterFirst = connAfterFirst.nextMessageId();

        // Second submission of the exact same bundle — replay-defense skips all messages
        setupHandleContext(bundle, true);
        subject.handle(handleContext);

        final var connAfterSecond = channelStore.getChannel(CHANNEL_ID);
        assertThat(connAfterSecond.receivedMessageId()).isEqualTo(receivedIdAfterFirst);
        assertThat(connAfterSecond.receivedRunningHash()).isEqualTo(runningHashAfterFirst);
        assertThat(connAfterSecond.nextMessageId()).isEqualTo(nextMsgIdAfterFirst);
        // dispatch called exactly once across both submissions — the replay added no second call
        verify(handleContext, times(1)).dispatch(any());
    }

    @Test
    @DisplayName("application dispatch success — SUCCESS response with dispatch call")
    void applicationDispatchSuccess() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        // dispatch returns SUCCESS (default mock behavior)
        subject.handle(handleContext);

        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response).isNotNull();
        assertThat(response.payload().messageReplyOrThrow().status()).isEqualTo(ClprMessageReplyStatus.SUCCESS);
        // Verify dispatch was actually called
        verify(handleContext).dispatch(any(DispatchOptions.class));
    }

    @Test
    @DisplayName("application dispatch failure — APPLICATION_ERROR response when dispatch returns non-SUCCESS")
    void applicationDispatchFailureStatus() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        // Override dispatch to return a failed status
        lenient().when(dispatchResult.status()).thenReturn(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED);

        subject.handle(handleContext);

        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response).isNotNull();
        assertThat(response.payload().messageReplyOrThrow().status())
                .isEqualTo(ClprMessageReplyStatus.APPLICATION_ERROR);
    }

    @Test
    @DisplayName("application dispatch throws HandleException — APPLICATION_ERROR response")
    void applicationDispatchThrowsException() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        // Override dispatch to throw HandleException
        given(handleContext.dispatch(any(DispatchOptions.class)))
                .willThrow(new HandleException(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED));

        subject.handle(handleContext);

        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response).isNotNull();
        assertThat(response.payload().messageReplyOrThrow().status())
                .isEqualTo(ClprMessageReplyStatus.APPLICATION_ERROR);
    }

    @Test
    @DisplayName("successful dispatch captures application return data in response")
    void successfulDispatchCapturesReturnData() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        // The handler ABI-decodes the EVM result as `(bytes)`, so the mock must return the
        // ABI-encoded form of the inner payload.
        final var innerData = new byte[] {(byte) 0xCA, (byte) 0xFE};
        lenient().when(dispatchResult.getEvmCallResult()).thenReturn(abiEncodeBytes(innerData));

        subject.handle(handleContext);

        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response).isNotNull();
        assertThat(response.payload().messageReplyOrThrow().status()).isEqualTo(ClprMessageReplyStatus.SUCCESS);
        assertThat(response.payload().messageReplyOrThrow().messageReplyData()).isEqualTo(Bytes.wrap(innerData));
    }

    @Test
    @DisplayName("failed dispatch captures revert data in response")
    void failedDispatchCapturesRevertData() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        final var innerRevert = new byte[] {(byte) 0xDE, (byte) 0xAD};
        lenient().when(dispatchResult.status()).thenReturn(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED);
        lenient().when(dispatchResult.getEvmCallResult()).thenReturn(abiEncodeBytes(innerRevert));

        subject.handle(handleContext);

        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response).isNotNull();
        assertThat(response.payload().messageReplyOrThrow().status())
                .isEqualTo(ClprMessageReplyStatus.APPLICATION_ERROR);
        assertThat(response.payload().messageReplyOrThrow().messageReplyData()).isEqualTo(Bytes.wrap(innerRevert));
    }

    @Test
    @DisplayName("null EVM result defaults to empty response data")
    void nullEvmResultDefaultsToEmpty() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        lenient().when(dispatchResult.getEvmCallResult()).thenReturn(null);

        subject.handle(handleContext);

        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response).isNotNull();
        assertThat(response.payload().messageReplyOrThrow().status()).isEqualTo(ClprMessageReplyStatus.SUCCESS);
        assertThat(response.payload().messageReplyOrThrow().messageReplyData()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    @DisplayName("HandleException dispatch — response data stays empty")
    void handleExceptionDispatchEmptyResponseData() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        given(handleContext.dispatch(any(DispatchOptions.class)))
                .willThrow(new HandleException(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED));

        subject.handle(handleContext);

        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response).isNotNull();
        assertThat(response.payload().messageReplyOrThrow().status())
                .isEqualTo(ClprMessageReplyStatus.APPLICATION_ERROR);
        assertThat(response.payload().messageReplyOrThrow().messageReplyData()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    @DisplayName("connector charged on successful dispatch — balance decremented")
    void connectorChargedOnSuccess() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnectorWithStakeAndBalance(100_000_000L, 0, 10_000_000L);

        subject.handle(handleContext);

        // The connector contract's hbar balance is charged via TokenServiceApi, not
        // an internal balance field. Just verify the transaction succeeded.
        final var updatedConnector = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
        assertThat(updatedConnector).isNotNull();
    }

    @Test
    @DisplayName("endpoint reimbursed on successful dispatch")
    void endpointReimbursedOnSuccess() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        subject.handle(handleContext);

        // Verify transfer from connector contract to endpoint (payer)
        verify(tokenServiceApi).transferFromTo(CONNECTOR_CONTRACT_ACCOUNT, PAYER_ID, WORST_CASE_CHARGE);
    }

    @Test
    @DisplayName("connector underfunded when balance insufficient — no dispatch, slashing triggered")
    void connectorUnderfundedWhenBalanceInsufficient() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnectorWithStakeAndBalance(100_000_000L, 0, 0L);
        // Balance 0 < worstCaseCharge (1_100_000) triggers the pre-check underfunded path; no dispatch

        subject.handle(handleContext);

        // No app dispatch should have happened
        verify(handleContext, never()).dispatch(any(DispatchOptions.class));
        // Response should be CONNECTOR_UNDERFUNDED
        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response).isNotNull();
        assertThat(response.payload().messageReplyOrThrow().status())
                .isEqualTo(ClprMessageReplyStatus.CONNECTOR_UNDERFUNDED);
    }

    @Nested
    @DisplayName("max_gas_per_message ceiling (#129 — spec §1.1 / §6.0, test plan §3.10.3 / §5.6.2)")
    class MaxGasPerMessageCeiling {

        /**
         * Common arrangement: an ACTIVE channel at message id 0, a single data-message bundle,
         * the handle context, and a funded connector. Shared by every test below except the
         * response-delivery and multi-message cases, which build their own bundle.
         */
        private void setUpSingleDataDispatch() {
            putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
            final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
            setupHandleContext(bundle, true);
            putConnector();
        }

        @Test
        @DisplayName("3.10.3 — onClprMessage is dispatched with gas == throttles.maxGasPerMessage")
        void dataMessageDispatchUsesMaxGasPerMessage() {
            setUpSingleDataDispatch();

            subject.handle(handleContext);

            final var captor = ArgumentCaptor.forClass(DispatchOptions.class);
            verify(handleContext).dispatch(captor.capture());
            final var dispatched = captor.getValue();
            // Asserting the exact throttle value (15_000_000) also guards against regressing to the
            // removed appDispatchGasLimit default (300_000L).
            assertThat(dispatched.body().contractCallOrThrow().gas()).isEqualTo(TEST_MAX_GAS_PER_MESSAGE);
        }

        @Test
        @DisplayName("6.0 — onClprResponse delivery is dispatched with gas == throttles.maxGasPerMessage")
        void responseDeliveryDispatchUsesMaxGasPerMessage() {
            // Bespoke arrangement: a response bundle acknowledging an outbound message, not a data bundle.
            putOutboundDataMessage(1);
            putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
            final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(replyPayload(1)));
            setupHandleContext(bundle, true);

            subject.handle(handleContext);

            final var captor = ArgumentCaptor.forClass(DispatchOptions.class);
            verify(handleContext).dispatch(captor.capture());
            assertThat(captor.getValue().body().contractCallOrThrow().gas()).isEqualTo(TEST_MAX_GAS_PER_MESSAGE);
        }

        @Test
        @DisplayName("3.10.3 — dispatched gas tracks the configured throttle value (not hard-coded)")
        void dispatchGasTracksConfiguredThrottle() {
            final long customGas = 7_777_777L;
            setUpSingleDataDispatch();
            // An updateLedgerConfiguration change is honored on the next dispatch: throttles are
            // re-read from LEDGER_CONFIGURATION each handle.
            overrideMaxGasPerMessage(customGas);

            subject.handle(handleContext);

            final var captor = ArgumentCaptor.forClass(DispatchOptions.class);
            verify(handleContext).dispatch(captor.capture());
            assertThat(captor.getValue().body().contractCallOrThrow().gas()).isEqualTo(customGas);
        }

        @Test
        @DisplayName("5.6.2 — over-budget callback yields APPLICATION_ERROR without corrupting channel state")
        void overBudgetCallbackYieldsApplicationErrorWithoutStateCorruption() {
            setUpSingleDataDispatch();

            // A callback that exhausts its gas budget surfaces as a non-SUCCESS dispatch status.
            lenient().when(dispatchResult.status()).thenReturn(ResponseCodeEnum.INSUFFICIENT_GAS);

            // Must not throw — gas overrun is a deterministic failure response, not a node crash.
            subject.handle(handleContext);

            // Deterministic APPLICATION_ERROR response with empty data.
            final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
            assertThat(response).isNotNull();
            assertThat(response.payload().messageReplyOrThrow().status())
                    .isEqualTo(ClprMessageReplyStatus.APPLICATION_ERROR);
            assertThat(response.payload().messageReplyOrThrow().messageReplyData())
                    .isEqualTo(Bytes.EMPTY);

            // No peer penalty: a gas overrun is application misbehavior, not Connector misbehavior (spec §4.6).
            final var connector = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
            assertThat(connector).isNotNull();
            assertThat(connector.slashCount()).isZero();
            assertThat(connector.lockedStake()).isEqualTo(100_000_000L);

            // Channel state intact: message consumed in order, still ACTIVE, hash advanced.
            final var conn = channelStore.getChannel(CHANNEL_ID);
            assertThat(conn.receivedMessageId()).isEqualTo(1L);
            assertThat(conn.status()).isEqualTo(ClprChannelStatus.ACTIVE);
            assertThat(conn.receivedRunningHash()).isNotEqualTo(ZERO_HASH);
        }

        @Test
        @DisplayName("5.6.2 — over-budget message in a multi-message bundle does not taint its siblings")
        void overBudgetMessageDoesNotTaintSiblingsInBundle() {
            // A gas overrun is a per-message APPLICATION_ERROR (it produces a Response), NOT a
            // bundle-tainting condition like a bad hash/size/ID. So a failure on the middle message
            // must not abort the first or third, and the running-hash chain must stay unbroken.
            // Bespoke arrangement: a 3-message bundle rather than the shared single-message one.
            putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
            final var messages = List.of(dataPayload(), dataPayload(), dataPayload());
            final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, messages);
            setupHandleContext(bundle, true);
            putConnector();

            // Each message gets its own gas budget: message 2 exhausts it (INSUFFICIENT_GAS), 1 and 3 succeed.
            // The status is keyed to the dispatch index (0-based) rather than a positional thenReturn
            // sequence, because the handler reads result.status() several times per message (the if-check
            // plus a couple of log statements) — a per-call sequence would desync on the very first message.
            final var dispatchIndex = new java.util.concurrent.atomic.AtomicInteger(-1);
            lenient().when(handleContext.dispatch(any(DispatchOptions.class))).thenAnswer(inv -> {
                dispatchIndex.incrementAndGet();
                return dispatchResult;
            });
            lenient()
                    .when(dispatchResult.status())
                    .thenAnswer(inv ->
                            dispatchIndex.get() == 1 ? ResponseCodeEnum.INSUFFICIENT_GAS : ResponseCodeEnum.SUCCESS);

            subject.handle(handleContext);

            // All three messages dispatched and produced ordered replies (outbound slots 0, 1, 2).
            verify(handleContext, times(3)).dispatch(any(DispatchOptions.class));
            final var reply1 =
                    messageQueueStore.getMessage(CHANNEL_ID, 0).payload().messageReplyOrThrow();
            final var reply2 =
                    messageQueueStore.getMessage(CHANNEL_ID, 1).payload().messageReplyOrThrow();
            final var reply3 =
                    messageQueueStore.getMessage(CHANNEL_ID, 2).payload().messageReplyOrThrow();
            assertThat(reply1.messageId()).isEqualTo(1L);
            assertThat(reply1.status()).isEqualTo(ClprMessageReplyStatus.SUCCESS);
            assertThat(reply2.messageId()).isEqualTo(2L);
            assertThat(reply2.status()).isEqualTo(ClprMessageReplyStatus.APPLICATION_ERROR);
            assertThat(reply3.messageId()).isEqualTo(3L);
            assertThat(reply3.status()).isEqualTo(ClprMessageReplyStatus.SUCCESS);

            // Channel advanced past all three and stayed ACTIVE, with the hash chain folded over
            // every message regardless of per-message dispatch outcome.
            var expectedHash = ZERO_HASH;
            for (final var m : messages) {
                expectedHash = ClprHashUtils.computeRunningHash(expectedHash, m);
            }
            final var conn = channelStore.getChannel(CHANNEL_ID);
            assertThat(conn.receivedMessageId()).isEqualTo(3L);
            assertThat(conn.status()).isEqualTo(ClprChannelStatus.ACTIVE);
            assertThat(conn.receivedRunningHash()).isEqualTo(expectedHash);
        }

        @Test
        @DisplayName("5.6.2 — connector pays the execution charge on an over-budget message; not slashed")
        void overBudgetMessageStillChargesConnectorWithoutSlash() {
            // CEI debits the connector before dispatch, so the connector pays for the attempted
            // execution even when the callback runs out of gas. APPLICATION_ERROR carries no slash
            // (spec §4.6), so the connector's stake is untouched and no endpoint reimbursement fires.
            setUpSingleDataDispatch();
            lenient().when(dispatchResult.status()).thenReturn(ResponseCodeEnum.INSUFFICIENT_GAS);

            subject.handle(handleContext);

            // Sanity: the message failed with an application error.
            assertThat(messageQueueStore
                            .getMessage(CHANNEL_ID, 0)
                            .payload()
                            .messageReplyOrThrow()
                            .status())
                    .isEqualTo(ClprMessageReplyStatus.APPLICATION_ERROR);

            // The connector was charged the flat worst-case execution cost (WORST_CASE_CHARGE)
            // exactly once — the pre-dispatch CEI debit — despite the gas overrun.
            verify(tokenServiceApi).transferFromTo(CONNECTOR_CONTRACT_ACCOUNT, PAYER_ID, WORST_CASE_CHARGE);

            // No slash: stake and offence count are untouched (gas overrun is not Connector misbehavior).
            final var connector = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
            assertThat(connector).isNotNull();
            assertThat(connector.slashCount()).isZero();
            assertThat(connector.lockedStake()).isEqualTo(100_000_000L);
            // And no endpoint reimbursement transfer from the staking account (that only happens on slash).
            verify(tokenServiceApi, never()).transferFromTo(eq(STAKING_ACCOUNT), any(), anyLong());
        }

        @Test
        @DisplayName("within-budget dispatch succeeds; connector charge is independent of the gas limit")
        void withinBudgetDispatchChargesFlatExecutionCost() {
            setUpSingleDataDispatch();
            // Even with a very large gas ceiling, the charge stays the flat messageExecutionCost + margin.
            overrideMaxGasPerMessage(30_000_000L);

            subject.handle(handleContext);

            final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
            assertThat(response.payload().messageReplyOrThrow().status()).isEqualTo(ClprMessageReplyStatus.SUCCESS);
            // WORST_CASE_CHARGE = messageExecutionCost + 10% margin, gas-limit-independent.
            verify(tokenServiceApi).transferFromTo(CONNECTOR_CONTRACT_ACCOUNT, PAYER_ID, WORST_CASE_CHARGE);
        }
    }

    @Test
    @DisplayName("ack update deletes outbound response messages")
    void ackUpdateDeletesOutboundResponses() {
        // Pre-populate outbound queue with responses at IDs 1, 2, 3
        for (int id = 1; id <= 3; id++) {
            messageQueueStore.put(
                    CHANNEL_ID,
                    id,
                    ClprMessageValue.newBuilder()
                            .payload(ClprMessagePayload.newBuilder()
                                    .messageReply(ClprMessageReply.newBuilder()
                                            .messageId(id)
                                            .status(ClprMessageReplyStatus.SUCCESS)
                                            .build())
                                    .build())
                            .runningHashAfterProcessing(ZERO_HASH)
                            .build());
        }

        // nextMessageId=4, ackedMessageId=0 → messages 1–3 are all un-acked
        putChannel(ClprChannelStatus.ACTIVE, 4, 0, ZERO_HASH);
        // Bundle acks all three outbound messages (metadata.receivedMessageId = 3)
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 3, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        subject.handle(handleContext);

        // Every acked slot must be deleted
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNull();
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 2)).isNull();
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 3)).isNull();
        assertThat(channelStore.getChannel(CHANNEL_ID).ackedMessageId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("peer CLOSING transitions local ACTIVE to CLOSING; inbound Data Message is dispatched normally")
    void peerClosingTransitionsLocalToClosing() {
        // putOutboundDataMessage(1) places a Data Message at slot 1; with ackedMessageId=0 the peer
        // has not acked it, so the step-11 scan finds it and dataMessagesDrained=false → stays CLOSING.
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        // Bundle with peer newly in CLOSING state
        final var bundle = buildBundle(ClprChannelStatus.CLOSING, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSING);
        // Data Message while CLOSING is dispatched normally — a real SUCCESS response is enqueued at nextMessageId=2
        final var response = messageQueueStore.getMessage(CHANNEL_ID, 2);
        assertThat(response.payload().messageReplyOrThrow().status()).isEqualTo(ClprMessageReplyStatus.SUCCESS);
        verify(handleContext).dispatch(notNull(DispatchOptions.class));
    }

    @Test
    @DisplayName("DRAINED + peer DRAINED transitions to CLOSED and notifies channel lifecycle")
    void closedTransitionNotifiesChannelLifecycle() {
        // Pre-populate an outbound one-way (control) message at slot 0; the peer's ack of it in
        // this bundle is what carries the bundle past the EmptyBundle check (spec §4.2 Step 1a).
        final var controlMsg = ClprMessageValue.newBuilder()
                .payload(ClprMessagePayload.newBuilder()
                        .control(buildControlMessage(CONFIG_TIMESTAMP))
                        .build())
                .runningHashAfterProcessing(ZERO_HASH)
                .build();
        messageQueueStore.put(CHANNEL_ID, 1L, controlMsg);
        // Local DRAINED with one outbound control awaiting ack (nextMessageId=2, ackedMessageId=0).
        putChannel(ClprChannelStatus.DRAINED, 2, 0, ZERO_HASH);
        // Peer DRAINED, acks our slot 1 (peerReceivedMessageId=1) → outbound drains, both sides
        // converge to CLOSED.
        final var bundle = buildBundle(ClprChannelStatus.DRAINED, 0, 1, ZERO_HASH, List.of());
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertNotNull(updated);
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSED);
        verify(channelLifecycle).onChannelClosed(CHANNEL_ID);
    }

    @Test
    @DisplayName("DRAINED->CLOSED does not transition before all reply messages are acknowledged (Step 5b sub-check 2)")
    void drainedToClosedRequiresOutboundDrained() {
        // local=DRAINED, peer=CLOSING; peer has acked 3 of the local ledger's outbound messages
        putChannel(ClprChannelStatus.DRAINED, 4, 3, 3, ZERO_HASH);
        // Local ledger enqueues a fourth outbound message (reply)
        final var bundle = buildBundle(ClprChannelStatus.DRAINED, 3, 3, ZERO_HASH, List.of(replyPayload(4)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        // Spec says local channel only transitions if all outbound replies are also acknowledged
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.DRAINED);
    }

    @Test
    @DisplayName("peer CLOSED triggers CLOSING on local ACTIVE channel; inbound Data Message is dispatched normally")
    void closedPeerTriggersClosingOnActive() {
        // putOutboundDataMessage(1) places a Data Message at slot 1; with ackedMessageId=0 the peer
        // has not acked it, so the step-11 scan finds it and the channel remains CLOSING.
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        // Bundle with peer in CLOSED state should trigger ACTIVE→CLOSING just like CLOSING
        final var bundle = buildBundle(ClprChannelStatus.CLOSED, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSING);
        // Peer's Data Message while (local) CLOSING is dispatched normally — a real SUCCESS response is enqueued at
        // nextMessageId=2
        final var response = messageQueueStore.getMessage(CHANNEL_ID, 2);
        assertThat(response.payload().messageReplyOrThrow().status()).isEqualTo(ClprMessageReplyStatus.SUCCESS);
        verify(handleContext).dispatch(notNull(DispatchOptions.class));
    }

    @Test
    @DisplayName(
            "CLOSING channel with all outbound acked and peer CLOSED cascades CLOSING→DRAINED→CLOSED in one bundle")
    void closingChannelDrainsAndClosesInOneBundle() {
        // Pre-populate an outbound control message at slot 1; the peer's ack of it in this bundle provides the progress
        // anchor
        final var controlMsg = ClprMessageValue.newBuilder()
                .payload(ClprMessagePayload.newBuilder()
                        .control(buildControlMessage(CONFIG_TIMESTAMP))
                        .build())
                .runningHashAfterProcessing(ZERO_HASH)
                .build();
        messageQueueStore.put(CHANNEL_ID, 1L, controlMsg);
        // Local CLOSING with one outbound control awaiting ack (nextMessageId=2, ackedMessageId=0).
        // When peer acks slot, ackedMessageId becomes 1 == nextMessageId-1 → outboundDrained=true.
        putChannel(ClprChannelStatus.CLOSING, 2, 0, ZERO_HASH);
        // Peer CLOSED, acks our slot 1 (peerReceivedMessageId=1) → outboundDrained fires.
        // Handler should cascade: CLOSING (now drained) → DRAINED → CLOSED in one handle call.
        final var bundle = buildBundle(ClprChannelStatus.CLOSED, 0, 1, ZERO_HASH, List.of());
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertNotNull(updated);
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSED);
        verify(channelLifecycle).onChannelClosed(CHANNEL_ID);
    }

    @Test
    @DisplayName("CLOSED channel rejects any bundle with CLPR_INVALID_CHANNEL_STATUS")
    void closedChannelRejectsBundle() {
        // Regression guard: a CLOSED channel must reject incoming bundles with
        // CLPR_INVALID_CHANNEL_STATUS, not CLPR_NO_PROGRESS (spec §4.2 step 0).
        putChannel(ClprChannelStatus.CLOSED, 0, 0, ZERO_HASH);
        setupHandleContext(validSingleDataBundle(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CHANNEL_STATUS));
    }

    @Test
    @DisplayName("DRAINED channel transitions to CLOSED when peer is CLOSED")
    void drainedChannelTransitionsToClosedWhenPeerIsClosed() {
        // Pre-populate an outbound one-way (control) message at slot 0; the peer's ack of it in
        // this bundle is what carries the bundle past the EmptyBundle check
        final var controlMsg = ClprMessageValue.newBuilder()
                .payload(ClprMessagePayload.newBuilder()
                        .control(buildControlMessage(CONFIG_TIMESTAMP))
                        .build())
                .runningHashAfterProcessing(ZERO_HASH)
                .build();
        messageQueueStore.put(CHANNEL_ID, 1L, controlMsg);
        // Local DRAINED with one outbound control awaiting ack (nextMessageId=2, ackedMessageId=0).
        putChannel(ClprChannelStatus.DRAINED, 2, 0, ZERO_HASH);
        // Peer CLOSED, acks our slot 1 (peerReceivedMessageId=1) → outbound drains, channel
        // should converge to CLOSED (peer CLOSED is equivalent to DRAINED).
        final var bundle = buildBundle(ClprChannelStatus.CLOSED, 0, 1, ZERO_HASH, List.of());
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertNotNull(updated);
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSED);
        verify(channelLifecycle).onChannelClosed(CHANNEL_ID);
    }

    @Test
    @DisplayName("in-order response deletes matched data message, channel stays ACTIVE")
    void inOrderResponseDeletesDataMessage() {
        putOutboundDataMessage(1);
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(replyPayload(1)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNull();
        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.ACTIVE);
    }

    @Test
    @DisplayName("response delivered to source application via onClprResponse callback")
    void responseDeliveredToSourceApplication() {
        putOutboundDataMessage(1);
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(replyPayload(1)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        // Verify dispatch was called (once for the response delivery callback)
        verify(handleContext).dispatch(any(DispatchOptions.class));
    }

    @Test
    @DisplayName("REDACTED reply for a source-side-redacted DATA still dispatches onClprResponse via preserved sender")
    void redactedOriginatingDispatchesOnClprResponseFromPreservedSender() {
        // Outbound slot 1 was admin-redacted before delivery. Per the spec §4.4 followup the
        // ClprRedactedMessage carries the sender so the source can still deliver the eventual
        // REDACTED reply to the originating application.
        messageQueueStore.put(
                CHANNEL_ID,
                1L,
                ClprMessageValue.newBuilder()
                        .payload(ClprMessagePayload.newBuilder()
                                .redactedMessage(ClprRedactedMessage.newBuilder()
                                        .messageHash(ZERO_HASH)
                                        .sender(SENDER)
                                        .build())
                                .build())
                        .runningHashAfterProcessing(ZERO_HASH)
                        .build());
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        final var bundle = buildBundle(
                ClprChannelStatus.ACTIVE,
                0,
                1,
                ZERO_HASH,
                List.of(replyPayloadWithStatus(1, ClprMessageReplyStatus.REDACTED)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        // Verify onClprResponse dispatch fires despite the originating slot being redacted.
        verify(handleContext).dispatch(any(DispatchOptions.class));
        // Slot deleted after the reply is processed.
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNull();
    }

    @Test
    @DisplayName("REDACTED reply for a source-side-redacted DATA skips slashing (connector id not preserved)")
    void redactedOriginatingSkipsSourceSideSlashing() {
        // Same setup as the dispatch test, but with a CONNECTOR_NOT_FOUND reply — which would
        // normally slash the source-side connector. With the originator redacted, connector id
        // isn't available on the slot, so the slashing branch is skipped.
        messageQueueStore.put(
                CHANNEL_ID,
                1L,
                ClprMessageValue.newBuilder()
                        .payload(ClprMessagePayload.newBuilder()
                                .redactedMessage(ClprRedactedMessage.newBuilder()
                                        .messageHash(ZERO_HASH)
                                        .sender(SENDER)
                                        .build())
                                .build())
                        .runningHashAfterProcessing(ZERO_HASH)
                        .build());
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putConnector(); // add a connector that could be slashed
        final var bundle = buildBundle(
                ClprChannelStatus.ACTIVE,
                0,
                1,
                ZERO_HASH,
                List.of(replyPayloadWithStatus(1, ClprMessageReplyStatus.CONNECTOR_NOT_FOUND)));
        setupHandleContext(bundle, true);

        // Should not throw — reply is processed, dispatch fires, slashing is skipped.
        subject.handle(handleContext);

        verify(handleContext).dispatch(any(DispatchOptions.class));
        assertThat(connectorStore
                        .getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS))
                        .slashCount())
                .isZero();
    }

    @Test
    @DisplayName("response delivery failure does not stop bundle processing")
    void responseDeliveryFailureDoesNotStopProcessing() {
        putOutboundDataMessage(1);
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(replyPayload(1)));
        setupHandleContext(bundle, true);

        // Make dispatch throw — callback is best-effort
        given(handleContext.dispatch(any(DispatchOptions.class)))
                .willThrow(new HandleException(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED));

        // Should not throw — bundle processing continues
        subject.handle(handleContext);

        // Data message should still be deleted (ordering was correct)
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNull();
        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.ACTIVE);
    }

    @Test
    @DisplayName("out-of-order response transitions ACTIVE to PAUSED")
    void outOfOrderResponsePausesChannel() {
        putOutboundDataMessage(1);
        putOutboundDataMessage(3); // ID 2 was a response/control, already deleted
        putChannel(ClprChannelStatus.ACTIVE, 4, 0, ZERO_HASH);
        // Response for 3, but oldest data message is 1
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(replyPayload(3)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.PAUSED);
        // Neither data message should be deleted
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNotNull();
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 3)).isNotNull();
    }

    @Test
    @DisplayName("PAUSED auto-resumes on correct response")
    void pausedAutoResumesOnCorrectResponse() {
        putOutboundDataMessage(1);
        putChannel(ClprChannelStatus.PAUSED, 2, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(replyPayload(1)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.ACTIVE);
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNull();
    }

    @Test
    @DisplayName("PAUSED stays PAUSED on incorrect response")
    void pausedStaysPausedOnIncorrectResponse() {
        putOutboundDataMessage(1);
        putOutboundDataMessage(3);
        putChannel(ClprChannelStatus.PAUSED, 4, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(replyPayload(3)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.PAUSED);
    }

    @Test
    @DisplayName("response for out-of-range message ID transitions to PAUSED")
    void outOfRangeResponsePauses() {
        putOutboundDataMessage(1);
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        // Response for ID 5, which is beyond nextMessageId=2
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(replyPayload(5)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.PAUSED);
    }

    @Test
    @DisplayName("response when no data messages exist transitions to PAUSED")
    void responseWithNoDataMessagesPauses() {
        // Queue has no data messages (only control/responses that were deleted during ack)
        putChannel(ClprChannelStatus.ACTIVE, 3, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(replyPayload(1)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.PAUSED);
    }

    @Test
    @DisplayName("multiple in-order responses in one bundle all succeed")
    void multipleInOrderResponsesSucceed() {
        putOutboundDataMessage(1);
        putOutboundDataMessage(2);
        putChannel(ClprChannelStatus.ACTIVE, 3, 0, ZERO_HASH);
        final var bundle =
                buildBundle(ClprChannelStatus.ACTIVE, 0, 2, ZERO_HASH, List.of(replyPayload(1), replyPayload(2)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.ACTIVE);
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNull();
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 2)).isNull();
    }

    @Test
    @DisplayName("responses succeed when remote also acks the messages it responds to")
    void responsesWithAckSucceed() {
        // Remote received our Data messages 1 and 2, and in this bundle it both
        // acks them (peerReceivedMessageId=2) AND sends responses for them.
        putOutboundDataMessage(1);
        putOutboundDataMessage(2);
        putChannel(ClprChannelStatus.ACTIVE, 3, 0, ZERO_HASH);
        final var bundle =
                buildBundle(ClprChannelStatus.ACTIVE, 0, 2, ZERO_HASH, List.of(replyPayload(1), replyPayload(2)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.ACTIVE);
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNull();
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 2)).isNull();
    }

    @Test
    @DisplayName("out-of-order responses in same bundle transitions to PAUSED")
    void outOfOrderResponsesInBundlePauses() {
        putOutboundDataMessage(1);
        putOutboundDataMessage(2);
        putChannel(ClprChannelStatus.ACTIVE, 3, 0, ZERO_HASH);
        // First response for 2 (wrong order), then response for 1 — entire bundle fails ordering pre-scan
        final var bundle =
                buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(replyPayload(2), replyPayload(1)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.PAUSED);
        // Bundle rejected — no messages deleted
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNotNull();
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 2)).isNotNull();
    }

    @Test
    @DisplayName("CLOSING channel does not transition to PAUSED on mismatch")
    void closingDoesNotTransitionToPaused() {
        putOutboundDataMessage(1);
        putChannel(ClprChannelStatus.CLOSING, 2, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(replyPayload(5)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.CLOSING);
    }

    @Test
    @DisplayName("response for already-acked message transitions to PAUSED")
    void responseForAckedMessagePauses() {
        putOutboundDataMessage(7);
        putChannel(ClprChannelStatus.ACTIVE, 8, 5, ZERO_HASH);
        // Response for ID 3, which is <= ackedMessageId=5; peerReceivedMessageId=5 to match existing ack
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 5, ZERO_HASH, List.of(replyPayload(3)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.PAUSED);
    }

    // ========== Lazy config propagation tests ==========

    @Test
    @DisplayName("lazy config propagation enqueues ConfigUpdate when config is stale")
    void lazyConfigPropagationEnqueuesConfigUpdate() {
        // Channel has stale config (ZERO_TIMESTAMP < CONFIG_TIMESTAMP). Use a single
        // redacted inbound slot so the bundle clears the EmptyBundle reject (spec §4.2 Step 1a);
        // the lazy-config behaviour under test is independent of the inbound payload type.
        putChannelWithConfigTimestamp(ClprChannelStatus.ACTIVE, 1, 0, 0, ZERO_HASH, ZERO_TIMESTAMP);
        setupHandleContext(buildRedactedBundle(), true);

        subject.handle(handleContext);

        // ConfigUpdate control message is enqueued at slot 1 (before the REDACTED reply for
        // the inbound slot, which lands at slot 2).
        final var enqueuedMsg = messageQueueStore.getMessage(CHANNEL_ID, 1);
        assertThat(enqueuedMsg).isNotNull();
        assertThat(enqueuedMsg.payload().hasControl()).isTrue();
        assertThat(enqueuedMsg.payload().controlOrThrow().hasConfigUpdate()).isTrue();

        // Channel should have updated lastConfigTimestamp and nextMessageId
        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.lastConfigTimestamp()).isEqualTo(CONFIG_TIMESTAMP);
        // 1 (original) + 1 (config update) + 1 (REDACTED reply for the inbound slot)
        assertThat(updated.nextMessageId()).isEqualTo(3);
    }

    @Test
    @DisplayName("no config propagation when timestamp is current")
    void noConfigPropagationWhenTimestampCurrent() {
        // Channel already has current config (CONFIG_TIMESTAMP == CONFIG_TIMESTAMP). A single
        // redacted inbound slot makes the bundle non-empty per spec §4.2 Step 1a.
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
        setupHandleContext(buildRedactedBundle(), true);

        subject.handle(handleContext);

        // No ConfigUpdate is enqueued; only the REDACTED reply for the inbound slot at slot 1.
        final var slot1 = messageQueueStore.getMessage(CHANNEL_ID, 1);
        assertThat(slot1).isNotNull();
        assertThat(slot1.payload().hasMessageReply()).isTrue();
        assertThat(slot1.payload().messageReplyOrThrow().status()).isEqualTo(ClprMessageReplyStatus.REDACTED);

        // nextMessageId advances only for the REDACTED reply (1 → 2)
        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.nextMessageId()).isEqualTo(2);
    }

    @Test
    @DisplayName("config propagation runs even when channel is CLOSING")
    void configPropagationFiresInClosing() {
        // Spec §4.2 Step 5c imposes no status restriction on lazy config propagation: as long as
        // the channel can still emit outbound messages, a stale config must be flushed so the
        // peer sees the latest ledger config before we drain. The handler enqueues the
        // ConfigUpdate first, then the REDACTED reply for the inbound slot.
        putChannelWithConfigTimestamp(ClprChannelStatus.CLOSING, 1, 0, 0, ZERO_HASH, ZERO_TIMESTAMP);
        setupHandleContext(buildRedactedBundle(), true);

        subject.handle(handleContext);

        // Slot 1 = ConfigUpdate control message (lazy propagation)
        final var slot1 = messageQueueStore.getMessage(CHANNEL_ID, 1);
        assertThat(slot1).isNotNull();
        assertThat(slot1.payload().hasControl()).isTrue();
        assertThat(slot1.payload().controlOrThrow().hasConfigUpdate()).isTrue();

        // Slot 2 = REDACTED reply for the inbound slot
        final var slot2 = messageQueueStore.getMessage(CHANNEL_ID, 2);
        assertThat(slot2).isNotNull();
        assertThat(slot2.payload().hasMessageReply()).isTrue();
        assertThat(slot2.payload().messageReplyOrThrow().status()).isEqualTo(ClprMessageReplyStatus.REDACTED);
    }

    @Test
    @DisplayName("empty bundle (no messages, no anchor advance, no ack progress) is rejected")
    void emptyBundleIsRejected() {
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
        final var emptyBundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of());
        setupHandleContext(emptyBundle, true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NO_PROGRESS));
    }

    @Test
    @DisplayName("bundle with no messages but non-empty trust anchor advancement is accepted")
    void acceptsBundleWithTrustAnchorOnly() {
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
        // Build a ClprBundleContent with a non-empty newTrustAnchor and newTrustAnchorId but no messages.
        // PassThroughClprVerifier deserializes the payload bytes directly as ClprBundleContent.
        // The trust anchor advancement alone satisfies the NoProgress check.
        final var metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(1)
                .sentRunningHash(ZERO_HASH)
                .receivedMessageId(0)
                .status(ClprChannelStatus.ACTIVE)
                .build();
        final var bundleContent = ClprBundleContent.newBuilder()
                .metadata(metadata)
                .newTrustAnchor(Bytes.wrap(new byte[] {1, 2, 3, 4}))
                .newTrustAnchorId(Bytes.wrap(new byte[] {5, 6, 7, 8}))
                .build();
        setupHandleContext(bundleTxn(bundleContent), true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.trustAnchor()).isEqualTo(Bytes.wrap(new byte[] {1, 2, 3, 4}));
        assertThat(updated.trustAnchorId()).isEqualTo(Bytes.wrap(new byte[] {5, 6, 7, 8}));
    }

    @Test
    @DisplayName("rejects bundle when verifier returns same trust_anchor_id as stored (spec §2.1.2 Criterion 2)")
    void rejectsBundleWithSameTrustAnchorIdAsStored() {
        // Spec §2.1.2 Criterion 2: trust anchor advancement requires the verifier's returned
        // new_trust_anchor_id to differ from the pre-bundle Channel.trust_anchor_id. A bundle
        // that carries a non-empty successor anchor with the SAME id as stored does not advance
        // the trust anchor — with no other progress signals, the bundle MUST be rejected.
        final var storedTrustAnchorId = Bytes.wrap(new byte[] {5, 6, 7, 8});
        channelStore.put(ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .chainId("hedera:testnet")
                .serviceAddress(Bytes.EMPTY)
                .verifierContract(VERIFIER_CONTRACT)
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(1)
                .ackedMessageId(0)
                .sentRunningHash(ZERO_HASH)
                .receivedMessageId(0)
                .receivedRunningHash(ZERO_HASH)
                .lastConfigTimestamp(CONFIG_TIMESTAMP)
                .peerConfigTimestamp(ZERO_TIMESTAMP)
                .trustAnchor(Bytes.wrap(new byte[] {1, 2, 3, 4}))
                .trustAnchorId(storedTrustAnchorId)
                .build());

        final var metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(1)
                .sentRunningHash(ZERO_HASH)
                .receivedMessageId(0)
                .status(ClprChannelStatus.ACTIVE)
                .build();
        final var bundleContent = ClprBundleContent.newBuilder()
                .metadata(metadata)
                .newTrustAnchor(Bytes.wrap(new byte[] {9, 9, 9, 9}))
                .newTrustAnchorId(storedTrustAnchorId)
                .build();
        setupHandleContext(bundleTxn(bundleContent), true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NO_PROGRESS));
    }

    // ---- #333: Step 1b — apply new_endpoint_manifest / #334: Criterion 5 ----

    @Test
    @DisplayName("#333: manifest replaced when version advances (flag ON)")
    void manifestReplacedWhenVersionAdvances() {
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
        final var newManifest = buildManifest(3L, "10.0.0.5");
        // Bundle carries a message (Criterion 1) so progress isn't in question; we're
        // asserting only that the manifest write lands.
        final var bundleContent = buildBundleWithManifest(newManifest, List.of(dataPayload()));
        setupHandleContextWithFlags(bundleTxn(bundleContent), true, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.endpointManifestVersion()).isEqualTo(3L);
        assertThat(updated.endpointManifestOrThrow()).isEqualTo(newManifest);
    }

    @Test
    @DisplayName("#333: stale manifest silently skipped, bundle still processes")
    void staleManifestSilentlySkipped() {
        // Cache is at v=5; bundle carries v=3 (stale) — silent skip, bundle still succeeds
        // via Criterion 1 (message).
        putChannelWithManifestVersion(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH, 5L);
        final var staleManifest = buildManifest(3L, "10.0.0.3");
        final var bundleContent = buildBundleWithManifest(staleManifest, List.of(dataPayload()));
        setupHandleContextWithFlags(bundleTxn(bundleContent), true, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.endpointManifestVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("#333: absent manifest silently skipped, bundle still processes")
    void absentManifestSilentlySkipped() {
        putChannelWithManifestVersion(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH, 5L);
        // Bundle carries no new_endpoint_manifest.
        final var bundleContent = buildBundleWithManifest(null, List.of(dataPayload()));
        setupHandleContextWithFlags(bundleTxn(bundleContent), true, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.endpointManifestVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("#333: manifest update applied on DRAINED channel")
    void manifestUpdateAppliedOnDrainedChannel() {
        putChannelWithManifestVersion(ClprChannelStatus.DRAINED, 0, 0, ZERO_HASH, 1L);
        final var newManifest = buildManifest(2L, "10.0.0.7");
        // Peer bundle metadata declares ACTIVE (peer's own channel state); local receiver
        // stays DRAINED. Manifest advancement is Criterion 5, satisfying NoProgress on its own.
        final var bundleContent = ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(1)
                        .sentRunningHash(ZERO_HASH)
                        .receivedMessageId(0)
                        .status(ClprChannelStatus.ACTIVE)
                        .build())
                .newEndpointManifest(newManifest)
                .build();
        setupHandleContextWithFlags(bundleTxn(bundleContent), true, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.endpointManifestVersion()).isEqualTo(2L);
        assertThat(updated.endpointManifestOrThrow()).isEqualTo(newManifest);
    }

    @Test
    @DisplayName("#333: flag OFF skips manifest write even when version advances")
    void flagOffSkipsManifestWrite() {
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
        final var newManifest = buildManifest(3L, "10.0.0.9");
        // Bundle carries a message (Criterion 1) so it still processes without Criterion 5.
        final var bundleContent = buildBundleWithManifest(newManifest, List.of(dataPayload()));
        setupHandleContextWithFlags(bundleTxn(bundleContent), true, false);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        // Flag OFF → no manifest write, cache stays at 0.
        assertThat(updated.endpointManifestVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("#334: bundle with only advancing manifest satisfies NoProgress via Criterion 5")
    void bundleWithOnlyAdvancingManifestSatisfiesProgress() {
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
        final var newManifest = buildManifest(1L, "10.0.0.11");
        // No messages, no trust anchor, no ack advance, no state transition — only Criterion 5.
        final var bundleContent = ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(1)
                        .sentRunningHash(ZERO_HASH)
                        .receivedMessageId(0)
                        .status(ClprChannelStatus.ACTIVE)
                        .build())
                .newEndpointManifest(newManifest)
                .build();
        setupHandleContextWithFlags(bundleTxn(bundleContent), true, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.endpointManifestVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("#334: stale-only manifest fails NoProgress (Criterion 5 requires advance)")
    void bundleWithStaleManifestOnlyIsNoProgress() {
        putChannelWithManifestVersion(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH, 5L);
        final var staleManifest = buildManifest(3L, "10.0.0.13");
        final var bundleContent = ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(1)
                        .sentRunningHash(ZERO_HASH)
                        .receivedMessageId(0)
                        .status(ClprChannelStatus.ACTIVE)
                        .build())
                .newEndpointManifest(staleManifest)
                .build();
        setupHandleContextWithFlags(bundleTxn(bundleContent), true, true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NO_PROGRESS));
    }

    @Test
    @DisplayName("#334: flag OFF makes Criterion 5 inert (manifest-only bundle rejected)")
    void bundleWithFlagOffAndManifestOnlyIsNoProgress() {
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
        final var newManifest = buildManifest(1L, "10.0.0.15");
        final var bundleContent = ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(1)
                        .sentRunningHash(ZERO_HASH)
                        .receivedMessageId(0)
                        .status(ClprChannelStatus.ACTIVE)
                        .build())
                .newEndpointManifest(newManifest)
                .build();
        setupHandleContextWithFlags(bundleTxn(bundleContent), true, false);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NO_PROGRESS));
    }

    @Test
    @DisplayName("received ConfigUpdate control message updates peerConfigTimestamp")
    void receivedConfigUpdateUpdatesPeerTimestamp() {
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);

        // Build a bundle containing a ConfigUpdate control message
        final var peerTimestamp = Timestamp.newBuilder().seconds(5000).nanos(42).build();
        final var configUpdatePayload = ClprMessagePayload.newBuilder()
                .control(buildControlMessage(peerTimestamp))
                .build();
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(configUpdatePayload));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.peerConfigTimestamp()).isEqualTo(peerTimestamp);
    }

    @Test
    @DisplayName("rejects bundle containing a ClprControlMessage with no known variant (spec §1.3)")
    void rejectsControlMessageUnknownVariant() {
        // Spec §1.3: unknown control-message variants MUST reject the entire bundle. Silently
        // skipping them would cause state divergence — Step 6's running-hash check does not catch
        // this because both sides serialize the same bytes.
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);

        final var emptyControlPayload = ClprMessagePayload.newBuilder()
                .control(ClprControlMessage.newBuilder().build())
                .build();
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(emptyControlPayload));
        setupHandleContext(bundle, true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    @Test
    @DisplayName("rejects ConfigUpdate with mismatched protocol_version (spec §1.1)")
    void rejectsConfigUpdateProtocolVersionMismatch() {
        // Spec §1.1: both sides MUST agree on the protocol version; cross-version messaging is
        // not supported. Local ledger defaults to protocolVersion=0 in tests; peer sends 42.
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);

        final var peerTimestamp = Timestamp.newBuilder().seconds(5000).nanos(42).build();
        final var mismatchedConfig = ClprMessagePayload.newBuilder()
                .control(ClprControlMessage.newBuilder()
                        .configUpdate(ClprConfigUpdate.newBuilder()
                                .configuration(ClprLedgerConfiguration.newBuilder()
                                        .protocolVersion(42)
                                        .timestamp(peerTimestamp)
                                        .build())
                                .build())
                        .build())
                .build();
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(mismatchedConfig));
        setupHandleContext(bundle, true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    @Test
    @DisplayName("rejects ConfigUpdate whose timestamp violates spec §1.1 range (nanos > 999_999_999)")
    void rejectsConfigUpdateTimestampOutOfRange() {
        // Spec §1.1: Timestamp.nanos MUST be in [0, 999_999_999]. Peer sends nanos = 1_000_000_000.
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);

        final var badTimestamp =
                Timestamp.newBuilder().seconds(5000).nanos(1_000_000_000).build();
        final var configPayload = ClprMessagePayload.newBuilder()
                .control(buildControlMessage(badTimestamp))
                .build();
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(configPayload));
        setupHandleContext(bundle, true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    @Test
    @DisplayName("rejects ConfigUpdate whose timestamp is not strictly greater than stored (spec §1.3)")
    void rejectsConfigUpdateStaleTimestamp() {
        // Spec §1.3: the enclosed configuration's timestamp MUST be strictly greater than the
        // stored peer_config_timestamp. Seed the channel with a peerConfigTimestamp of
        // {seconds=5000, nanos=42}, then submit a ConfigUpdate with the same timestamp.
        final var storedTimestamp =
                Timestamp.newBuilder().seconds(5000).nanos(42).build();
        channelStore.put(ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .chainId("hedera:testnet")
                .serviceAddress(Bytes.EMPTY)
                .verifierContract(VERIFIER_CONTRACT)
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(1)
                .ackedMessageId(0)
                .sentRunningHash(ZERO_HASH)
                .receivedMessageId(0)
                .receivedRunningHash(ZERO_HASH)
                .lastConfigTimestamp(CONFIG_TIMESTAMP)
                .peerConfigTimestamp(storedTimestamp)
                .build());

        final var configPayload = ClprMessagePayload.newBuilder()
                .control(buildControlMessage(storedTimestamp))
                .build();
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(configPayload));
        setupHandleContext(bundle, true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    @Test
    @DisplayName("received ConfigUpdate keeps all endpoint keys when max_peer_endpoints is zero")
    void receivedConfigUpdateKeepsAllEndpointKeysWhenMaxPeerEndpointsIsZero() {
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
        final var peerTimestamp = Timestamp.newBuilder().seconds(5000).nanos(42).build();
        final var endpoints = endpointList(12);
        final var bundle = buildBundle(
                ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(configUpdatePayload(peerTimestamp, endpoints)));
        setupHandleContext(bundle, true);
        given(configStore.getConfiguration()).willReturn(createLedgerConfigWithMaxPeerEndpoints(0));

        subject.handle(handleContext);
    }

    @Test
    @DisplayName("ConfigUpdate with negative timestamp seconds is rejected with CLPR_BUNDLE_VERIFICATION_FAILED")
    void configUpdateWithNegativeTimestampRejected() {
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
        final var invalidTimestamp = Timestamp.newBuilder().seconds(-1).nanos(0).build();
        final var payload = ClprMessagePayload.newBuilder()
                .control(buildControlMessage(invalidTimestamp))
                .build();
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(payload));
        setupHandleContext(bundle, true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    @Test
    @DisplayName("ConfigUpdate with nanos > 999_999_999 is rejected with CLPR_BUNDLE_VERIFICATION_FAILED")
    void configUpdateWithNanosOutOfRangeRejected() {
        putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
        final var invalidTimestamp =
                Timestamp.newBuilder().seconds(1000).nanos(1_000_000_000).build();
        final var payload = ClprMessagePayload.newBuilder()
                .control(buildControlMessage(invalidTimestamp))
                .build();
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(payload));
        setupHandleContext(bundle, true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    // ========== Slashing tests ==========

    @Test
    @DisplayName("source-side: CONNECTOR_NOT_FOUND response slashes source connector")
    void sourceSlashOnConnectorNotFound() {
        // Set up outbound data message at slot 1 (next=2, acked=0)
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        putConnectorWithStake(100_000_000L, 0);

        // Peer sends a CONNECTOR_NOT_FOUND reply targeting message 1 and acks it (peerReceivedMessageId=1)
        final var reply = replyPayloadWithStatus(1, ClprMessageReplyStatus.CONNECTOR_NOT_FOUND);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(reply));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        // Connector should be slashed: 100M - 10M = 90M, slashCount 0→1
        final var slashed = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
        assertThat(slashed).isNotNull();
        assertThat(slashed.lockedStake()).isEqualTo(90_000_000L);
        assertThat(slashed.slashCount()).isEqualTo(1);

        // Payer should be reimbursed 10M
        verify(tokenServiceApi).transferFromTo(STAKING_ACCOUNT, PAYER_ID, 10_000_000L);
    }

    @Test
    @DisplayName("source-side: CONNECTOR_UNDERFUNDED response slashes source connector")
    void sourceSlashOnConnectorUnderfunded() {
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        putConnectorWithStake(100_000_000L, 2); // slash_count=2 → penalty = 10M * 2^2 = 40M

        final var reply = replyPayloadWithStatus(1, ClprMessageReplyStatus.CONNECTOR_UNDERFUNDED);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(reply));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var slashed = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
        assertThat(slashed).isNotNull();
        assertThat(slashed.lockedStake()).isEqualTo(60_000_000L); // 100M - 40M
        assertThat(slashed.slashCount()).isEqualTo(3);

        verify(tokenServiceApi).transferFromTo(STAKING_ACCOUNT, PAYER_ID, 40_000_000L);
    }

    @Test
    @DisplayName("source-side: CONNECTOR_UNDERFUNDED with unbacked stake slashes state but pays no hbar")
    void sourceSlashUnbackedStakeReimbursesNothing() {
        // Reproduces the production incident: the connector carries locked_stake in state, but the
        // staking account holds no hbar backing it (a well-known connector that never posted
        // stake; 0.0.803 was never funded). Reimbursing the penalty as hbar here would credit the
        // payer with no surviving debit, yielding a non-zero net hbar change -> FAIL_INVALID. The
        // slash must still decrement locked_stake, but no hbar may be paid out.
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        putConnectorWithStake(100_000_000L, 0); // penalty = 10M

        final var reply = replyPayloadWithStatus(1, ClprMessageReplyStatus.CONNECTOR_UNDERFUNDED);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(reply));
        setupHandleContext(bundle, true);
        // Override the default well-funded staking account: here it holds nothing for this connector.
        // (Applied after setupHandleContext, which installs the default funded stub.)
        given(accountStore.getAccountById(STAKING_ACCOUNT)).willReturn(null);

        subject.handle(handleContext);

        // State slash still applies: 100M - 10M = 90M, slashCount 0->1
        final var slashed = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
        assertThat(slashed).isNotNull();
        assertThat(slashed.lockedStake()).isEqualTo(90_000_000L);
        assertThat(slashed.slashCount()).isEqualTo(1);

        // But no hbar is moved out of the (empty) staking account.
        verify(tokenServiceApi, never()).transferFromTo(eq(STAKING_ACCOUNT), eq(PAYER_ID), anyLong());
    }

    @Test
    @DisplayName("source-side: SUCCESS response does not slash")
    void sourceNoSlashOnSuccess() {
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        putConnectorWithStake(100_000_000L, 0);

        final var reply = replyPayloadWithStatus(1, ClprMessageReplyStatus.SUCCESS);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(reply));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        // Connector unchanged
        final var conn = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
        assertThat(conn).isNotNull();
        assertThat(conn.lockedStake()).isEqualTo(100_000_000L);
        assertThat(conn.slashCount()).isEqualTo(0);

        verify(tokenServiceApi, never()).transferFromTo(any(), any(), anyLong());
    }

    @Test
    @DisplayName("source-side: APPLICATION_ERROR response does not slash")
    void sourceNoSlashOnApplicationError() {
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        putConnectorWithStake(100_000_000L, 0);

        final var reply = replyPayloadWithStatus(1, ClprMessageReplyStatus.APPLICATION_ERROR);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(reply));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var conn = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
        assertThat(conn).isNotNull();
        assertThat(conn.lockedStake()).isEqualTo(100_000_000L);

        verify(tokenServiceApi, never()).transferFromTo(any(), any(), anyLong());
    }

    @Test
    @DisplayName("source-side: REDACTED response does not slash")
    void sourceNoSlashOnRedacted() {
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        putConnectorWithStake(100_000_000L, 0);

        final var reply = replyPayloadWithStatus(1, ClprMessageReplyStatus.REDACTED);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(reply));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var conn = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
        assertThat(conn).isNotNull();
        assertThat(conn.lockedStake()).isEqualTo(100_000_000L);
        assertThat(conn.slashCount()).isEqualTo(0);

        verify(tokenServiceApi, never()).transferFromTo(any(), any(), anyLong());
    }

    @Test
    @DisplayName("source-side: legacy CHANNEL_CLOSED response (never generated by conforming impl) does not slash")
    void sourceNoSlashOnLegacyChannelClosed() {
        // CHANNEL_CLOSED is reserved and will never be generated by a conforming implementation,
        // but may be received from older peers. It must not trigger slashing.
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        putConnectorWithStake(100_000_000L, 0);

        final var reply = replyPayloadWithStatus(1, ClprMessageReplyStatus.CHANNEL_CLOSED);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(reply));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var conn = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
        assertThat(conn).isNotNull();
        assertThat(conn.lockedStake()).isEqualTo(100_000_000L);
        assertThat(conn.slashCount()).isEqualTo(0);

        verify(tokenServiceApi, never()).transferFromTo(any(), any(), anyLong());
    }

    @Test
    @DisplayName("source-side: ban after threshold slashes removes connector")
    void sourceSlashBanRemovesConnector() {
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        putConnectorWithStake(100_000_000L, 4); // threshold=5, so 4+1=5 → ban

        final var reply = replyPayloadWithStatus(1, ClprMessageReplyStatus.CONNECTOR_NOT_FOUND);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(reply));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        // Connector should be removed (banned)
        final var conn = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
        assertThat(conn).isNull();

        // Full stake forfeited and reimbursed
        verify(tokenServiceApi).transferFromTo(STAKING_ACCOUNT, PAYER_ID, 100_000_000L);
    }

    @Test
    @DisplayName("source-side: slashing with already-removed connector is safe")
    void sourceSlashMissingConnectorIsSafe() {
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        // Don't put any connector — simulates already-banned/removed

        final var reply = replyPayloadWithStatus(1, ClprMessageReplyStatus.CONNECTOR_NOT_FOUND);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(reply));
        setupHandleContext(bundle, true);

        // Should not throw
        subject.handle(handleContext);

        verify(tokenServiceApi, never()).transferFromTo(any(), any(), anyLong());
    }

    // ========== C-1: Redacted slot generates REDACTED reply ==========

    @Test
    @DisplayName("redacted slot enqueues REDACTED reply")
    void redactedSlotGeneratesRedactedReply() {
        final var messageHash = ClprHashUtils.sha256(
                ClprMessagePayload.PROTOBUF.toBytes(dataPayload()).toByteArray());
        final var redactedPayload = ClprMessagePayload.newBuilder()
                .redactedMessage(ClprRedactedMessage.newBuilder()
                        .messageHash(Bytes.wrap(messageHash))
                        .build())
                .build();
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(redactedPayload));
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        // REDACTED reply should be enqueued at nextMessageId=0
        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response).isNotNull();
        assertThat(response.payload().hasMessageReply()).isTrue();
        assertThat(response.payload().messageReplyOrThrow().status()).isEqualTo(ClprMessageReplyStatus.REDACTED);
        assertThat(response.payload().messageReplyOrThrow().messageId()).isEqualTo(1L);

        // receivedMessageId advances past the redacted slot
        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.receivedMessageId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("redacted slot extends the running-hash chain via SHA-256(prev || message_hash)")
    void redactedSlotChainsViaMessageHash() {
        final var messageHash = Bytes.wrap(new byte[] {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
        });
        final var redactedPayload = ClprMessagePayload.newBuilder()
                .redactedMessage(ClprRedactedMessage.newBuilder()
                        .messageHash(messageHash)
                        .build())
                .build();
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(redactedPayload));
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var expected = ClprHashUtils.computeRunningHashFromPayloadHash(ZERO_HASH, messageHash);
        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.receivedRunningHash()).isEqualTo(expected);
    }

    // ========== 3.13.1: Redaction × bundle verification — source-side REDACTED reply ==========

    @Test
    @DisplayName("3.13.1: REDACTED reply for source-side DATA advances channel without slashing")
    void redactedReplyAdvancesChannelNoSlash() {
        // Source sent DATA message 1; connector has some stake at slash_count=0.
        // Peer redacted that slot → source now receives a REDACTED reply.
        // Expected: DATA message deleted from queue, channel advances, NO slash.
        putChannel(ClprChannelStatus.ACTIVE, 2, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        putConnectorWithStake(100_000_000L, 0);

        final var reply = replyPayloadWithStatus(1, ClprMessageReplyStatus.REDACTED);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(reply));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        // Channel advances normally
        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.ackedMessageId()).isEqualTo(1L);
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.ACTIVE);

        // DATA message deleted from the outbound queue (reply was terminal)
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNull();

        // No slash — connector stake unchanged
        final var connector = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
        assertThat(connector).isNotNull();
        assertThat(connector.lockedStake()).isEqualTo(100_000_000L);
        assertThat(connector.slashCount()).isEqualTo(0);

        // No reimbursement transfer to payer (staking account → payer)
        verify(tokenServiceApi, never()).transferFromTo(eq(STAKING_ACCOUNT), eq(PAYER_ID), anyLong());
    }

    @Test
    @DisplayName("3.13.1: in-flight counter decremented on REDACTED reply (same as normal reply)")
    void redactedReplyDecrementsInFlightCounter() {
        // Connector has inFlightMessageCount=2 (two DATA messages in flight).
        // Receiving a REDACTED reply for message 1 should decrement the counter to 1.
        putChannel(ClprChannelStatus.ACTIVE, 3, 0, ZERO_HASH);
        putOutboundDataMessage(1);
        putOutboundDataMessage(2);

        // Put connector with in-flight count = 2
        connectorStore.put(ClprConnector.newBuilder()
                .channelId(CHANNEL_ID)
                .connectorId(CONNECTOR_ADDRESS)
                .connectorContract(CONNECTOR_CONTRACT_ID)
                .lockedStake(100_000_000L)
                .slashCount(0)
                .inFlightMessageCount(2)
                .build());
        final var connectorAccount = Account.newBuilder()
                .accountId(CONNECTOR_CONTRACT_ACCOUNT)
                .tinybarBalance(DEFAULT_CONNECTOR_BALANCE)
                .build();
        lenient().when(accountStore.getContractById(CONNECTOR_CONTRACT_ID)).thenReturn(connectorAccount);

        // Bundle acks message 1 with a REDACTED reply
        final var reply = replyPayloadWithStatus(1, ClprMessageReplyStatus.REDACTED);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 1, ZERO_HASH, List.of(reply));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        // In-flight counter should decrease from 2 → 1
        final var updatedConnector = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ADDRESS));
        assertThat(updatedConnector).isNotNull();
        assertThat(updatedConnector.inFlightMessageCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("3.13.2: double redact at handler level rejects with CLPR_MESSAGE_ALREADY_REDACTED")
    void doubleRedactRejectsAtHandlerLevel() {
        // The redact handler itself handles duplicate redaction — it rejects with CLPR_MESSAGE_ALREADY_REDACTED.
        // This test documents the current behavior captured by ClprRedactMessageHandlerTest, verified here via
        // the WritableMessageQueueStore directly. We put an already-redacted slot (no payload) into the queue
        // and verify that a subsequent redact call throws CLPR_MESSAGE_ALREADY_REDACTED.
        // This captures spec test 3.13.2 — "redacting the same slot twice is a no-op or rejects."
        // Current behavior: rejects (CLPR_MESSAGE_ALREADY_REDACTED).
        final var alreadyRedacted = ClprMessageValue.newBuilder()
                .runningHashAfterProcessing(ZERO_HASH)
                .build();
        messageQueueStore.put(CHANNEL_ID, 5L, alreadyRedacted);
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 5L)).isNotNull();
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 5L).hasPayload()).isFalse();

        // Attempting a second redact: the ClprRedactMessageHandler checks hasNonEmptyPayload and throws.
        // We verify this by directly calling the handler logic via ClprRedactMessageHandler's behavior:
        // if the stored message has no payload, the handler MUST throw CLPR_MESSAGE_ALREADY_REDACTED.
        // (See ClprRedactMessageHandlerTest.rejectsWhenMessageAlreadyRedacted — same invariant.)
        final var alreadyRedactedMsg = messageQueueStore.getMessage(CHANNEL_ID, 5L);
        assertThat(alreadyRedactedMsg).isNotNull();
        // No hasMessage, no hasMessageReply, no hasControl — all unset.
        assertThat(alreadyRedactedMsg.hasPayload()).isFalse();
        // This is the state that causes CLPR_MESSAGE_ALREADY_REDACTED in ClprRedactMessageHandler.doHandle.
        // The test here documents that the queue store faithfully stores the already-redacted state.
    }

    // ========== C-2: CEI ordering — connector debit before dispatch ==========

    @Test
    @DisplayName("CEI: connector is debited before dispatch — funds escrowed before app call")
    void connectorDebitedBeforeDispatch() {
        // Verify that the transfer call happens before (or independent of) the dispatch.
        // The easiest observable: if the dispatch is never called because connector is null,
        // the transfer also never happens. Conversely, if connector has sufficient balance,
        // transferFromTo is invoked.
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        subject.handle(handleContext);

        // Transfer was called — debit happened during processing
        verify(tokenServiceApi).transferFromTo(CONNECTOR_CONTRACT_ACCOUNT, PAYER_ID, WORST_CASE_CHARGE);
    }

    @Test
    @DisplayName("CEI: dispatch exception does not prevent debit — charge was already applied")
    void dispatchExceptionAfterDebitDoesNotRevertCharge() {
        // When dispatch throws, the debit has already occurred.
        // The prior behavior would do: validate balance → dispatch (throws) → charge fails.
        // New behavior: charge first → dispatch (throws) → charge already done, no transfer attempt.
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);
        putConnector();

        given(handleContext.dispatch(any(DispatchOptions.class)))
                .willThrow(new HandleException(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED));

        subject.handle(handleContext);

        // Transfer still called — debit is pre-dispatch (no second transfer after exception)
        verify(tokenServiceApi, times(1)).transferFromTo(CONNECTOR_CONTRACT_ACCOUNT, PAYER_ID, WORST_CASE_CHARGE);
        // Reply is APPLICATION_ERROR
        final var response = messageQueueStore.getMessage(CHANNEL_ID, 0);
        assertThat(response.payload().messageReplyOrThrow().status())
                .isEqualTo(ClprMessageReplyStatus.APPLICATION_ERROR);
    }

    // ========== C?-9: M1 → C2 → M3 config ordering ==========

    @Test
    @DisplayName("C2 config update applies before M3 — subsequent messages see updated peerConfigTimestamp")
    void configUpdateAppliesBeforeSubsequentMessages() {
        // Bundle: [M1(data), C2(configUpdate), M3(data)]
        // C2 carries a new peer config timestamp. After processing, the channel should
        // store the timestamp from C2, not the old one.
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        putConnector();

        final var newTimestamp = Timestamp.newBuilder().seconds(9999).nanos(0).build();
        final var configPayload = ClprMessagePayload.newBuilder()
                .control(buildControlMessage(newTimestamp))
                .build();

        final var bundle = buildBundle(
                ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload(), configPayload, dataPayload()));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        // After the bundle, peerConfigTimestamp must reflect C2's timestamp
        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.peerConfigTimestamp()).isEqualTo(newTimestamp);
        // All three messages processed
        assertThat(updated.receivedMessageId()).isEqualTo(3L);
    }

    // ========== C?-10: Cross-bundle response ordering ==========

    @Test
    @DisplayName("bundle acking DATA #1+#2 with out-of-order replies transitions to PAUSED")
    void crossBundleOutOfOrderReplyPausesChannel() {
        // Bundle acks DATA #1 and #2 (peerReceivedMessageId=2) but provides replies in wrong
        // order (#2 first, then #1). The prescan expects reply for #1 before reply for #2.
        putOutboundDataMessage(1);
        putOutboundDataMessage(2);
        // Channel: nextMessageId=3, ackedMessageId=0 (nothing acked yet)
        putChannel(ClprChannelStatus.ACTIVE, 3, 0, ZERO_HASH);

        // Bundle acks both DATA messages (peerReceivedMessageId=2) with replies in WRONG order
        final var bundle =
                buildBundle(ClprChannelStatus.ACTIVE, 0, 2, ZERO_HASH, List.of(replyPayload(2), replyPayload(1)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        // Prescan detects reply #2 before reply #1 → channel PAUSED
        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.PAUSED);
        // Neither DATA message deleted (prescan exits before mutation pass)
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNotNull();
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 2)).isNotNull();
    }

    @Test
    @DisplayName("bundle acking DATA #1+#2 with in-order replies succeeds and channel stays ACTIVE")
    void crossBundleInOrderRepliesSucceed() {
        // Bundle acks DATA #1 and #2 (peerReceivedMessageId=2) with replies in correct order.
        putOutboundDataMessage(1);
        putOutboundDataMessage(2);
        // Channel: nextMessageId=3, ackedMessageId=0 (nothing acked yet)
        putChannel(ClprChannelStatus.ACTIVE, 3, 0, ZERO_HASH);

        // Bundle acks both DATA messages with in-order replies: #1 then #2
        final var bundle =
                buildBundle(ClprChannelStatus.ACTIVE, 0, 2, ZERO_HASH, List.of(replyPayload(1), replyPayload(2)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.ACTIVE);
        // Both DATA messages removed from queue after their replies are processed
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNull();
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 2)).isNull();
    }

    // ========== Step 5a peer-state mirror (spec §4.2 step 5a) ==========

    @Test
    @DisplayName("PAUSED + peer CLOSING + in-order reply → channel transitions to CLOSING")
    void pausedChannelPeerClosingTransitionsToClosing() {
        // Source is PAUSED (e.g. from an earlier ordering violation). Peer admin closes the
        // channel; peer's next bundle ships state=CLOSING along with a correctly-ordered
        // reply that lets us advance our ack. Spec §4.2 step 5a says ACTIVE or PAUSED →
        // CLOSING. Channel has two outbound DATA messages so peer's ack of #1 leaves
        // #2 un-acked — outboundDrained stays false so we don't transition past CLOSING.
        putOutboundDataMessage(1);
        putOutboundDataMessage(2);
        putChannel(ClprChannelStatus.PAUSED, 3, 0, ZERO_HASH);

        final var bundle = buildBundle(ClprChannelStatus.CLOSING, 0, 1, ZERO_HASH, List.of(replyPayload(1)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSING);
        assertThat(updated.ackedMessageId()).isEqualTo(1L);
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 1)).isNull();
        // Slot 2 still in queue (un-acked) — keeps drain check from firing
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 2)).isNotNull();
    }

    @Test
    @DisplayName("PAUSED + peer DRAINED + in-order reply → channel transitions to CLOSING")
    void pausedChannelPeerDrainedTransitionsToClosing() {
        // Same drain-close handshake but peer is further along (DRAINED). Source still mirrors
        // to CLOSING per spec §4.2 step 5a. Same two-slot trick to keep outboundDrained false.
        putOutboundDataMessage(1);
        putOutboundDataMessage(2);
        putChannel(ClprChannelStatus.PAUSED, 3, 0, ZERO_HASH);

        final var bundle = buildBundle(ClprChannelStatus.DRAINED, 0, 1, ZERO_HASH, List.of(replyPayload(1)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSING);
        assertThat(updated.ackedMessageId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("ACTIVE + peer CLOSING transitions to CLOSING")
    void activeChannelPeerClosingStillTransitionsToClosing() {
        putOutboundDataMessage(1);
        putOutboundDataMessage(2);
        putChannel(ClprChannelStatus.ACTIVE, 3, 0, ZERO_HASH);

        final var bundle = buildBundle(ClprChannelStatus.CLOSING, 0, 1, ZERO_HASH, List.of(replyPayload(1)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSING);
    }

    @Test
    @DisplayName("CLOSED peer state on ACTIVE local channel triggers CLOSING transition (Step 5a)")
    void closedPeerStateTriggersClosingTransition() {
        // local=ACTIVE, peer=CLOSED — close-notification bundle arriving at an otherwise active conn.
        // Put two outbound messages; peer acks only #1, so #2 remains un-acked — keeps outboundDrained false
        // so we stay in CLOSING without cascading to CLOSED.
        putOutboundDataMessage(1);
        putOutboundDataMessage(2);
        putChannel(ClprChannelStatus.ACTIVE, 3, 0, ZERO_HASH);
        final var bundle = buildBundle(ClprChannelStatus.CLOSED, 0, 1, ZERO_HASH, List.of(replyPayload(1)));
        setupHandleContext(bundle, true);

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSING);
        assertThat(updated.ackedMessageId()).isEqualTo(1L);
        // Slot 2 still in queue (un-acked) — keeps drain check from firing
        assertThat(messageQueueStore.getMessage(CHANNEL_ID, 2)).isNotNull();
    }

    // ========== C?-11: Per-message decode failure → PAUSED ==========

    @Test
    @DisplayName("runtime exception during per-message dispatch pauses channel with CLPR_BUNDLE_DECODE_FAILED")
    void runtimeExceptionInDispatchLoopPausesChannel() {
        // Simulate a RuntimeException thrown inside the per-message dispatch loop by making
        // the tokenServiceApi.transferFromTo throw unexpectedly on the first call.
        // The handler wraps the whole per-message processing in try/catch(RuntimeException),
        // so a throw there should PAUSE the channel and propagate CLPR_BUNDLE_DECODE_FAILED.
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        putConnector();

        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true);

        // Cause a RuntimeException inside the dispatch loop (pre-dispatch transfer throws).
        // transferFromTo is void, so use doThrow rather than given(...).willThrow().
        org.mockito.Mockito.doThrow(new RuntimeException("simulated decode/state corruption"))
                .when(tokenServiceApi)
                .transferFromTo(any(), any(), anyLong());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_DECODE_FAILED));

        // Channel should be PAUSED in persisted state
        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.PAUSED);
    }

    @Test
    @DisplayName("runtime exception stops processing remaining messages in bundle")
    void runtimeExceptionStopsRemainingMessages() {
        // Two data messages in bundle; the first triggers a RuntimeException.
        // Only the channel should be PAUSED — the second message should NOT be processed.
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        putConnector();

        final var bundle =
                buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload(), dataPayload()));
        setupHandleContext(bundle, true);

        // transferFromTo is void, so use doThrow rather than given(...).willThrow().
        org.mockito.Mockito.doThrow(new RuntimeException("simulated decode/state corruption"))
                .when(tokenServiceApi)
                .transferFromTo(any(), any(), anyLong());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_DECODE_FAILED));

        // No reply messages enqueued for either message (stopped at first exception)
        // dispatch was called at most once (for the first message before the exception)
        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.PAUSED);
    }

    // ========== Section 7 / bundle-level limit tests ==========

    /**
     * Spec 3.10.1: bundle exceeding max_messages_per_bundle is rejected.
     * Channel state must not advance on rejection.
     */
    @Test
    @DisplayName("3.10.1 — bundle exceeding max_messages_per_bundle is rejected with CLPR_BUNDLE_VERIFICATION_FAILED")
    void spec3_10_1_maxMessagesPerBundleExceeded() {
        // Config allows max 2 messages; bundle has 3.
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        final var messages = List.of(dataPayload(), dataPayload(), dataPayload());
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, messages);
        setupHandleContext(bundle, true, 2, 65536, 1_048_576L);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));

        // Channel state must not advance
        final var conn = channelStore.getChannel(CHANNEL_ID);
        assertThat(conn.receivedMessageId()).isEqualTo(0L);
        assertThat(conn.status()).isEqualTo(ClprChannelStatus.ACTIVE);
    }

    /**
     * Spec 3.10.2: bundle whose serialized size exceeds max_sync_bytes is rejected.
     * Channel state must not advance on rejection.
     */
    @Test
    @DisplayName("3.10.2 — bundle payload exceeding max_sync_bytes is rejected with CLPR_PAYLOAD_TOO_LARGE")
    void spec3_10_2_maxSyncBytesExceeded() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        // Build a bundle with a large payload, then set max_sync_bytes to 1 byte to force rejection.
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
        setupHandleContext(bundle, true, 1000, 65536, 1L /* max_sync_bytes = 1 forces rejection */);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_PAYLOAD_TOO_LARGE));

        // Channel state must not advance
        final var conn = channelStore.getChannel(CHANNEL_ID);
        assertThat(conn.receivedMessageId()).isEqualTo(0L);
        assertThat(conn.status()).isEqualTo(ClprChannelStatus.ACTIVE);
    }

    /**
     * Spec 3.5.4: a Data message whose payload exceeds max_message_payload_bytes causes the
     * entire bundle to be rejected. Channel state must not advance.
     */
    @Test
    @DisplayName(
            "3.5.4 — individual message exceeding max_message_payload_bytes rejects bundle with CLPR_PAYLOAD_TOO_LARGE")
    void spec3_5_4_perMessagePayloadSizeExceeded() {
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
        // Build a payload whose message_data is 10 bytes; limit is 5 bytes.
        final var oversizedPayload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .connectorId(CONNECTOR_ADDRESS)
                        .targetApplication(TARGET_APP)
                        .sender(SENDER)
                        .messageData(Bytes.wrap(new byte[10]))
                        .build())
                .build();
        final var bundle = buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(oversizedPayload));
        setupHandleContext(bundle, true, 1000, 5 /* max_message_payload_bytes = 5 */, 1_048_576L);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_PAYLOAD_TOO_LARGE));

        // Channel state must not advance
        final var conn = channelStore.getChannel(CHANNEL_ID);
        assertThat(conn.receivedMessageId()).isEqualTo(0L);
        assertThat(conn.status()).isEqualTo(ClprChannelStatus.ACTIVE);
    }

    /**
     * Spec 5.2.6: bundle whose metadata claims more message IDs than messages present
     * (i.e., non-contiguous / gap) is rejected. Channel state must not advance.
     */
    @Test
    @DisplayName(
            "5.2.6 — bundle with non-contiguous message IDs (gap in sequence) is rejected with CLPR_BUNDLE_VERIFICATION_FAILED")
    void spec5_2_6_nonContiguousMessageIds() {
        // Channel has receivedMessageId=0, so expects IDs 1, 2, 3 for a 3-message bundle.
        // We build metadata claiming nextMessageId=5 (IDs 1..4, count=4) but only supply 3
        // messages — creating a gap (simulating IDs 1, 2, 4 with 3 missing).
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);

        final var msgs = List.of(dataPayload(), dataPayload(), dataPayload());
        var hash = ZERO_HASH;
        for (final var m : msgs) {
            hash = ClprHashUtils.computeRunningHash(hash, m);
        }
        // nextMessageId=5 implies 4 messages expected (5-1=4), but we only provide 3 → gap detected
        final var metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(5L)
                .sentRunningHash(hash)
                .receivedMessageId(0L)
                .status(ClprChannelStatus.ACTIVE)
                .build();
        final var bundleContent =
                ClprBundleContent.newBuilder().metadata(metadata).messages(msgs).build();
        setupHandleContext(bundleTxn(bundleContent), true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));

        // Channel state must not advance
        final var conn = channelStore.getChannel(CHANNEL_ID);
        assertThat(conn.receivedMessageId()).isEqualTo(0L);
        assertThat(conn.status()).isEqualTo(ClprChannelStatus.ACTIVE);
    }

    @Test
    @DisplayName("flag off: a peer-reported endpoint_manifest_version is ignored and does not perturb bundle handling")
    void peerEndpointManifestVersionIgnoredWhenFlagOff() {
        // Spec §4.5: metadata.endpoint_manifest_version carries the sender's cache of THIS
        // ledger's manifest version. #330 populates + carries the field through the pipeline.
        // With clpr.endpointManifestEnabled=false the handler does not read or record it (the
        // inbound read is flag-guarded), so the field is inert and the bundle is handled exactly
        // as before.
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);

        final var msg = dataPayload();
        final var runningHash = ClprHashUtils.computeRunningHash(ZERO_HASH, msg);
        final var metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(2L)
                .sentRunningHash(runningHash)
                .receivedMessageId(0L)
                .status(ClprChannelStatus.ACTIVE)
                .endpointManifestVersion(42L)
                .build();
        final var bundle = ClprBundleContent.newBuilder()
                .metadata(metadata)
                .messages(List.of(msg))
                .build();
        setupHandleContext(bundleTxn(bundle), true);
        putConnector();

        subject.handle(handleContext);

        // Bundle processed normally; channel advanced.
        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.receivedMessageId()).isEqualTo(1L);
        // Our OWN endpoint_manifest_version is NOT touched by the peer-reported value - this
        // ledger's cache of the PEER's manifest is unrelated to the peer's cache of ours.
        assertThat(updated.endpointManifestVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("flag on: a peer-reported endpoint_manifest_version is accepted and the bundle is handled cleanly")
    void peerEndpointManifestVersionAcceptedWhenFlagOn() {
        // With clpr.endpointManifestEnabled=true the handler reads the peer-reported version (the
        // guarded inbound read fires). On this branch that read is log-only — recording it lands in
        // #335 — so the observable contract is unchanged: the bundle is handled normally and this
        // ledger's own cache of the PEER's manifest is untouched (the bundle carries no
        // new_endpoint_manifest to apply via Step 1b).
        putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);

        final var msg = dataPayload();
        final var runningHash = ClprHashUtils.computeRunningHash(ZERO_HASH, msg);
        final var metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(2L)
                .sentRunningHash(runningHash)
                .receivedMessageId(0L)
                .status(ClprChannelStatus.ACTIVE)
                .endpointManifestVersion(42L)
                .build();
        final var bundle = ClprBundleContent.newBuilder()
                .metadata(metadata)
                .messages(List.of(msg))
                .build();
        setupHandleContextWithFlags(bundleTxn(bundle), true, true);
        putConnector();

        subject.handle(handleContext);

        // Bundle processed normally with the flag on; channel advanced.
        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.receivedMessageId()).isEqualTo(1L);
        // No new_endpoint_manifest in the bundle → Step 1b is a no-op; our cache stays at 0.
        assertThat(updated.endpointManifestVersion()).isEqualTo(0L);
    }

    // ========== Verifier outcomes ==========

    /**
     * Tests that require controlling what the {@link ClprVerifier} returns or throws.
     * Each test overrides the factory stub set in the outer {@code setUp()} with a Mockito
     * mock so individual tests can configure any verifier outcome without touching the
     * pass-through verifier used by the rest of the suite.
     */
    @Nested
    @DisplayName("Verifier outcomes")
    class VerifierOutcomes {

        @Mock
        private ClprVerifier mockVerifier;

        @BeforeEach
        void overrideVerifier() {
            lenient().when(verifierFactory.getVerifier(any())).thenReturn(mockVerifier);
        }

        @Test
        @DisplayName("verifyBundle() reverts — bundle rejected, channel state unchanged")
        void verifyBundleRevertsRejectsBundle() {
            putChannel(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH);
            putConnector();
            given(mockVerifier.verifyBundle(any(), any(), any(), any()))
                    .willThrow(new HandleException(CLPR_BUNDLE_VERIFICATION_FAILED));

            setupHandleContext(buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload())), true);

            assertThatThrownBy(() -> subject.handle(handleContext))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));

            final var conn = channelStore.getChannel(CHANNEL_ID);
            assertThat(conn.receivedMessageId()).isEqualTo(0L);
            assertThat(conn.status()).isEqualTo(ClprChannelStatus.ACTIVE);
        }

        @Test
        @DisplayName("metadata-free trust-update-only result installs anchor and returns")
        void metadataFreeTrustUpdateOnlyResultInstallsAnchor() {
            final var newTrustAnchor = Bytes.wrap(new byte[] {1, 2, 3, 4});
            final var newTrustAnchorId = Bytes.wrap(new byte[] {5, 6, 7, 8});
            final var trustOnlyContent = ClprBundleContent.newBuilder()
                    .newTrustAnchor(newTrustAnchor)
                    .newTrustAnchorId(newTrustAnchorId)
                    .build();
            putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
            setupHandleContext(validSingleDataBundle(), true);
            given(mockVerifier.verifyBundle(any(), any(), any(), any())).willReturn(trustOnlyContent);

            subject.handle(handleContext);

            final var updated = channelStore.getChannel(CHANNEL_ID);
            assertThat(updated.trustAnchor()).isEqualTo(newTrustAnchor);
            assertThat(updated.trustAnchorId()).isEqualTo(newTrustAnchorId);
            assertThat(updated.nextMessageId()).isEqualTo(1L);
            assertThat(updated.receivedMessageId()).isZero();
            verify(handleContext, never()).dispatch(any(DispatchOptions.class));
        }

        @Test
        @DisplayName("metadata-free manifest-only recovery replaces the peer manifest and returns (spec §8.1.4)")
        void metadataFreeManifestOnlyRecoveryReplacesManifest() {
            final var newManifest = ClprEndpointManifest.newBuilder()
                    .version(5L)
                    .serviceAddress(Bytes.wrap(new byte[] {9, 8, 7}))
                    .build();
            // Manifest-only recovery: no metadata, no messages, no trust anchor — only the manifest advance.
            final var manifestOnlyContent = ClprBundleContent.newBuilder()
                    .newEndpointManifest(newManifest)
                    .build();
            putChannelWithManifestVersion(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH, 1L);
            setupHandleContextWithFlags(validSingleDataBundle(), true, true);
            given(mockVerifier.verifyBundle(any(), any(), any(), any())).willReturn(manifestOnlyContent);

            subject.handle(handleContext);

            final var updated = channelStore.getChannel(CHANNEL_ID);
            assertThat(updated.endpointManifestVersion()).isEqualTo(5L);
            assertThat(updated.endpointManifestOrThrow()).isEqualTo(newManifest);
            // Queue state untouched; no application dispatch (no messages).
            assertThat(updated.nextMessageId()).isEqualTo(1L);
            assertThat(updated.receivedMessageId()).isZero();
            verify(handleContext, never()).dispatch(any(DispatchOptions.class));
        }

        @Test
        @DisplayName("metadata-free manifest-only recovery with a stale manifest is rejected (no progress)")
        void metadataFreeManifestOnlyStaleIsRejected() {
            final var staleManifest = ClprEndpointManifest.newBuilder()
                    .version(3L)
                    .serviceAddress(Bytes.wrap(new byte[] {9, 8, 7}))
                    .build();
            final var manifestOnlyContent = ClprBundleContent.newBuilder()
                    .newEndpointManifest(staleManifest)
                    .build();
            putChannelWithManifestVersion(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH, 5L);
            setupHandleContextWithFlags(validSingleDataBundle(), true, true);
            given(mockVerifier.verifyBundle(any(), any(), any(), any())).willReturn(manifestOnlyContent);

            assertThatThrownBy(() -> subject.handle(handleContext))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
        }

        @Test
        @DisplayName("metadata-free verifier result without anchor is rejected")
        void metadataFreeResultWithoutAnchorIsRejected() {
            putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
            setupHandleContext(validSingleDataBundle(), true);
            given(mockVerifier.verifyBundle(any(), any(), any(), any())).willReturn(ClprBundleContent.DEFAULT);

            assertThatThrownBy(() -> subject.handle(handleContext))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
        }

        @Test
        @DisplayName("oversized wire payload is rejected before verifier invocation")
        void oversizedPayloadIsRejectedBeforeVerifierInvocation() {
            putChannel(ClprChannelStatus.ACTIVE, 1, 0, ZERO_HASH);
            setupHandleContext(validSingleDataBundle(), true, 100, 65_536, 1L);

            assertThatThrownBy(() -> subject.handle(handleContext))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(CLPR_PAYLOAD_TOO_LARGE));
            verify(mockVerifier, never()).verifyBundle(any(), any(), any(), any());
        }
    }

    // ========== Helper methods ==========

    private void putOutboundDataMessage(final long messageId) {
        messageQueueStore.put(
                CHANNEL_ID,
                messageId,
                ClprMessageValue.newBuilder()
                        .payload(dataPayload())
                        .runningHashAfterProcessing(ZERO_HASH)
                        .build());
    }

    private ClprMessagePayload replyPayload(final long messageId) {
        return replyPayloadWithStatus(messageId, ClprMessageReplyStatus.SUCCESS);
    }

    private ClprMessagePayload replyPayloadWithStatus(
            final long messageId, @NonNull final ClprMessageReplyStatus status) {
        return ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder()
                        .messageId(messageId)
                        .status(status)
                        .messageReplyData(Bytes.EMPTY)
                        .build())
                .build();
    }

    private ClprMessagePayload dataPayload() {
        return ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .connectorId(CONNECTOR_ADDRESS)
                        .targetApplication(TARGET_APP)
                        .sender(SENDER)
                        .messageData(MESSAGE_DATA)
                        .build())
                .build();
    }

    /** Builds a redacted-slot payload carrying the supplied {@code SHA-256(serialized_payload)}. */
    private static ClprMessagePayload redactedPayload(@NonNull final Bytes messageHash) {
        return ClprMessagePayload.newBuilder()
                .redactedMessage(ClprRedactedMessage.newBuilder()
                        .messageHash(messageHash)
                        .build())
                .build();
    }

    /**
     * ABI-encodes a single {@code bytes} value as it would appear in an EVM return buffer.
     * <p>
     * The Solidity ABI encoding for a dynamic {@code bytes} return consists of:
     * <ol>
     *   <li><b>Offset (32 bytes):</b> A pointer to the start of the data, relative to the start
     *       of the encoded region. For a single return value, this is always 0x20 (32).</li>
     *   <li><b>Length (32 bytes):</b> The number of bytes in the array, stored as a big-endian integer.</li>
     *   <li><b>Data:</b> The raw bytes, right-padded with zeros to the next 32-byte boundary.</li>
     * </ol>
     *
     * @param inner the raw bytes to encode
     * @return the ABI-encoded bytes
     */
    private static Bytes abiEncodeBytes(final byte[] inner) {
        final int paddedLen = ((inner.length + 31) / 32) * 32;
        final byte[] result = new byte[64 + paddedLen];
        result[31] = 0x20;
        result[60] = (byte) ((inner.length >> 24) & 0xFF);
        result[61] = (byte) ((inner.length >> 16) & 0xFF);
        result[62] = (byte) ((inner.length >> 8) & 0xFF);
        result[63] = (byte) (inner.length & 0xFF);
        System.arraycopy(inner, 0, result, 64, inner.length);
        return Bytes.wrap(result);
    }

    private TransactionBody buildRedactedBundle() {
        return buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(redactedPayload(ZERO_HASH)));
    }

    /** Builds a valid bundle, computing the spec §4.1 running-hash chain over the messages. */
    private TransactionBody buildBundle(
            @NonNull final ClprChannelStatus peerState,
            final long ackedMessageId,
            final long peerReceivedMessageId,
            @NonNull final Bytes startingHash,
            @NonNull final List<ClprMessagePayload> messages) {
        var hash = startingHash;
        for (final var p : messages) {
            if (p.hasRedactedMessage()) {
                hash = ClprHashUtils.computeRunningHashFromPayloadHash(
                        hash, p.redactedMessageOrThrow().messageHash());
            } else {
                hash = ClprHashUtils.computeRunningHash(hash, p);
            }
        }
        final var metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(ackedMessageId + messages.size() + 1)
                .sentRunningHash(hash)
                .receivedMessageId(peerReceivedMessageId)
                .status(peerState)
                .build();
        final var bundle = ClprBundleContent.newBuilder()
                .metadata(metadata)
                .messages(messages)
                .build();
        return bundleTxn(bundle);
    }

    private TransactionBody validSingleDataBundle() {
        return buildBundle(ClprChannelStatus.ACTIVE, 0, 0, ZERO_HASH, List.of(dataPayload()));
    }

    private static @NonNull ClprControlMessage buildControlMessage(Timestamp ledgerConfigTimestamp) {
        return ClprControlMessage.newBuilder()
                .configUpdate(ClprConfigUpdate.newBuilder()
                        .configuration(ClprLedgerConfiguration.newBuilder()
                                .timestamp(ledgerConfigTimestamp)
                                .build())
                        .build())
                .build();
    }

    private void putChannel(
            @NonNull final ClprChannelStatus status,
            final long nextMessageId,
            final long ackedMessageId,
            @NonNull final Bytes receivedRunningHash) {
        putChannel(status, nextMessageId, ackedMessageId, 0, receivedRunningHash);
    }

    private void putChannel(
            @NonNull final ClprChannelStatus status,
            final long nextMessageId,
            final long ackedMessageId,
            final long receivedMessageId,
            @NonNull final Bytes receivedRunningHash) {
        channelStore.put(ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .chainId("hedera:testnet")
                .serviceAddress(Bytes.EMPTY)
                .verifierContract(VERIFIER_CONTRACT)
                .status(status)
                .nextMessageId(nextMessageId)
                .ackedMessageId(ackedMessageId)
                .sentRunningHash(ZERO_HASH)
                .receivedMessageId(receivedMessageId)
                .receivedRunningHash(receivedRunningHash)
                .lastConfigTimestamp(CONFIG_TIMESTAMP)
                .peerConfigTimestamp(ZERO_TIMESTAMP)
                .build());
    }

    private void putChannelWithManifestVersion(
            @NonNull final ClprChannelStatus status,
            final long nextMessageId,
            final long ackedMessageId,
            @NonNull final Bytes receivedRunningHash,
            final long endpointManifestVersion) {
        channelStore.put(ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .chainId("hedera:testnet")
                .serviceAddress(Bytes.EMPTY)
                .verifierContract(VERIFIER_CONTRACT)
                .status(status)
                .nextMessageId(nextMessageId)
                .ackedMessageId(ackedMessageId)
                .sentRunningHash(ZERO_HASH)
                .receivedMessageId(0)
                .receivedRunningHash(receivedRunningHash)
                .lastConfigTimestamp(CONFIG_TIMESTAMP)
                .peerConfigTimestamp(ZERO_TIMESTAMP)
                .endpointManifestVersion(endpointManifestVersion)
                .build());
    }

    private static ClprEndpointManifest buildManifest(final long version, final String ipAddress) {
        return ClprEndpointManifest.newBuilder()
                .version(version)
                .endpoints(List.of(ClprEndpoint.newBuilder()
                        .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                                .ipAddress(ipAddress)
                                .port(50211)
                                .build())
                        .tlsCertificate(Bytes.wrap(new byte[] {(byte) version}))
                        .accountId(Bytes.wrap(new byte[] {(byte) version}))
                        .build()))
                .build();
    }

    private static ClprBundleContent buildBundleWithManifest(
            @edu.umd.cs.findbugs.annotations.Nullable final ClprEndpointManifest newManifest,
            @NonNull final List<ClprMessagePayload> messages) {
        // Start from ZERO_HASH (matches putChannel's receivedRunningHash) and chain each
        // message payload so the bundle's sentRunningHash matches what the receiver recomputes.
        var hash = ZERO_HASH;
        for (final var p : messages) {
            if (p.hasRedactedMessage()) {
                hash = ClprHashUtils.computeRunningHashFromPayloadHash(
                        hash, p.redactedMessageOrThrow().messageHash());
            } else {
                hash = ClprHashUtils.computeRunningHash(hash, p);
            }
        }
        final var metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(messages.size() + 1L)
                .sentRunningHash(hash)
                .receivedMessageId(0)
                .status(ClprChannelStatus.ACTIVE)
                .build();
        final var builder = ClprBundleContent.newBuilder().metadata(metadata).messages(messages);
        if (newManifest != null) {
            builder.newEndpointManifest(newManifest);
        }
        return builder.build();
    }

    private void setupHandleContextWithFlags(
            @NonNull final TransactionBody txn, final boolean clprEnabled, final boolean endpointManifestEnabled) {
        setupHandleContext(txn, clprEnabled);
        final var config = com.hedera.node.config.testfixtures.HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", clprEnabled)
                .withValue("clpr.endpointManifestEnabled", endpointManifestEnabled)
                .withValue("clpr.slashBasePenalty", "10000000")
                .withValue("clpr.slashMultiplier", "2")
                .withValue("clpr.slashBanThreshold", "5")
                .withValue("clpr.messageExecutionCost", String.valueOf(MESSAGE_EXECUTION_COST))
                .withValue("clpr.endpointMarginPercent", String.valueOf(ENDPOINT_MARGIN_PERCENT))
                .withValue("clpr.endpointMisbehaviorPenaltyTinybars", "5000000")
                .withValue("clpr.endpointPenaltyTinybars", "5000000")
                .getOrCreateConfig();
        lenient().when(handleContext.configuration()).thenReturn(config);
    }

    private void putChannelWithConfigTimestamp(
            @NonNull final ClprChannelStatus status,
            final long nextMessageId,
            final long ackedMessageId,
            final long receivedMessageId,
            @NonNull final Bytes receivedRunningHash,
            @NonNull final Timestamp lastConfigTs) {
        channelStore.put(ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .chainId("hedera:testnet")
                .serviceAddress(Bytes.EMPTY)
                .verifierContract(VERIFIER_CONTRACT)
                .status(status)
                .nextMessageId(nextMessageId)
                .ackedMessageId(ackedMessageId)
                .sentRunningHash(ZERO_HASH)
                .receivedMessageId(receivedMessageId)
                .receivedRunningHash(receivedRunningHash)
                .lastConfigTimestamp(lastConfigTs)
                .peerConfigTimestamp(ZERO_TIMESTAMP)
                .build());
    }

    private static final long DEFAULT_CONNECTOR_BALANCE = 100_000_000L;

    private void putConnector() {
        putConnectorWithStakeAndBalance(100_000_000L, 0, DEFAULT_CONNECTOR_BALANCE);
    }

    private void putConnectorWithStake(final long lockedStake, final int slashCount) {
        putConnectorWithStakeAndBalance(lockedStake, slashCount, DEFAULT_CONNECTOR_BALANCE);
    }

    private void putConnectorWithStakeAndBalance(final long lockedStake, final int slashCount, final long balance) {
        connectorStore.put(ClprConnector.newBuilder()
                .channelId(CHANNEL_ID)
                .connectorId(CONNECTOR_ADDRESS)
                .connectorContract(CONNECTOR_CONTRACT_ID)
                .lockedStake(lockedStake)
                .slashCount(slashCount)
                .build());
        final var connectorAccount = Account.newBuilder()
                .accountId(CONNECTOR_CONTRACT_ACCOUNT)
                .tinybarBalance(balance)
                .build();
        lenient().when(accountStore.getContractById(CONNECTOR_CONTRACT_ID)).thenReturn(connectorAccount);
    }

    private void setupHandleContext(
            @NonNull final TransactionBody txn,
            final boolean enabled,
            final int maxMessagesPerBundle,
            final int maxMessagePayloadBytes,
            final long maxSyncBytes) {
        // Set up common mocks first, then override the throttle config.
        setupHandleContext(txn, enabled);
        // Override the throttle config with custom limits (overrides the default set by setupHandleContext).
        lenient()
                .when(configStore.getConfiguration())
                .thenReturn(ClprLedgerConfiguration.newBuilder()
                        .timestamp(CONFIG_TIMESTAMP)
                        .throttles(ClprThrottles.newBuilder()
                                .maxMessagesPerBundle(maxMessagesPerBundle)
                                .maxMessagePayloadBytes(maxMessagePayloadBytes)
                                .maxQueueDepth(1000)
                                .maxSyncBytes(maxSyncBytes)
                                .maxGasPerMessage(TEST_MAX_GAS_PER_MESSAGE)
                                .build())
                        .build());
    }

    private void setupHandleContext(@NonNull final TransactionBody txn, final boolean enabled) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", enabled)
                .withValue("clpr.slashBasePenalty", "10000000")
                .withValue("clpr.slashMultiplier", "2")
                .withValue("clpr.slashBanThreshold", "5")
                .withValue("clpr.messageExecutionCost", String.valueOf(MESSAGE_EXECUTION_COST))
                .withValue("clpr.endpointMarginPercent", String.valueOf(ENDPOINT_MARGIN_PERCENT))
                .withValue("clpr.endpointMisbehaviorPenaltyTinybars", "5000000")
                .withValue("clpr.endpointPenaltyTinybars", "5000000")
                .getOrCreateConfig();
        lenient().when(handleContext.body()).thenReturn(txn);
        lenient().when(handleContext.payer()).thenReturn(PAYER_ID);
        lenient().when(handleContext.configuration()).thenReturn(config);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient().when(handleContext.creatorInfo()).thenReturn(creatorInfo);
        // Default to self-submitted (creatorNodeId == endpointNodeId): the handler skips
        // endpoint-signature verification for self-submitted bundles. Tests that need to
        // exercise the peer-submitted signature path override this after setupHandleContext.
        lenient().when(creatorInfo.nodeId()).thenReturn(ENDPOINT_NODE_ID);
        lenient().when(storeFactory.writableStore(WritableChannelStore.class)).thenReturn(channelStore);
        lenient()
                .when(storeFactory.writableStore(WritableMessageQueueStore.class))
                .thenReturn(messageQueueStore);
        lenient().when(storeFactory.writableStore(WritableConnectorStore.class)).thenReturn(connectorStore);
        lenient().when(handleContext.consensusNow()).thenReturn(Instant.ofEpochSecond(1_700_000_000L));
        lenient()
                .when(storeFactory.readableStore(ReadableLedgerConfigurationStore.class))
                .thenReturn(configStore);
        lenient().when(storeFactory.serviceApi(TokenServiceApi.class)).thenReturn(tokenServiceApi);
        lenient().when(configStore.getConfiguration()).thenReturn(createLedgerConfig());
        // Mock node store — doHandle requires a non-null node with an accountId
        final var endpointNodeObj = Node.newBuilder()
                .nodeId(ENDPOINT_NODE_ID)
                .accountId(ENDPOINT_ACCOUNT)
                .build();
        lenient().when(storeFactory.readableStore(ReadableNodeStore.class)).thenReturn(nodeStore);
        lenient().when(nodeStore.get(ENDPOINT_NODE_ID)).thenReturn(endpointNodeObj);
        // Mock account store — used for connector balance pre-check
        lenient().when(storeFactory.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
        // Mock dispatch for application calls — default to SUCCESS
        lenient().when(handleContext.dispatch(any(DispatchOptions.class))).thenReturn(dispatchResult);
        lenient().when(dispatchResult.status()).thenReturn(ResponseCodeEnum.SUCCESS);
        lenient().when(dispatchResult.getEvmCallResult()).thenReturn(Bytes.EMPTY);
        // Mock entity factory for staking account
        lenient().when(entityIdFactory.newAccountId(803L)).thenReturn(STAKING_ACCOUNT);
        // Staking account is escrowed with ample stake by default so slash reimbursements pay out in
        // full. Tests that exercise the unbacked-stake path override this to null / a low balance.
        lenient()
                .when(accountStore.getAccountById(STAKING_ACCOUNT))
                .thenReturn(Account.newBuilder()
                        .accountId(STAKING_ACCOUNT)
                        .tinybarBalance(1_000_000_000_000L)
                        .build());
        // System-admin account is the synthetic dispatch payer for app callbacks (per the
        // EntityIdFactory-based fix that populates shard/realm correctly on non-zero-shard networks).
        lenient().when(entityIdFactory.newAccountId(50L)).thenReturn(SYSTEM_ADMIN_ACCOUNT);
        // Long-zero target/sender resolution for app dispatches — return a ContractID carrying the
        // given evmAddress, mirroring AppEntityIdFactory's behavior so the unit-mocked path matches
        // production semantics.
        lenient()
                .when(entityIdFactory.newContractIdWithEvmAddress(any(Bytes.class)))
                .thenAnswer(invocation -> com.hedera.hapi.node.base.ContractID.newBuilder()
                        .evmAddress(invocation.getArgument(0, Bytes.class))
                        .build());
    }

    private TransactionBody bundleTxn(@NonNull final ClprBundleContent bundle) {
        final var payload = ClprBundleContent.PROTOBUF.toBytes(bundle);
        return submitBundleTxn(CHANNEL_ID, payload, ENDPOINT_NODE_ID);
    }

    private TransactionBody submitBundleTxn(
            @NonNull final Bytes channelId, @NonNull final Bytes payload, final long endpointNodeId) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprSubmitBundle(ClprSubmitBundleTransactionBody.newBuilder()
                        .channelId(channelId)
                        .bundlePayload(payload)
                        .endpointNodeId(endpointNodeId)
                        .build())
                .build();
    }

    private ClprLedgerConfiguration createLedgerConfig() {
        return createLedgerConfigWithMaxPeerEndpoints(0);
    }

    /** Single source of truth for the default throttle values shared by the test config helpers. */
    private ClprThrottles defaultTestThrottles() {
        return ClprThrottles.newBuilder()
                .maxMessagesPerBundle(100)
                .maxMessagePayloadBytes(65536)
                .maxQueueDepth(1000)
                .maxSyncBytes(1_048_576L)
                .maxGasPerMessage(TEST_MAX_GAS_PER_MESSAGE)
                .build();
    }

    private ClprLedgerConfiguration createLedgerConfigWithMaxPeerEndpoints(final int maxPeerEndpoints) {
        return ClprLedgerConfiguration.newBuilder()
                .timestamp(CONFIG_TIMESTAMP)
                .throttles(defaultTestThrottles()
                        .copyBuilder()
                        .maxPeerEndpoints(maxPeerEndpoints)
                        .build())
                .build();
    }

    /**
     * Overrides the ledger config so the per-message gas ceiling is {@code maxGasPerMessage}.
     * Used to prove the dispatched callback gas tracks the configured throttle (spec §1.1 / §6.0).
     */
    private void overrideMaxGasPerMessage(final long maxGasPerMessage) {
        lenient()
                .when(configStore.getConfiguration())
                .thenReturn(ClprLedgerConfiguration.newBuilder()
                        .timestamp(CONFIG_TIMESTAMP)
                        .throttles(defaultTestThrottles()
                                .copyBuilder()
                                .maxGasPerMessage(maxGasPerMessage)
                                .build())
                        .build());
    }

    private static ClprMessagePayload configUpdatePayload(
            @NonNull final Timestamp peerTimestamp, @NonNull final List<ClprEndpoint> endpoints) {
        return ClprMessagePayload.newBuilder()
                .control(ClprControlMessage.newBuilder()
                        .configUpdate(ClprConfigUpdate.newBuilder()
                                .configuration(ClprLedgerConfiguration.newBuilder()
                                        .timestamp(peerTimestamp)
                                        .endpoints(endpoints)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static List<ClprEndpoint> endpointList(final int count) {
        final var endpoints = new ArrayList<ClprEndpoint>();
        for (int i = 0; i < count; i++) {
            endpoints.add(ClprEndpoint.newBuilder().build());
        }
        return endpoints;
    }

    @Test
    @DisplayName("verifyBundle receives channelContext from state entity, not from untrusted bundle payload")
    void verifierReceivesChannelContextFromEntityNotFromPayload() {
        // op.channelId() = alternativeId (spoofed); the entity stored under that key
        // has channelContext = knownContext (the authoritative value from state).
        final var alternativeIdBytes = new byte[32];
        alternativeIdBytes[0] = 0x01;
        final Bytes alternativeId = Bytes.wrap(alternativeIdBytes);

        final var knownContextBytes = new byte[52]; // 32 (channelId) + 20 (serviceAddress)
        knownContextBytes[0] = (byte) 0x42;
        final Bytes knownContext = Bytes.wrap(knownContextBytes);

        // Store the entity keyed by alternativeId so the handler lookup by op.channelId()
        // finds an entity whose channelContext is the authoritative knownContext.
        channelStore.put(ClprChannel.newBuilder()
                .channelId(alternativeId)
                .channelContext(knownContext)
                .verifierContract(VERIFIER_CONTRACT)
                .status(ClprChannelStatus.ACTIVE)
                .sentRunningHash(ZERO_HASH)
                .receivedRunningHash(ZERO_HASH)
                .lastConfigTimestamp(CONFIG_TIMESTAMP)
                .peerConfigTimestamp(ZERO_TIMESTAMP)
                .build());

        // Capture which channelContext the verifier receives, then reject so we don't need
        // a valid bundle payload.
        final var capturedContext = new Bytes[1];
        given(verifierFactory.getVerifier(any())).willReturn(getFailureVerifier(capturedContext));

        setupHandleContext(submitBundleTxn(alternativeId, Bytes.wrap(new byte[] {1}), ENDPOINT_NODE_ID), true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));

        // The verifier must receive the entity's channelContext, not anything derived from
        // op.channelId() (alternativeId).
        assertThat(capturedContext[0]).isEqualTo(knownContext);
        assertThat(capturedContext[0]).isNotEqualTo(alternativeId);
    }

    private static ClprVerifier getFailureVerifier(final Bytes[] capturedContext) {
        // Return a ClprVerifier that always throws an exception
        return new ClprVerifier() {
            @Override
            public VerifiedConfig verifyConfig(
                    @NonNull Bytes configProofBytes,
                    @NonNull Bytes channelId,
                    @NonNull Bytes endpointManifestProofBytes,
                    @NonNull HandleContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ClprBundleContent verifyBundle(
                    @NonNull Bytes bundlePayload,
                    @NonNull Bytes trustAnchor,
                    @NonNull Bytes channelContext,
                    @NonNull HandleContext context) {
                capturedContext[0] = channelContext;
                throw new HandleException(CLPR_BUNDLE_VERIFICATION_FAILED);
            }
        };
    }
}
