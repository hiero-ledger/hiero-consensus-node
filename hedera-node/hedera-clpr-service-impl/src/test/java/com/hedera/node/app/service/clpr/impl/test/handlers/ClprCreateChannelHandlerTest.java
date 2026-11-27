// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_VERIFIER_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.test.handlers.ChannelIndexStubs.stubChannelIndex;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.lenient;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprCreateChannelTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.ClprChannelId;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.ClprGasAccounting;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprCreateChannelHandler;
import com.hedera.node.app.service.clpr.impl.test.verifier.PassThroughClprVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierFactory;
import com.hedera.node.app.service.clpr.impl.verifier.VerifiedConfig;
import com.hedera.node.app.service.contract.api.SmartContractServiceApi;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.ResourcePriceCalculator;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.ContractGasUsage;
import com.hedera.node.app.spi.workflows.FunctionalityResourcePrices;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.RollbackAwareException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprCreateChannelHandlerTest {

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1001).build();
    private static final ContractID VERIFIER_CONTRACT_ID =
            ContractID.newBuilder().shardNum(0).realmNum(0).contractNum(5001).build();
    private static final Bytes PEER_TRUST_ANCHOR = Bytes.wrap(
            new byte[] {(byte) 0xab, (byte) 0xcd, (byte) 0xef, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08});

    private static final ClprLedgerConfiguration PEER_CONFIG = ClprLedgerConfiguration.newBuilder()
            .protocolVersion(1)
            .chainId("eip155:1")
            .serviceAddress(Bytes.wrap(new byte[] {0x01, 0x02, 0x03}))
            .timestamp(Timestamp.newBuilder().seconds(1000).build())
            .throttles(ClprThrottles.DEFAULT)
            .initialTrustAnchor(PEER_TRUST_ANCHOR)
            .initialTrustAnchorId(PEER_TRUST_ANCHOR)
            .build();

    private static final String LOCAL_CHAIN_ID = "hedera:testnet";
    private static final Bytes SALT = Bytes.wrap(new byte[32]);
    private static final Bytes PEER_VERIFIER = Bytes.fromHex("00112233445566778899aabbccddeeff00112233");
    // Produced by ChannelLogic._deriveChannelId in clpr-smart-contracts #622.
    private static final Bytes CHANNEL_ID =
            Bytes.fromHex("bb082a20892923291a09d2402beeedc74d1077bf44ba67d4b33cede9495688a2");

    @Test
    void derivesTheSameIdFromEitherLedgerIncludingWhenChainAndServiceMatch() {
        final var verifier = Bytes.wrap(asEvmAddress(5001));
        assertThat(ClprChannelId.derive(
                        PEER_CONFIG.chainId(),
                        PEER_CONFIG.serviceAddress(),
                        PEER_VERIFIER,
                        LOCAL_CHAIN_ID,
                        CLPR_EVM_ADDRESS_BYTES,
                        verifier,
                        SALT))
                .isEqualTo(CHANNEL_ID);
        final var otherVerifier = Bytes.fromHex("ff");
        // Solidity vector with equal chain and service: the verifier hashes break the tie.
        final var expected = Bytes.fromHex("63c19f9211fb8e9c2d094a1bde7992e39fa80892666b644451fab75be8077710");
        assertThat(ClprChannelId.derive(
                        LOCAL_CHAIN_ID,
                        CLPR_EVM_ADDRESS_BYTES,
                        verifier,
                        LOCAL_CHAIN_ID,
                        CLPR_EVM_ADDRESS_BYTES,
                        otherVerifier,
                        SALT))
                .isEqualTo(expected);
        assertThat(ClprChannelId.derive(
                        LOCAL_CHAIN_ID,
                        CLPR_EVM_ADDRESS_BYTES,
                        otherVerifier,
                        LOCAL_CHAIN_ID,
                        CLPR_EVM_ADDRESS_BYTES,
                        verifier,
                        SALT))
                .isEqualTo(expected);
    }

    private static Bytes channelIdForVerifier(final ContractID verifierId) {
        final var address = verifierId.hasEvmAddress()
                ? verifierId.evmAddressOrThrow()
                : Bytes.wrap(asEvmAddress(verifierId.contractNumOrThrow()));
        return ClprChannelId.derive(
                LOCAL_CHAIN_ID,
                CLPR_EVM_ADDRESS_BYTES,
                address,
                PEER_CONFIG.chainId(),
                PEER_CONFIG.serviceAddress(),
                PEER_VERIFIER,
                SALT);
    }

    @Mock
    private ClprVerifierFactory verifierFactory;

    @Mock
    private ClprChannelLifecycle channelLifecycle;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ReadableLedgerConfigurationStore localConfigStore;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private SmartContractServiceApi smartContractServiceApi;

    @Mock
    private ReadableStates readableStates;

    @Mock
    private WritableStates writableStates;

    private ClprCreateChannelHandler subject;
    private WritableChannelStore channelStore;

    @BeforeEach
    void setUp() {
        subject = new ClprCreateChannelHandler(verifierFactory, channelLifecycle);
        lenient().when(verifierFactory.getVerifier(any())).thenReturn(new PassThroughClprVerifier());

        // Channel state
        final var readableChannels = MapReadableKVState.<ProtoBytes, ClprChannel>builder(
                        CHANNELS_STATE_ID, "ClprService:CHANNELS")
                .build();
        final var writableChannels = MapWritableKVState.<ProtoBytes, ClprChannel>builder(
                        CHANNELS_STATE_ID, "ClprService:CHANNELS")
                .build();
        lenient()
                .when(readableStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID))
                .thenReturn(readableChannels);
        lenient()
                .when(writableStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID))
                .thenReturn(writableChannels);
        stubChannelIndex(writableStates);
        channelStore = new WritableChannelStore(writableStates);
    }

    @Test
    void rejectsWrongChannelIdLength() {
        final var op = ClprCreateChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[16]))
                .salt(SALT)
                .peerVerifier(PEER_VERIFIER)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.wrap(new byte[] {1}))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void rejectsWrongSaltLength() {
        final var op = ClprCreateChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[32]))
                .salt(Bytes.wrap(new byte[31]))
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.wrap(new byte[] {1}))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void rejectsMissingVerifierContract() {
        final var op = ClprCreateChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[32]))
                .salt(SALT)
                .peerVerifier(PEER_VERIFIER)
                .configProofBytes(Bytes.wrap(new byte[] {1}))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void rejectsEmptyConfigProofBytes() {
        final var op = ClprCreateChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[32]))
                .salt(SALT)
                .peerVerifier(PEER_VERIFIER)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.EMPTY)
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void passesWithValidFields() throws PreCheckException {
        given(pureChecksContext.body()).willReturn(validOpenTxn());
        subject.pureChecks(pureChecksContext);
    }

    @Test
    void rejectsWhenClprNotEnabled() {
        final var disabledConfig = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", false)
                .getOrCreateConfig();
        setupHandleContext(validOpenTxn(), disabledConfig);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    void rejectsExistingChannel() {
        channelStore.put(ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(ClprChannelStatus.ACTIVE)
                .build());
        setupHandleContext(validOpenTxn());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CHANNEL_ALREADY_EXISTS));
    }

    @Test
    void rejectsNonExistentVerifierContract() {
        setupHandleContext(validOpenTxn());
        lenient().when(accountStore.getContractById(VERIFIER_CONTRACT_ID)).thenReturn(null);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_VERIFIER_CONTRACT));
    }

    @Test
    void rejectsNonContractVerifier() {
        setupHandleContext(validOpenTxn());
        setupVerifierAccount(false);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_VERIFIER_CONTRACT));
    }

    @Test
    void rejectsInvalidConfigProof() {
        final var op = ClprCreateChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .salt(SALT)
                .peerVerifier(PEER_VERIFIER)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.wrap(new byte[] {(byte) 0xFF, (byte) 0xFF}))
                .build();
        setupHandleContext(txnWith(op));
        setupVerifierAccount(true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_VERIFIER_CONFIG_FAILED));
    }

    @Test
    void opensChannelWithoutPriorCommitment() {
        setupHandleContext(validOpenTxn());
        setupVerifierAccount(true);

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(CHANNEL_ID);
        assertThat(stored).isNotNull();
        assertThat(stored.status()).isEqualTo(ClprChannelStatus.ACTIVE);
        assertThat(stored.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(stored.chainId()).isEqualTo("eip155:1");
        assertThat(stored.verifierContract()).isEqualTo(VERIFIER_CONTRACT_ID);
        assertThat(stored.nextMessageId()).isEqualTo(1L);
        // Channel.trust_anchor / trust_anchor_id are seeded from the proven config's
        // initial_trust_anchor / initial_trust_anchor_id, which the source ledger populates
        // with its own ledger_id at ClprUpdateLedgerConfiguration time.
        assertThat(stored.trustAnchor()).isEqualTo(PEER_TRUST_ANCHOR);
        assertThat(stored.trustAnchorId()).isEqualTo(PEER_TRUST_ANCHOR);
        assertThat(stored.salt()).isEqualTo(SALT);
        assertThat(stored.ownershipCommitment()).isEqualTo(Bytes.EMPTY);
        // Manifest mode: the (empty) endpoint_manifest_proof_bytes yields an empty manifest, which
        // is attached to the channel — ClprEndpointManifest permits an empty list at version >= 1
        // and later manifest-update bundles populate it (spec §4.7).
        assertThat(stored.hasEndpointManifest()).isTrue();
        assertThat(stored.endpointManifestOrThrow().endpoints()).isEmpty();
        then(channelLifecycle).should().onChannelActivated(CHANNEL_ID);
    }

    @ParameterizedTest
    @MethodSource("builtInVerifiers")
    void storesFixedFingerprintForBuiltInVerifier(final ContractID verifierId, final Bytes expectedFingerprint) {
        final var op = validOpenTxn()
                .clprCreateChannelOrThrow()
                .copyBuilder()
                .verifierContract(verifierId)
                .channelId(channelIdForVerifier(verifierId))
                .build();
        setupHandleContext(txnWith(op));

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(op.channelId());
        assertThat(stored.verifierContract()).isEqualTo(verifierId);
        assertThat(stored.verifierFingerprint()).isEqualTo(expectedFingerprint);
        then(accountStore).shouldHaveNoInteractions();
        then(smartContractServiceApi).shouldHaveNoInteractions();
    }

    private static Stream<Arguments> builtInVerifiers() {
        // Pin the public identities so changing a permanent label cannot silently change persisted hashes.
        return Map.of(
                        0x16eL, "9a0239cbc7be7e16347b8bfe6ab37f7a55d4eb688a0c8b6b141cc5035acac51e",
                        0x16fL, "9bb5a67f672529e6928db26e11d0a93f56f2f3581fda619f737e3e1615c3e909",
                        0x171L, "962abc985d9e3cd97cc8598b2399f85e948187d8e56cbb988b4dad04e60c398c",
                        0x173L, "8779a561b39aa363dda50c36f7df7e7e11262d62a1c3114752f5691271d984a9")
                .entrySet()
                .stream()
                .flatMap(entry -> Stream.of(
                        Arguments.of(verifierContractId(entry.getKey(), false), Bytes.fromHex(entry.getValue())),
                        Arguments.of(verifierContractId(entry.getKey(), true), Bytes.fromHex(entry.getValue()))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsTheUnsupportedSeiVerifier(final boolean useEvmAddress) {
        // 0x170 is not a built-in verifier: without a smart-contract account it is rejected like any other address
        final var seiVerifierId = verifierContractId(0x170L, useEvmAddress);
        final var op = validOpenTxn()
                .clprCreateChannelOrThrow()
                .copyBuilder()
                .verifierContract(seiVerifierId)
                .channelId(channelIdForVerifier(seiVerifierId))
                .build();
        setupHandleContext(txnWith(op));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_VERIFIER_CONTRACT));
        assertThat(channelStore.getChannel(op.channelId())).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storesDeployedBytecodeHashForCustomVerifier(final boolean useEvmAddress) {
        final var verifierId = verifierContractId(5001L, useEvmAddress);
        final var op = validOpenTxn()
                .clprCreateChannelOrThrow()
                .copyBuilder()
                .verifierContract(verifierId)
                .channelId(channelIdForVerifier(verifierId))
                .build();
        setupHandleContext(txnWith(op));
        given(accountStore.getContractById(verifierId))
                .willReturn(Account.newBuilder()
                        .accountId(AccountID.newBuilder().accountNum(5001))
                        .smartContract(true)
                        .build());
        given(smartContractServiceApi.getContractBytecode(verifierId)).willReturn(Bytes.fromHex("60006000f3"));

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).verifierFingerprint())
                .isEqualTo(Bytes.fromHex("d003426e799329b8dca093f3bbab55a5e4e9f3c40160fc942068eef712ae88ad"));
    }

    @ParameterizedTest
    @MethodSource("missingBytecode")
    void retainsZeroFingerprintForCustomVerifierWithoutBytecode(final Bytes bytecode) {
        setupHandleContext(validOpenTxn());
        setupVerifierAccount(true);
        given(smartContractServiceApi.getContractBytecode(VERIFIER_CONTRACT_ID)).willReturn(bytecode);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).verifierFingerprint()).isEqualTo(Bytes.wrap(new byte[32]));
    }

    private static Stream<Bytes> missingBytecode() {
        return Stream.of(null, Bytes.EMPTY);
    }

    private static ContractID verifierContractId(final long number, final boolean useEvmAddress) {
        return useEvmAddress
                ? ContractID.newBuilder()
                        .evmAddress(Bytes.wrap(ByteBuffer.allocate(20)
                                .position(12)
                                .putLong(number)
                                .array()))
                        .build()
                : ContractID.newBuilder().contractNum(number).build();
    }

    @Test
    void handleStoresPeerManifestFromProofBytes() {
        // Non-empty endpoint_manifest_proof_bytes → PassThroughClprVerifier parses them
        // as a real manifest, its endpoints satisfy the gate, and the Channel carries the same
        // manifest bytes-for-bytes.
        final var peerManifest = ClprEndpointManifest.newBuilder()
                .version(7L)
                .serviceAddress(PEER_CONFIG.serviceAddress())
                .endpoints(List.of(ClprEndpoint.newBuilder()
                        .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                                .ipAddress("10.0.0.1")
                                .port(50211)
                                .build())
                        .tlsCertificate(Bytes.wrap(new byte[] {9, 9, 9}))
                        .accountId(Bytes.wrap(new byte[] {1, 2, 3}))
                        .build()))
                .build();
        final var op = ClprCreateChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .salt(SALT)
                .peerVerifier(PEER_VERIFIER)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .endpointManifestProofBytes(ClprEndpointManifest.PROTOBUF.toBytes(peerManifest))
                .build();
        setupHandleContext(txnWith(op));
        setupVerifierAccount(true);

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(CHANNEL_ID);
        assertThat(stored).isNotNull();
        assertThat(stored.status()).isEqualTo(ClprChannelStatus.ACTIVE);
        assertThat(stored.endpointManifestVersion()).isEqualTo(7L);
        assertThat(stored.endpointManifestOrThrow()).isEqualTo(peerManifest);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void verifierGasIsRefundedOnSuccessAndPreservedAfterLateFailure(final int failureMode) {
        final boolean invalidManifest = failureMode == 1;
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(7)
                .serviceAddress(invalidManifest ? Bytes.wrap(new byte[16_384]) : PEER_CONFIG.serviceAddress())
                .build();
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", true)
                .withValue("clpr.verifierGasLimit", 1000L)
                .withValue("contracts.maxGasPerTransaction", 1000L)
                .getOrCreateConfig();
        setupHandleContext(validOpenTxnWithManifest(manifest), config);
        setupVerifierAccount(true);
        givenUnitGasPrice(handleContext);
        given(handleContext.tryToCharge(PAYER_ID, 1000L)).willReturn(true);
        final var result = new ContractGasUsage();
        result.recordGasUsed(400L);
        given(verifierFactory.getVerifier(any())).willReturn(new PassThroughClprVerifier() {
            @Override
            public VerifiedConfig verifyConfig(
                    final Bytes proof, final Bytes channelId, final Bytes manifestProof, final HandleContext context) {
                final var accounting = ClprGasAccounting.forContext(context);
                assertThat(accounting.prepayVerifier(context.payer(), 1000L)).isTrue();
                accounting.settleVerifierWithGasUsage(context.payer(), 1000L, result);
                return super.verifyConfig(proof, channelId, manifestProof, context);
            }
        });

        if (failureMode == 2) {
            given(smartContractServiceApi.getContractBytecode(VERIFIER_CONTRACT_ID))
                    .willThrow(new IllegalStateException("Unexpected store failure after verifier execution"));
        }
        if (failureMode > 0) {
            final var rollbackContext = mock(HandleContext.class);
            if (failureMode == 1) {
                final var failure = org.junit.jupiter.api.Assertions.assertThrows(
                        HandleException.class, () -> subject.handle(handleContext));
                assertThat(failure.getStatus()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
                failure.maybeReplay(mock(FeeCharging.Context.class), rollbackContext);
            } else {
                final var failure = org.junit.jupiter.api.Assertions.assertThrows(
                        RollbackAwareException.class, () -> subject.handle(handleContext));
                assertThat(failure.getCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Unexpected store failure after verifier execution");
                failure.maybeReplay(mock(FeeCharging.Context.class), rollbackContext);
            }
            verify(rollbackContext).tryToCharge(PAYER_ID, 400L);
            verify(handleContext, never()).refundHandlerCharge(any(), org.mockito.ArgumentMatchers.anyLong());
            assertThat(channelStore.getChannel(CHANNEL_ID)).isNull();
        } else {
            subject.handle(handleContext);
            verify(handleContext).refundHandlerCharge(PAYER_ID, 600L);
        }
    }

    @Test
    void unexpectedFailureBeforeEvmWorkPropagatesWithoutChangingWorkflowFeeSemantics() {
        setupHandleContext(validOpenTxn());
        final var failure = new IllegalStateException("Unexpected store failure before verifier execution");
        given(storeFactory.writableStore(WritableChannelStore.class)).willThrow(failure);

        assertThatThrownBy(() -> subject.handle(handleContext)).isSameAs(failure);

        verify(verifierFactory, never()).getVerifier(any());
        verify(handleContext, never()).tryToCharge(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    private static void givenUnitGasPrice(final HandleContext context) {
        given(context.dispatchMetadata()).willReturn(new HandleContext.DispatchMetadata(new HashMap<>()));
        final var stack = mock(HandleContext.SavepointStack.class);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(StreamBuilder.class)).willReturn(mock(StreamBuilder.class));
        given(context.getGasPriceInTinycents()).willReturn(1L);
        final var prices = mock(ResourcePriceCalculator.class);
        given(context.resourcePriceCalculator()).willReturn(prices);
        given(prices.resourcePricesFor(CONTRACT_CALL, DEFAULT))
                .willReturn(new FunctionalityResourcePrices(FeeData.DEFAULT, 1L));
        final var rates = mock(ExchangeRateInfo.class);
        given(context.exchangeRateInfo()).willReturn(rates);
        given(rates.activeRate(any()))
                .willReturn(ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(1).build());
    }

    @Test
    void oversizedVerifierCertificateIsNeitherStoredNorCached() {
        final var oversized = ClprEndpoint.newBuilder()
                .tlsCertificate(Bytes.wrap(new byte[65_536]))
                .build();
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(7)
                .serviceAddress(PEER_CONFIG.serviceAddress())
                .endpoints(oversized)
                .build();
        setupHandleContext(validOpenTxnWithManifest(manifest));
        setupVerifierAccount(true);

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).endpointManifestOrThrow().endpoints())
                .isEmpty();
    }

    @Test
    void handleAllowsEmptyManifestAtChannelCreation() {
        // The manifest is the authoritative endpoint source. An empty manifest proof
        // yields an empty manifest, which ClprEndpointManifest explicitly permits at version >= 1
        // (genesis creates exactly that); later manifest-update bundles populate it. So the
        // channel is accepted ACTIVE with an empty stored manifest — NOT rejected.
        final var op = ClprCreateChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .salt(SALT)
                .peerVerifier(PEER_VERIFIER)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .endpointManifestProofBytes(Bytes.EMPTY)
                .build();
        setupHandleContext(txnWith(op));
        setupVerifierAccount(true);

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(CHANNEL_ID);
        assertThat(stored).isNotNull();
        assertThat(stored.status()).isEqualTo(ClprChannelStatus.ACTIVE);
        assertThat(stored.hasEndpointManifest()).isTrue();
        assertThat(stored.endpointManifestOrThrow().endpoints()).isEmpty();
    }

    @Test
    void handleRollsBackAllStateOnInvalidManifestProof() {
        // Invalid endpoint_manifest_proof_bytes → verifier throws → no Channel persisted
        // An invalid proof must leave channel state untouched.
        final var op = ClprCreateChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .salt(SALT)
                .peerVerifier(PEER_VERIFIER)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                // Garbage bytes — PassThroughClprVerifier fails to parse as ClprEndpointManifest.
                .endpointManifestProofBytes(Bytes.wrap(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}))
                .build();
        setupHandleContext(txnWith(op));
        setupVerifierAccount(true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_VERIFIER_CONFIG_FAILED));

        assertThat(channelStore.getChannel(CHANNEL_ID)).isNull();
        then(channelLifecycle).should(never()).onChannelActivated(any());
    }

    @Test
    void doesNotNotifyLifecycleWhenHandleFails() {
        final var op = validOpenTxn()
                .clprCreateChannelOrThrow()
                .copyBuilder()
                .peerVerifier(Bytes.fromHex("ff"))
                .build();
        setupHandleContext(txnWith(op));
        setupVerifierAccount(true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
        assertThat(channelStore.getChannel(CHANNEL_ID)).isNull();

        then(channelLifecycle).should(never()).onChannelActivated(any());
    }

    @Test
    void initializesQueueMetadata() {
        setupHandleContext(validOpenTxn());
        setupVerifierAccount(true);

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(CHANNEL_ID);
        assertThat(stored).isNotNull();
        assertThat(stored.nextMessageId()).isEqualTo(1L);
        assertThat(stored.ackedMessageId()).isEqualTo(0L);
        assertThat(stored.sentRunningHash()).isEqualTo(Bytes.wrap(new byte[32]));
        assertThat(stored.receivedMessageId()).isEqualTo(0L);
        assertThat(stored.receivedRunningHash()).isEqualTo(Bytes.wrap(new byte[32]));
    }

    @Test
    void storesPeerConfigTimestamp() {
        setupHandleContext(validOpenTxn());
        setupVerifierAccount(true);

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(CHANNEL_ID);
        assertThat(stored).isNotNull();
        assertThat(stored.peerConfigTimestamp()).isEqualTo(PEER_CONFIG.timestamp());
        assertThat(stored.peerThrottles()).isEqualTo(PEER_CONFIG.throttles());
    }

    @Test
    void storesAllPeerEndpointsWhenMaxPeerEndpointsIsZero() {
        final var peerManifest = manifestWithEndpointCount(12);
        setupHandleContext(validOpenTxnWithManifest(peerManifest));
        setupVerifierAccount(true);

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(CHANNEL_ID);
        assertThat(stored).isNotNull();
        assertThat(stored.endpointManifestOrThrow().endpoints()).isEqualTo(peerManifest.endpoints());
    }

    @Test
    void truncatesPeerEndpointsWhenMaxPeerEndpointsIsNonZero() {
        final var peerManifest = manifestWithEndpointCount(5);
        setupHandleContext(validOpenTxnWithManifest(peerManifest), enabledConfig(), localConfigWithMaxPeerEndpoints(3));
        setupVerifierAccount(true);

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(CHANNEL_ID);
        assertThat(stored).isNotNull();
        assertThat(stored.endpointManifestOrThrow().endpoints())
                .isEqualTo(peerManifest.endpoints().subList(0, 3));
    }

    @Test
    void rejectsPeerConfigWithNegativeTimestampSeconds() {
        final var peerConfig = PEER_CONFIG
                .copyBuilder()
                .timestamp(Timestamp.newBuilder().seconds(-1).nanos(0).build())
                .build();
        setupHandleContext(validOpenTxnWithPeerConfig(peerConfig));
        setupVerifierAccount(true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_VERIFIER_CONFIG_FAILED));
    }

    @Test
    void rejectsPeerConfigWithNanosOutOfRange() {
        final var peerConfig = PEER_CONFIG
                .copyBuilder()
                .timestamp(Timestamp.newBuilder()
                        .seconds(1000)
                        .nanos(1_000_000_000)
                        .build())
                .build();
        setupHandleContext(validOpenTxnWithPeerConfig(peerConfig));
        setupVerifierAccount(true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_VERIFIER_CONFIG_FAILED));
    }

    @Test
    void createChannelAcceptsCantonBuiltInVerifier() {
        final var cantonCid = ContractID.newBuilder().contractNum(0x173L).build();
        final var op = validOpenTxn()
                .clprCreateChannelOrThrow()
                .copyBuilder()
                .verifierContract(cantonCid)
                .channelId(channelIdForVerifier(cantonCid))
                .build();
        setupHandleContext(txnWith(op));

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(op.channelId());
        assertThat(stored).isNotNull();
        assertThat(stored.verifierContract()).isEqualTo(cantonCid);
        assertThat(stored.verifierFingerprint())
                .isEqualTo(Bytes.fromHex("8779a561b39aa363dda50c36f7df7e7e11262d62a1c3114752f5691271d984a9"));
        then(accountStore).shouldHaveNoInteractions();
        then(smartContractServiceApi).shouldHaveNoInteractions();
    }

    private void setupHandleContext(final TransactionBody txn) {
        setupHandleContext(txn, enabledConfig());
    }

    private void setupHandleContext(final TransactionBody txn, final Configuration configuration) {
        setupHandleContext(txn, configuration, localConfigWithMaxPeerEndpoints(0));
    }

    private void setupHandleContext(
            final TransactionBody txn,
            final Configuration configuration,
            final ClprLedgerConfiguration localLedgerConfig) {
        lenient().when(handleContext.body()).thenReturn(txn);
        lenient().when(handleContext.payer()).thenReturn(PAYER_ID);
        lenient().when(handleContext.configuration()).thenReturn(configuration);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient().when(handleContext.consensusNow()).thenReturn(java.time.Instant.EPOCH);
        lenient().when(storeFactory.writableStore(WritableChannelStore.class)).thenReturn(channelStore);
        lenient().when(storeFactory.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
        lenient()
                .when(storeFactory.readableStore(ReadableLedgerConfigurationStore.class))
                .thenReturn(localConfigStore);
        lenient().when(localConfigStore.getConfiguration()).thenReturn(localLedgerConfig);
        lenient().when(storeFactory.serviceApi(SmartContractServiceApi.class)).thenReturn(smartContractServiceApi);
    }

    private void setupVerifierAccount(final boolean isSmartContract) {
        final var account = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(5001))
                .smartContract(isSmartContract)
                .build();
        given(accountStore.getContractById(VERIFIER_CONTRACT_ID)).willReturn(isSmartContract ? account : null);
    }

    private TransactionBody validOpenTxn() {
        return validOpenTxnWithPeerConfig(PEER_CONFIG);
    }

    private TransactionBody validOpenTxnWithPeerConfig(final ClprLedgerConfiguration peerConfig) {
        final var op = ClprCreateChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .salt(SALT)
                .peerVerifier(PEER_VERIFIER)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(peerConfig))
                .build();
        return txnWith(op);
    }

    private TransactionBody validOpenTxnWithManifest(final ClprEndpointManifest peerManifest) {
        final var op = ClprCreateChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .salt(SALT)
                .peerVerifier(PEER_VERIFIER)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .endpointManifestProofBytes(ClprEndpointManifest.PROTOBUF.toBytes(peerManifest))
                .build();
        return txnWith(op);
    }

    private static Configuration enabledConfig() {
        return HederaTestConfigBuilder.create().withValue("clpr.enabled", true).getOrCreateConfig();
    }

    private static ClprLedgerConfiguration localConfigWithMaxPeerEndpoints(final int maxPeerEndpoints) {
        return ClprLedgerConfiguration.newBuilder()
                .chainId(LOCAL_CHAIN_ID)
                .throttles(ClprThrottles.newBuilder()
                        .maxPeerEndpoints(maxPeerEndpoints)
                        .build())
                .build();
    }

    private static ClprEndpointManifest manifestWithEndpointCount(final int count) {
        final var endpoints = new ArrayList<ClprEndpoint>();
        for (int i = 0; i < count; i++) {
            endpoints.add(ClprEndpoint.newBuilder()
                    .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                            .ipAddress("10.0.0." + i)
                            .port(50211)
                            .build())
                    .tlsCertificate(Bytes.wrap(new byte[] {(byte) (i + 1)}))
                    .build());
        }
        return ClprEndpointManifest.newBuilder()
                .version(1L)
                .serviceAddress(PEER_CONFIG.serviceAddress())
                .endpoints(endpoints)
                .build();
    }

    private TransactionBody txnWith(final ClprCreateChannelTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprCreateChannel(op)
                .build();
    }
}
