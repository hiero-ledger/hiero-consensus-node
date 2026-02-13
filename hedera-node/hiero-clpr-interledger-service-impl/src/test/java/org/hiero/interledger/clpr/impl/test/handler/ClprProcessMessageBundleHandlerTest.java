// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test.handler;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_BUNDLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_RUNNING_HASH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageKey;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageValue;
import static org.hiero.interledger.clpr.ClprStateProofUtils.validateStateProof;
import static org.hiero.interledger.clpr.impl.ClprMessageUtils.nextRunningHash;
import static org.hiero.interledger.clpr.impl.ClprServiceImpl.RUNNING_HASH_SIZE;
import static org.hiero.interledger.clpr.impl.test.handler.ClprTestConstants.MOCK_LEDGER_ID;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.hapi.interledger.clpr.ClprProcessMessageBundleTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprMessage;
import org.hiero.hapi.interledger.state.clpr.ClprMessageBundle;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.WritableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.WritableClprMessageStore;
import org.hiero.interledger.clpr.impl.ClprMessageUtils;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;
import org.hiero.interledger.clpr.impl.handlers.ClprProcessMessageBundleHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprProcessMessageBundleHandlerTest extends ClprHandlerTestBase {

    @Mock
    private ClprStateProofManager stateProofManager;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private ClprProcessMessageBundleTransactionBody clprProcessMessageBundleTransactionBody;

    @Mock
    private ClprMessageBundle clprMessageBundle;

    @Mock
    private StateProof clprStateProof;

    @Mock
    private StoreFactory storeFactory;

    private ClprProcessMessageBundleHandler subject;

    @BeforeEach
    void setUp() {
        setupStates();
        subject = new ClprProcessMessageBundleHandler(stateProofManager, networkInfo, configProvider);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when stateProofManager is null")
    void constructorThrowsForNullStateProofManager() {
        assertThatThrownBy(() -> new ClprProcessMessageBundleHandler(null, networkInfo, configProvider))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when networkInfo is null")
    void constructorThrowsForNullNetworkInfo() {
        assertThatThrownBy(() -> new ClprProcessMessageBundleHandler(stateProofManager, null, configProvider))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when configProvider is null")
    void constructorThrowsForNullConfigProvider() {
        assertThatThrownBy(() -> new ClprProcessMessageBundleHandler(stateProofManager, networkInfo, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("pureChecks throws NOT_SUPPORTED if CLPR is disabled")
    void pureChecksThrowsIfClprIsDisabled() {
        given(stateProofManager.clprEnabled()).willReturn(false);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", NOT_SUPPORTED);
        verifyNoInteractions(pureChecksContext);
    }

    @Test
    @DisplayName("pureChecks throws INVALID_TRANSACTION_BODY if message bundle is missing")
    void pureChecksThrowsIfMessageBundleIsMissing() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(false);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", INVALID_TRANSACTION_BODY);
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_STATE_PROOF if state proof is missing")
    void pureChecksThrowsIfStateProofIsMissing() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(true);
        given(clprProcessMessageBundleTransactionBody.messageBundleOrThrow()).willReturn(clprMessageBundle);
        given(clprMessageBundle.hasStateProof()).willReturn(false);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_STATE_PROOF);
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_STATE_PROOF if state proof validation fails")
    void pureChecksThrowsIfStateProofValidationFails() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(true);
        given(clprProcessMessageBundleTransactionBody.messageBundleOrThrow()).willReturn(clprMessageBundle);
        given(clprMessageBundle.hasStateProof()).willReturn(true);
        given(clprMessageBundle.stateProofOrThrow()).willReturn(clprStateProof);
        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils.when(() -> validateStateProof(clprStateProof)).thenReturn(false);

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_STATE_PROOF);
        }
    }

    @Test
    @DisplayName("pureChecks succeeds with valid bundle and state proof")
    void pureChecksSucceedsWithValidBundleAndStateProof() throws PreCheckException {
        final var receivedRunningHash = Bytes.wrap(new byte[RUNNING_HASH_SIZE]);
        final var messageQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(5L)
                .receivedRunningHash(receivedRunningHash)
                .build();
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalMessageQueueMetadata(MOCK_LEDGER_ID)).willReturn(messageQueue);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(true);
        given(clprProcessMessageBundleTransactionBody.messageBundleOrThrow()).willReturn(clprMessageBundle);
        given(clprMessageBundle.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(clprMessageBundle.hasStateProof()).willReturn(true);
        given(clprMessageBundle.stateProofOrThrow()).willReturn(clprStateProof);
        given(clprMessageBundle.messages())
                .willReturn(List.of(ClprMessagePayload.newBuilder()
                        .message(ClprMessage.newBuilder()
                                .messageData(Bytes.wrap("msg1".getBytes()))
                                .build())
                        .build()));
        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils.when(() -> validateStateProof(clprStateProof)).thenReturn(true);
            stateProofUtils
                    .when(() -> extractMessageKey(clprStateProof))
                    .thenReturn(ClprMessageKey.newBuilder().messageId(7L).build());
            final var lastPayload = ClprMessagePayload.newBuilder()
                    .message(ClprMessage.newBuilder()
                            .messageData(Bytes.wrap("msg2".getBytes()))
                            .build())
                    .build();
            final var runningHash1 =
                    nextRunningHash(clprMessageBundle.messages().getFirst(), receivedRunningHash);
            final var runningHash2 = nextRunningHash(lastPayload, runningHash1);
            stateProofUtils
                    .when(() -> extractMessageValue(clprStateProof))
                    .thenReturn(ClprMessageValue.newBuilder()
                            .payload(lastPayload)
                            .runningHashAfterProcessing(runningHash2)
                            .build());

            subject.pureChecks(pureChecksContext);
        }
    }

    @Test
    @DisplayName("pureChecks verify running hash of partially received bundle")
    void pureChecksAllowsPartiallyReceivedBundle() throws PreCheckException {
        // create 4 payloads
        final var payload1 = payload("msg1");
        final var payload2 = payload("msg2");
        final var payload3 = payload("msg3");
        final var payload4 = payload("msg4");
        // calculate running hash of 4th msg
        final var initHash = Bytes.wrap(new byte[RUNNING_HASH_SIZE]);
        final var runningHash1 = nextRunningHash(payload1, initHash);
        final var runningHash2 = nextRunningHash(payload2, runningHash1);
        final var runningHash3 = nextRunningHash(payload3, runningHash2);
        final var runningHash4 = nextRunningHash(payload4, runningHash3);
        // build msg key and value for bundle state proof
        final var msg4Key = ClprMessageKey.newBuilder().messageId(4L).build();
        final var msg4Value = ClprMessageValue.newBuilder()
                .payload(payload4)
                .runningHashAfterProcessing(runningHash4)
                .build();
        // mock last received msg id to 2 and it's running hash
        final var messageQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(2L)
                .receivedRunningHash(runningHash2)
                .build();

        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalMessageQueueMetadata(MOCK_LEDGER_ID)).willReturn(messageQueue);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(true);
        given(clprProcessMessageBundleTransactionBody.messageBundleOrThrow()).willReturn(clprMessageBundle);
        given(clprMessageBundle.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(clprMessageBundle.hasStateProof()).willReturn(true);
        given(clprMessageBundle.stateProofOrThrow()).willReturn(clprStateProof);

        // The first two messages in this bundle are already received!
        given(clprMessageBundle.messages()).willReturn(List.of(payload1, payload2, payload3));

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils.when(() -> validateStateProof(clprStateProof)).thenReturn(true);
            stateProofUtils.when(() -> extractMessageKey(clprStateProof)).thenReturn(msg4Key);
            stateProofUtils.when(() -> extractMessageValue(clprStateProof)).thenReturn(msg4Value);

            subject.pureChecks(pureChecksContext);
        }
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_BUNDLE when bundle contains only received messages")
    void pureChecksThrowsIfBundleIsReceived() throws PreCheckException {
        // create 4 payloads
        final var payload1 = payload("msg1");
        final var payload2 = payload("msg2");
        final var payload3 = payload("msg3");
        final var payload4 = payload("msg4");
        // calculate running hash of 4th msg
        final var initHash = Bytes.wrap(new byte[RUNNING_HASH_SIZE]);
        final var runningHash1 = nextRunningHash(payload1, initHash);
        final var runningHash2 = nextRunningHash(payload2, runningHash1);
        final var runningHash3 = nextRunningHash(payload3, runningHash2);
        final var runningHash4 = nextRunningHash(payload4, runningHash3);
        // build msg key and value for bundle state proof
        final var msg4Key = ClprMessageKey.newBuilder().messageId(4L).build();
        final var msg4Value = ClprMessageValue.newBuilder()
                .payload(payload4)
                .runningHashAfterProcessing(runningHash4)
                .build();
        // mock last received msg id to 4
        final var messageQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(4L)
                .receivedRunningHash(runningHash4)
                .build();

        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalMessageQueueMetadata(MOCK_LEDGER_ID)).willReturn(messageQueue);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(true);
        given(clprProcessMessageBundleTransactionBody.messageBundleOrThrow()).willReturn(clprMessageBundle);
        given(clprMessageBundle.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(clprMessageBundle.hasStateProof()).willReturn(true);
        given(clprMessageBundle.stateProofOrThrow()).willReturn(clprStateProof);

        // The first two messages in this bundle are already received!
        given(clprMessageBundle.messages()).willReturn(List.of(payload1, payload2, payload3));

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils.when(() -> validateStateProof(clprStateProof)).thenReturn(true);
            stateProofUtils.when(() -> extractMessageKey(clprStateProof)).thenReturn(msg4Key);
            stateProofUtils.when(() -> extractMessageValue(clprStateProof)).thenReturn(msg4Value);

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_BUNDLE);
        }
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_BUNDLE when bundle message ids are misaligned")
    void pureChecksThrowsIfBundleMisalignedByMessageId() {
        final var messageQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(5L)
                .receivedRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalMessageQueueMetadata(MOCK_LEDGER_ID)).willReturn(messageQueue);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(true);
        given(clprProcessMessageBundleTransactionBody.messageBundleOrThrow()).willReturn(clprMessageBundle);
        given(clprMessageBundle.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(clprMessageBundle.hasStateProof()).willReturn(true);
        given(clprMessageBundle.stateProofOrThrow()).willReturn(clprStateProof);
        given(clprMessageBundle.messages())
                .willReturn(List.of(
                        ClprMessagePayload.newBuilder()
                                .message(ClprMessage.newBuilder()
                                        .messageData(Bytes.wrap("msg1".getBytes()))
                                        .build())
                                .build(),
                        ClprMessagePayload.newBuilder()
                                .message(ClprMessage.newBuilder()
                                        .messageData(Bytes.wrap("msg2".getBytes()))
                                        .build())
                                .build()));

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils.when(() -> validateStateProof(clprStateProof)).thenReturn(true);
            stateProofUtils
                    .when(() -> extractMessageKey(clprStateProof))
                    .thenReturn(ClprMessageKey.newBuilder().messageId(9L).build());

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_BUNDLE);
        }
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_RUNNING_HASH when running hash does not match")
    void pureChecksThrowsIfRunningHashMismatch() {
        final var messageQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(5L)
                .receivedRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalMessageQueueMetadata(MOCK_LEDGER_ID)).willReturn(messageQueue);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(true);
        given(clprProcessMessageBundleTransactionBody.messageBundleOrThrow()).willReturn(clprMessageBundle);
        given(clprMessageBundle.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(clprMessageBundle.hasStateProof()).willReturn(true);
        given(clprMessageBundle.stateProofOrThrow()).willReturn(clprStateProof);
        given(clprMessageBundle.messages())
                .willReturn(List.of(ClprMessagePayload.newBuilder()
                        .message(ClprMessage.newBuilder()
                                .messageData(Bytes.wrap("msg1".getBytes()))
                                .build())
                        .build()));

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils.when(() -> validateStateProof(clprStateProof)).thenReturn(true);
            stateProofUtils
                    .when(() -> extractMessageKey(clprStateProof))
                    .thenReturn(ClprMessageKey.newBuilder().messageId(7L).build());
            final var lastPayload = ClprMessagePayload.newBuilder()
                    .message(ClprMessage.newBuilder()
                            .messageData(Bytes.wrap("msg2".getBytes()))
                            .build())
                    .build();
            stateProofUtils
                    .when(() -> extractMessageValue(clprStateProof))
                    .thenReturn(ClprMessageValue.newBuilder()
                            .payload(lastPayload)
                            .runningHashAfterProcessing(Bytes.wrap("bad-hash".getBytes()))
                            .build());

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_RUNNING_HASH);
        }
    }

    @Test
    @DisplayName("preHandle throws NOT_SUPPORTED if CLPR is disabled")
    void preHandleThrowsIfClprIsDisabled() {
        given(stateProofManager.clprEnabled()).willReturn(false);

        assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", NOT_SUPPORTED);
        verifyNoInteractions(preHandleContext);
    }

    @Test
    @DisplayName("preHandle succeeds if CLPR is enabled")
    void preHandleSucceedsIfClprIsEnabled() throws PreCheckException {
        given(stateProofManager.clprEnabled()).willReturn(true);
        // No specific pre-handle logic to test beyond CLPR enabled check
        subject.preHandle(preHandleContext);
        verifyNoMoreInteractions(preHandleContext);
    }

    @Test
    @DisplayName("handle throws NullPointerException when context is null")
    void handleThrowsForNullContext() {
        assertThatThrownBy(() -> subject.handle(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("handle processes message bundle and updates stores correctly")
    void handleProcessesBundleAndUpdatesStores() throws HandleException {
        final var receivedRunningHash = Bytes.wrap(new byte[RUNNING_HASH_SIZE]);
        final var initialQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(5L)
                .receivedRunningHash(receivedRunningHash)
                .nextMessageId(6L)
                .sentMessageId(0L)
                .sentRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
        writableClprMessageQueueMetadataStore.put(MOCK_LEDGER_ID, initialQueue);

        final var payload1 = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap("msg1".getBytes()))
                        .build())
                .build();
        final var payload2 = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap("msg2".getBytes()))
                        .build())
                .build();
        final var payload3 = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap("msg3".getBytes()))
                        .build())
                .build();

        final var runningHash1 = ClprMessageUtils.nextRunningHash(payload1, receivedRunningHash);
        final var runningHash2 = ClprMessageUtils.nextRunningHash(payload2, runningHash1);
        final var runningHash3 = ClprMessageUtils.nextRunningHash(payload3, runningHash2);

        final var lastMessageKey = ClprMessageKey.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .messageId(8L)
                .build();
        final var lastMessageValue = ClprMessageValue.newBuilder()
                .payload(payload3)
                .runningHashAfterProcessing(runningHash3)
                .build();
        final var stateProof = ClprStateProofUtils.buildLocalClprStateProofWrapper(lastMessageKey, lastMessageValue);
        final var messageBundle = ClprMessageBundle.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .messages(List.of(payload1, payload2))
                .stateProof(stateProof)
                .build();

        final var txBody = TransactionBody.newBuilder()
                .clprProcessMessageBundle(ClprProcessMessageBundleTransactionBody.newBuilder()
                        .messageBundle(messageBundle)
                        .build())
                .build();

        given(handleContext.body()).willReturn(txBody);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageStore.class)).willReturn(writableClprMessageStore);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);

        subject.handle(handleContext);

        final var updatedQueueMetadata = writableClprMessageQueueMetadataStore.get(MOCK_LEDGER_ID);
        assertThat(updatedQueueMetadata).isNotNull();
        assertThat(updatedQueueMetadata.receivedMessageId()).isEqualTo(8L);
        assertThat(updatedQueueMetadata.nextMessageId()).isEqualTo(9L);
        assertThat(updatedQueueMetadata.receivedRunningHash()).isEqualTo(runningHash3);

        assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                        .ledgerId(MOCK_LEDGER_ID)
                        .messageId(6L)
                        .build()))
                .isNotNull();
        assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                        .ledgerId(MOCK_LEDGER_ID)
                        .messageId(7L)
                        .build()))
                .isNotNull();
        assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                        .ledgerId(MOCK_LEDGER_ID)
                        .messageId(8L)
                        .build()))
                .isNotNull();
    }

    @Test
    @DisplayName("handle processes a partially received message bundle")
    void handleAllowsPartiallyReceivedBundle() throws HandleException {
        // create 4 payloads
        final var payload1 = payload("msg1");
        final var payload2 = payload("msg2");
        final var payload3 = payload("msg3");
        final var payload4 = payload("msg4");
        // calculate running hash of 4th msg
        final var initHash = Bytes.wrap(new byte[RUNNING_HASH_SIZE]);
        final var runningHash1 = nextRunningHash(payload1, initHash);
        final var runningHash2 = nextRunningHash(payload2, runningHash1);
        final var runningHash3 = nextRunningHash(payload3, runningHash2);
        final var runningHash4 = nextRunningHash(payload4, runningHash3);
        // build msg key and value for bundle state proof
        final var msg4Key = ClprMessageKey.newBuilder().messageId(4L).build();
        final var msg4Value = ClprMessageValue.newBuilder()
                .payload(payload4)
                .runningHashAfterProcessing(runningHash4)
                .build();
        // mock last received msg id to 2 and it's running hash
        final var initialQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(2L)
                .receivedRunningHash(runningHash2)
                .nextMessageId(3L)
                .sentMessageId(0L)
                .build();

        writableClprMessageQueueMetadataStore.put(MOCK_LEDGER_ID, initialQueue);

        final var stateProof = ClprStateProofUtils.buildLocalClprStateProofWrapper(msg4Key, msg4Value);
        final var messageBundle = ClprMessageBundle.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .messages(List.of(payload1, payload2, payload3))
                .stateProof(stateProof)
                .build();

        final var txBody = TransactionBody.newBuilder()
                .clprProcessMessageBundle(ClprProcessMessageBundleTransactionBody.newBuilder()
                        .messageBundle(messageBundle)
                        .build())
                .build();

        given(handleContext.body()).willReturn(txBody);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageStore.class)).willReturn(writableClprMessageStore);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);

        subject.handle(handleContext);

        final var updatedQueueMetadata = writableClprMessageQueueMetadataStore.get(MOCK_LEDGER_ID);
        assertThat(updatedQueueMetadata).isNotNull();
        assertThat(updatedQueueMetadata.receivedMessageId()).isEqualTo(4L);
        assertThat(updatedQueueMetadata.nextMessageId()).isEqualTo(5L);
        assertThat(updatedQueueMetadata.receivedRunningHash()).isEqualTo(runningHash4);

        assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                        .ledgerId(MOCK_LEDGER_ID)
                        .messageId(3L)
                        .build()))
                .isNotNull();
        assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                        .ledgerId(MOCK_LEDGER_ID)
                        .messageId(4L)
                        .build()))
                .isNotNull();
    }

    private ClprMessagePayload payload(String msg) {
        return ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap(msg.getBytes()))
                        .build())
                .build();
    }
}
