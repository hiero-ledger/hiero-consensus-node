// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NO_PROGRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_PAYLOAD_TOO_LARGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_RUNNING_HASH_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_SERVICE_ACCOUNT_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.contract.ContractCallLocalResponse;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprControlMessage;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.service.clpr.ReadableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.ClprBundleValidator;
import com.hedera.node.app.service.clpr.impl.ClprHashUtils;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierFactory;
import com.hedera.node.app.service.contract.impl.handlers.ContractCallLocalHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractHandlers;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableKVStateBase;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprBundlePrevalidatorTest {
    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final Bytes ZERO_HASH = Bytes.wrap(new byte[32]);
    private static final Bytes PROOF = Bytes.fromHex("010203");
    private static final ContractID VERIFIER =
            ContractID.newBuilder().contractNum(1234).build();
    private static final long VERIFIER_GAS = 5_000_000L;
    private static final ClprChannel CHANNEL = ClprChannel.newBuilder()
            .channelId(CHANNEL_ID)
            .status(ClprChannelStatus.ACTIVE)
            .verifierContract(VERIFIER)
            .trustAnchor(Bytes.fromHex("ab"))
            .channelContext(Bytes.fromHex("cdef"))
            .nextMessageId(3)
            .receivedRunningHash(ZERO_HASH)
            .build();
    private static final ClprLedgerConfiguration LEDGER_CONFIG = ClprLedgerConfiguration.newBuilder()
            .protocolVersion(1)
            .throttles(ClprThrottles.newBuilder()
                    .maxSyncBytes(1024)
                    .maxMessagesPerBundle(10)
                    .maxMessagePayloadBytes(1024))
            .build();
    private static final ClprSyncPayload PAYLOAD = ClprSyncPayload.newBuilder()
            .channelId(CHANNEL_ID)
            .bundlePayload(PROOF)
            .build();

    @Mock
    private Supplier<AutoCloseableWrapper<State>> stateAccessor;

    @Mock
    private State state;

    @Mock
    private ReadableKVStateBase<ProtoBytes, ClprChannel> cachedChannels;

    @Mock
    private ReadableStates clprStates;

    @Mock
    private ReadableStates entityStates;

    @Mock
    private ReadableKVState<ProtoBytes, ClprChannel> channels;

    @Mock
    private ReadableKVState<ClprMessageKey, ClprMessageValue> queue;

    @Mock
    private ReadableSingletonState<ClprLedgerConfiguration> ledgerConfig;

    @Mock
    private Runnable releaseState;

    @Mock
    private Configuration configuration;

    @Mock
    private ClprConfig config;

    @Mock
    private Supplier<ContractHandlers> handlersSupplier;

    @Mock
    private ContractHandlers handlers;

    @Mock
    private ContractCallLocalHandler localHandler;

    @Mock
    private RecordCache recordCache;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private FeeManager feeManager;

    @Mock
    private ClprChannelLifecycle channelLifecycle;

    private final ClprBundleValidator validator = spy(new ClprBundleValidator());
    private final ClprVerifierFactory verifierFactory = spy(new ClprVerifierFactory());
    private ClprBundlePrevalidator subject;

    @BeforeEach
    void setUp() {
        when(configuration.getConfigData(ClprConfig.class)).thenReturn(config);
        lenient().when(config.enabled()).thenReturn(true);
        lenient().when(config.verifierGasLimit()).thenReturn(VERIFIER_GAS);
        lenient().when(stateAccessor.get()).thenAnswer(ignored -> new AutoCloseableWrapper<>(state, releaseState));
        lenient().when(state.getReadableStates(ClprService.NAME)).thenReturn(clprStates);
        lenient().when(state.getReadableStates(EntityIdService.NAME)).thenReturn(entityStates);
        lenient()
                .when(clprStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID))
                .thenReturn(channels);
        lenient()
                .when(clprStates.<ClprMessageKey, ClprMessageValue>get(MESSAGE_QUEUE_STATE_ID))
                .thenReturn(queue);
        lenient()
                .when(clprStates.<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID))
                .thenReturn(ledgerConfig);
        lenient().when(channels.get(new ProtoBytes(CHANNEL_ID))).thenReturn(CHANNEL);
        lenient().when(ledgerConfig.get()).thenReturn(LEDGER_CONFIG);
        lenient().when(handlersSupplier.get()).thenReturn(handlers);
        lenient().when(handlers.contractCallLocalHandler()).thenReturn(localHandler);
        subject = new ClprBundlePrevalidator(
                stateAccessor,
                validator,
                verifierFactory,
                handlersSupplier,
                recordCache,
                exchangeRateManager,
                feeManager,
                () -> channelLifecycle);
    }

    @AfterEach
    void neverRequestsWritableStateOrFeeServices() {
        verify(state, never()).getWritableStates(anyString());
        verifyNoInteractions(recordCache, exchangeRateManager, feeManager);
    }

    @Test
    void rejectsDisabledServiceBeforeAcquiringState() {
        when(config.enabled()).thenReturn(false);

        assertRejected(PAYLOAD, CLPR_NOT_ENABLED);

        verifyNoInteractions(stateAccessor, verifierFactory, handlersSupplier, releaseState);
    }

    @Test
    void rejectsShortChannelIdBeforeAcquiringState() {
        assertRejected(PAYLOAD.copyBuilder().channelId(Bytes.fromHex("01")).build(), INVALID_TRANSACTION_BODY);

        verifyNoInteractions(stateAccessor, verifierFactory, handlersSupplier, releaseState);
    }

    @Test
    void readsTheChannelLiveRatherThanFromThisRoundsReadCache() throws PreCheckException {
        when(clprStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID)).thenReturn(cachedChannels);
        when(cachedChannels.get(new ProtoBytes(CHANNEL_ID))).thenReturn(CHANNEL);
        givenLocalReturn(bundle(List.of(dataPayload())));

        subject.validate(PAYLOAD, configuration);

        final var order = inOrder(cachedChannels);
        order.verify(cachedChannels).reset();
        order.verify(cachedChannels).get(new ProtoBytes(CHANNEL_ID));
    }

    @Test
    void rejectsUnknownChannelAndClosesStateBeforeProofVerification() {
        when(channels.get(new ProtoBytes(CHANNEL_ID))).thenReturn(null);

        assertRejected(PAYLOAD, CLPR_CHANNEL_NOT_FOUND);

        verify(releaseState).run();
        verifyNoInteractions(verifierFactory, handlersSupplier);
    }

    @ParameterizedTest
    @EnumSource(
            value = ClprChannelStatus.class,
            names = {"CLOSED", "PENDING"})
    void rejectsInadmissibleChannelBeforeProofVerification(final ClprChannelStatus status) {
        when(channels.get(new ProtoBytes(CHANNEL_ID)))
                .thenReturn(CHANNEL.copyBuilder().status(status).build());

        assertRejected(PAYLOAD, CLPR_INVALID_CHANNEL_STATUS);

        verify(releaseState).run();
        verifyNoInteractions(verifierFactory, handlersSupplier);
    }

    @Test
    void rejectsOversizedPayloadBeforeResolvingOrCallingVerifier() {
        assertRejected(
                PAYLOAD.copyBuilder().bundlePayload(Bytes.wrap(new byte[1025])).build(), CLPR_PAYLOAD_TOO_LARGE);

        verify(releaseState).run();
        verifyNoInteractions(verifierFactory, handlersSupplier);
    }

    @Test
    void verifiesProofLocallyAndChecksAttestedContentAgainstTheSameReadOnlyState() throws PreCheckException {
        final var content = bundle(List.of(dataPayload()));
        givenLocalReturn(content);

        subject.validate(PAYLOAD, configuration);

        verify(validator).validate(CHANNEL, content, LEDGER_CONFIG);
        verify(validator).validateReplies(eq(CHANNEL), any(), any(ReadableMessageQueueStore.class));
        verify(channelLifecycle).recordPeerObservedManifestVersion(CHANNEL_ID, 9L);
        verify(releaseState).run();
        final var contextCaptor = ArgumentCaptor.forClass(QueryContext.class);
        verify(localHandler).findResponse(contextCaptor.capture(), any());
        final var context = contextCaptor.getValue();
        final var query = context.query().contractCallLocalOrThrow();
        final var expectedCall = Bytes.wrap(new Function("verifyBundle(bytes,bytes,bytes)", "()")
                .encodeCall(Tuple.of(
                        PROOF.toByteArray(),
                        CHANNEL.trustAnchor().toByteArray(),
                        CHANNEL.channelContext().toByteArray()))
                .array());
        assertThat(query.contractID()).isEqualTo(VERIFIER);
        assertThat(query.gas()).isEqualTo(VERIFIER_GAS);
        assertThat(query.functionParameters()).isEqualTo(expectedCall);
        assertThat(context.payer()).isEqualTo(CLPR_SERVICE_ACCOUNT_ID);
        assertThat(context.clprDispatchMetadata().senderId()).isEqualTo(CLPR_SERVICE_ACCOUNT_ID);
        assertThat(context.clprDispatchMetadata().senderAddress()).isEqualTo(CLPR_EVM_ADDRESS_BYTES);
    }

    @ParameterizedTest
    @MethodSource("invalidLocalResponses")
    void rejectsFailedProofExecutionOrMalformedVerifierOutputAndClosesState(final Response response) {
        when(localHandler.findResponse(any(), any())).thenReturn(response);

        assertRejected(PAYLOAD, CLPR_BUNDLE_VERIFICATION_FAILED);

        verify(releaseState).run();
        verifyNoInteractions(channelLifecycle);
    }

    @Test
    void rejectsBadRunningHashBeforeSubmission() {
        final var content = bundle(List.of(dataPayload()));
        givenLocalReturn(content.copyBuilder()
                .metadata(content.metadataOrThrow().copyBuilder().sentRunningHash(ZERO_HASH))
                .build());

        assertRejected(PAYLOAD, CLPR_RUNNING_HASH_MISMATCH);

        verify(releaseState).run();
    }

    @Test
    void rejectsNoProgressWhileRetainingPeersProvenManifestObservation() {
        givenLocalReturn(bundle(List.of()));

        assertRejected(PAYLOAD, CLPR_NO_PROGRESS);

        verify(channelLifecycle).recordPeerObservedManifestVersion(CHANNEL_ID, 9L);
        verify(releaseState).run();
    }

    @Test
    void rejectsUnsupportedControlMessageBeforeSubmission() {
        givenLocalReturn(bundle(List.of(ClprMessagePayload.newBuilder()
                .control(ClprControlMessage.DEFAULT)
                .build())));

        assertRejected(PAYLOAD, CLPR_BUNDLE_VERIFICATION_FAILED);

        verify(releaseState).run();
    }

    @Test
    void rejectsOutOfOrderRepliesAgainstTheOutboundQueue() {
        final var content = bundle(List.of(replyPayload(2), replyPayload(1)));
        givenLocalReturn(content.copyBuilder()
                .metadata(content.metadataOrThrow().copyBuilder().receivedMessageId(2))
                .build());
        when(queue.get(ClprMessageKey.newBuilder()
                        .channelId(CHANNEL_ID)
                        .messageId(1)
                        .build()))
                .thenReturn(ClprMessageValue.newBuilder().payload(dataPayload()).build());

        assertRejected(PAYLOAD, CLPR_BUNDLE_VERIFICATION_FAILED);

        verify(releaseState).run();
    }

    @Test
    void rejectsUnpairedTrustAnchorBeforeRecordingPeerMetadata() {
        givenLocalReturn(bundle(List.of(dataPayload()))
                .copyBuilder()
                .newTrustAnchor(Bytes.fromHex("01"))
                .build());

        assertRejected(PAYLOAD, CLPR_BUNDLE_VERIFICATION_FAILED);

        verifyNoInteractions(channelLifecycle);
        verify(releaseState).run();
    }

    private void assertRejected(final ClprSyncPayload payload, final ResponseCodeEnum code) {
        assertThatThrownBy(() -> subject.validate(payload, configuration))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(code));
    }

    private void givenLocalReturn(final ClprBundleContent content) {
        final var messages = content.messages().stream()
                .map(message -> ClprMessagePayload.PROTOBUF.toBytes(message).toByteArray())
                .toArray(byte[][]::new);
        final var rawResult = Bytes.wrap(ClprVerifierAbi.VERIFY_BUNDLE_RETURN
                .encode(Tuple.of(
                        content.metadata() == null
                                ? ClprVerifierAbi.absentMetadataTuple()
                                : ClprVerifierAbi.metadataTuple(content.metadataOrThrow()),
                        messages,
                        content.newTrustAnchor().toByteArray(),
                        content.newTrustAnchorId().toByteArray(),
                        ClprVerifierAbi.manifestStructTuple(
                                content.newEndpointManifestOrElse(ClprEndpointManifest.DEFAULT))))
                .array());
        when(localHandler.findResponse(any(), any())).thenReturn(localResponse(SUCCESS, rawResult));
    }

    private static Stream<Response> invalidLocalResponses() {
        return Stream.of(
                localResponse(CONTRACT_REVERT_EXECUTED, Bytes.EMPTY),
                localResponse(SUCCESS, null),
                localResponse(SUCCESS, Bytes.EMPTY),
                localResponse(SUCCESS, Bytes.fromHex("ffff")));
    }

    private static Response localResponse(final ResponseCodeEnum status, final Bytes result) {
        final var response = ContractCallLocalResponse.newBuilder()
                .header(ResponseHeader.newBuilder().nodeTransactionPrecheckCode(status));
        if (result != null) {
            response.functionResult(ContractFunctionResult.newBuilder().contractCallResult(result));
        }
        return Response.newBuilder().contractCallLocal(response).build();
    }

    private static ClprBundleContent bundle(final List<ClprMessagePayload> messages) {
        var hash = ZERO_HASH;
        for (final var message : messages) {
            hash = ClprHashUtils.computeRunningHash(hash, message);
        }
        return ClprBundleContent.newBuilder()
                .messages(messages)
                .metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(messages.size() + 1)
                        .receivedMessageId(0)
                        .receivedRunningHash(ZERO_HASH)
                        .sentRunningHash(hash)
                        .status(ClprChannelStatus.ACTIVE)
                        .endpointManifestVersion(9))
                .build();
    }

    private static ClprMessagePayload dataPayload() {
        return ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder().messageData(Bytes.fromHex("01")))
                .build();
    }

    private static ClprMessagePayload replyPayload(final long id) {
        return ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder().messageId(id))
                .build();
    }
}
