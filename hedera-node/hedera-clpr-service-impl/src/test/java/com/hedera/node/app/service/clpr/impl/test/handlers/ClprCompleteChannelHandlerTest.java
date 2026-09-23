// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_COMMITMENT_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_VERIFIER_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.hapi.utils.keys.Ed25519Utils.keyFrom;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.PENDING_COMMITMENTS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.lenient;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprCompleteChannelTransactionBody;
import com.hedera.hapi.node.clpr.ClprSignatureScheme;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.WritablePendingCommitmentStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprCompleteChannelHandler;
import com.hedera.node.app.service.clpr.impl.test.verifier.PassThroughClprVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierFactory;
import com.hedera.node.app.service.contract.api.SmartContractServiceApi;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.sun.jna.ptr.IntByReference;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.i2p.crypto.eddsa.EdDSAEngine;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprCompleteChannelHandlerTest {

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
            .endpoints(List.of(ClprEndpoint.DEFAULT))
            .initialTrustAnchor(PEER_TRUST_ANCHOR)
            .initialTrustAnchorId(PEER_TRUST_ANCHOR)
            .build();

    private static final byte[] SECRET_KEY = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
    };

    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[] {
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42
    });

    private static final byte[] ECDSA_PUBLIC_KEY = CryptoTestHelpers.deriveEcdsaPublicKey(SECRET_KEY);
    private static final Bytes ECDSA_COMMITMENT = computeCommitment(CHANNEL_ID, Bytes.wrap(ECDSA_PUBLIC_KEY));

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

    private ClprCompleteChannelHandler subject;
    private WritableChannelStore channelStore;
    private WritablePendingCommitmentStore commitmentStore;

    @BeforeEach
    void setUp() {
        subject = new ClprCompleteChannelHandler(verifierFactory, channelLifecycle);
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
        channelStore = new WritableChannelStore(writableStates);

        // Pending commitments state
        final var writableCommitments = MapWritableKVState.<ProtoBytes, ProtoBytes>builder(
                        PENDING_COMMITMENTS_STATE_ID, "ClprService:PENDING_COMMITMENTS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ProtoBytes>get(PENDING_COMMITMENTS_STATE_ID))
                .thenReturn(writableCommitments);
        commitmentStore = new WritablePendingCommitmentStore(writableStates);
    }

    @Test
    void rejectsWrongChannelIdLength() {
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[16]))
                .publicKey(Bytes.wrap(new byte[64]))
                .signature(Bytes.wrap(new byte[64]))
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.wrap(new byte[] {1}))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void rejectsWrongEcdsaPublicKeyLength() {
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[32]))
                .publicKey(Bytes.wrap(new byte[33]))
                .signature(Bytes.wrap(new byte[64]))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.wrap(new byte[] {1}))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void rejectsWrongEd25519PublicKeyLength() {
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[32]))
                .publicKey(Bytes.wrap(new byte[64]))
                .signature(Bytes.wrap(new byte[64]))
                .signatureScheme(ClprSignatureScheme.ED25519)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.wrap(new byte[] {1}))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void rejectsWrongSignatureLength() {
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[32]))
                .publicKey(Bytes.wrap(new byte[64]))
                .signature(Bytes.wrap(new byte[32]))
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
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[32]))
                .publicKey(Bytes.wrap(new byte[64]))
                .signature(Bytes.wrap(new byte[64]))
                .configProofBytes(Bytes.wrap(new byte[] {1}))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void rejectsEmptyConfigProofBytes() {
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[32]))
                .publicKey(Bytes.wrap(new byte[64]))
                .signature(Bytes.wrap(new byte[64]))
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.EMPTY)
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void rejectsUnrecognizedSignatureScheme() {
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[32]))
                .publicKey(Bytes.wrap(new byte[64]))
                .signature(Bytes.wrap(new byte[64]))
                .signatureScheme(ClprSignatureScheme.fromProtobufOrdinal(999))
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.wrap(new byte[] {1}))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void passesWithValidEcdsaFields() throws PreCheckException {
        given(pureChecksContext.body()).willReturn(validEcdsaTxn());
        subject.pureChecks(pureChecksContext);
    }

    @Test
    void passesWithValidEd25519Fields() throws PreCheckException {
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[32]))
                .publicKey(Bytes.wrap(new byte[32]))
                .signature(Bytes.wrap(new byte[64]))
                .signatureScheme(ClprSignatureScheme.ED25519)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.wrap(new byte[] {1}))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));
        subject.pureChecks(pureChecksContext);
    }

    @Test
    void rejectsWhenClprNotEnabled() {
        commitmentStore.put(ECDSA_COMMITMENT);
        final var disabledConfig = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", false)
                .getOrCreateConfig();
        setupHandleContext(validEcdsaTxn(), disabledConfig);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    void rejectsNoMatchingCommitment() {
        // Don't seed any commitment
        setupHandleContext(validEcdsaTxn());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_COMMITMENT_MISMATCH));
    }

    @Test
    void rejectsExistingChannel() {
        commitmentStore.put(ECDSA_COMMITMENT);
        channelStore.put(ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(ClprChannelStatus.ACTIVE)
                .build());
        setupHandleContext(validEcdsaTxn());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CHANNEL_ALREADY_EXISTS));
    }

    @Test
    void rejectsInvalidEcdsaSignature() {
        commitmentStore.put(ECDSA_COMMITMENT);
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(ECDSA_PUBLIC_KEY))
                .signature(Bytes.wrap(new byte[64]))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .build();
        setupHandleContext(txnWith(op));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_SIGNATURE));
    }

    @Test
    void rejectsNonExistentVerifierContract() {
        commitmentStore.put(ECDSA_COMMITMENT);
        setupHandleContext(validEcdsaTxn());
        lenient().when(accountStore.getContractById(VERIFIER_CONTRACT_ID)).thenReturn(null);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_VERIFIER_CONTRACT));
    }

    @Test
    void rejectsNonContractVerifier() {
        commitmentStore.put(ECDSA_COMMITMENT);
        setupHandleContext(validEcdsaTxn());
        setupVerifierAccount(false);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_VERIFIER_CONTRACT));
    }

    @Test
    void rejectsInvalidConfigProof() {
        commitmentStore.put(ECDSA_COMMITMENT);
        final var sig = signChannelId(SECRET_KEY, CHANNEL_ID);
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(ECDSA_PUBLIC_KEY))
                .signature(Bytes.wrap(sig))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
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
    void handleSuccessfullyCreatesChannelWithEcdsa() {
        commitmentStore.put(ECDSA_COMMITMENT);
        setupHandleContext(validEcdsaTxn());
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
        // Commitment stays in pending store (freed only when channel is closed)
        assertThat(commitmentStore.contains(ECDSA_COMMITMENT)).isTrue();
        assertThat(stored.ownershipCommitment()).isEqualTo(ECDSA_COMMITMENT);
        // Flag off (default clpr.endpointManifestEnabled=false): dial targets come from
        // ClprLedgerConfiguration.endpoints and no manifest is attached to the channel
        // (spec §4.7). The verifier's synthesized bring-up manifest is intentionally not
        // persisted, since the runtime orchestrator never reads it in this mode.
        assertThat(stored.hasEndpointManifest()).isFalse();
        then(channelLifecycle).should().onChannelActivated(CHANNEL_ID);
    }

    @Test
    void handleStoresPeerManifestFromProofBytes() {
        // Flag on: non-empty endpoint_manifest_proof_bytes → PassThroughClprVerifier parses them
        // as a real manifest, its endpoints satisfy the gate, and the Channel carries the same
        // manifest bytes-for-bytes.
        commitmentStore.put(ECDSA_COMMITMENT);
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
        final var sig = signChannelId(SECRET_KEY, CHANNEL_ID);
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(ECDSA_PUBLIC_KEY))
                .signature(Bytes.wrap(sig))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .endpointManifestProofBytes(ClprEndpointManifest.PROTOBUF.toBytes(peerManifest))
                .build();
        setupHandleContext(txnWith(op), manifestEnabledConfig());
        setupVerifierAccount(true);

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(CHANNEL_ID);
        assertThat(stored).isNotNull();
        assertThat(stored.status()).isEqualTo(ClprChannelStatus.ACTIVE);
        assertThat(stored.endpointManifestVersion()).isEqualTo(7L);
        assertThat(stored.endpointManifestOrThrow()).isEqualTo(peerManifest);
    }

    @Test
    void handleFlagOffIgnoresManifestProofAndStoresNoManifest() {
        // Flag off: the endpoint_manifest_proof_bytes on the body are irrelevant — dial targets
        // come from ClprLedgerConfiguration.endpoints (non-empty here) and no manifest is attached
        // to the Channel. The channel still transitions to ACTIVE.
        commitmentStore.put(ECDSA_COMMITMENT);
        final var sig = signChannelId(SECRET_KEY, CHANNEL_ID);
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(ECDSA_PUBLIC_KEY))
                .signature(Bytes.wrap(sig))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
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
        assertThat(stored.hasEndpointManifest()).isFalse();
    }

    @Test
    void handleFlagOnAllowsEmptyManifestAtChannelCreation() {
        // Flag on: the manifest is the authoritative endpoint source. An empty manifest proof
        // yields an empty manifest, which ClprEndpointManifest explicitly permits at version >= 1
        // (genesis creates exactly that); later manifest-update bundles populate it. So the
        // channel is accepted ACTIVE with an empty stored manifest — NOT rejected.
        commitmentStore.put(ECDSA_COMMITMENT);
        final var sig = signChannelId(SECRET_KEY, CHANNEL_ID);
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(ECDSA_PUBLIC_KEY))
                .signature(Bytes.wrap(sig))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .endpointManifestProofBytes(Bytes.EMPTY)
                .build();
        setupHandleContext(txnWith(op), manifestEnabledConfig());
        setupVerifierAccount(true);

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(CHANNEL_ID);
        assertThat(stored).isNotNull();
        assertThat(stored.status()).isEqualTo(ClprChannelStatus.ACTIVE);
        assertThat(stored.hasEndpointManifest()).isTrue();
        assertThat(stored.endpointManifestOrThrow().endpoints()).isEmpty();
    }

    @Test
    void handleFlagOffRejectsEmptyConfigEndpointsEvenWithManifestProof() {
        // Flag off: config.endpoints is the authoritative source, so the manifest proof is
        // ignored. A config with no endpoints leaves the channel inert → rejected, regardless
        // of a non-empty manifest proof on the body. Proves the gate validates config.endpoints,
        // not the manifest, when the flag is off.
        commitmentStore.put(ECDSA_COMMITMENT);
        final var peerManifest = ClprEndpointManifest.newBuilder()
                .version(3L)
                .serviceAddress(PEER_CONFIG.serviceAddress())
                .endpoints(List.of(ClprEndpoint.newBuilder()
                        .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                                .ipAddress("10.0.0.2")
                                .port(50211)
                                .build())
                        .tlsCertificate(Bytes.wrap(new byte[] {7, 7, 7}))
                        .build()))
                .build();
        final var sig = signChannelId(SECRET_KEY, CHANNEL_ID);
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(ECDSA_PUBLIC_KEY))
                .signature(Bytes.wrap(sig))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(peerConfigWithEndpointCount(0)))
                .endpointManifestProofBytes(ClprEndpointManifest.PROTOBUF.toBytes(peerManifest))
                .build();
        setupHandleContext(txnWith(op));
        setupVerifierAccount(true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_VERIFIER_CONFIG_FAILED));
        assertThat(channelStore.getChannel(CHANNEL_ID)).isNull();
    }

    @Test
    void handleRollsBackAllStateOnInvalidManifestProof() {
        // Invalid endpoint_manifest_proof_bytes → verifier throws → no Channel persisted
        // (spec: "Invalid proof → completeChannel fails; no partial state written").
        commitmentStore.put(ECDSA_COMMITMENT);
        final var sig = signChannelId(SECRET_KEY, CHANNEL_ID);
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(ECDSA_PUBLIC_KEY))
                .signature(Bytes.wrap(sig))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
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
        // Commitment missing — handle should throw and lifecycle must not be notified.
        setupHandleContext(validEcdsaTxn());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_COMMITMENT_MISMATCH));

        then(channelLifecycle).should(never()).onChannelActivated(any());
    }

    @Test
    void handleSuccessfullyCreatesChannelWithEd25519() {
        final var privateKey = keyFrom(SECRET_KEY);
        final var publicKey = privateKey.getAbyte();
        final var commitment = computeCommitment(CHANNEL_ID, Bytes.wrap(publicKey));
        commitmentStore.put(commitment);

        final var messageHash = MiscCryptoUtils.keccak256DigestOf(CHANNEL_ID.toByteArray());
        final byte[] sig;
        try {
            final var engine = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
            engine.initSign(privateKey);
            engine.update(messageHash);
            sig = engine.sign();
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to sign with Ed25519", e);
        }

        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(publicKey))
                .signature(Bytes.wrap(sig))
                .signatureScheme(ClprSignatureScheme.ED25519)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .build();
        setupHandleContext(txnWith(op));
        setupVerifierAccount(true);

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(CHANNEL_ID);
        assertThat(stored).isNotNull();
        assertThat(stored.status()).isEqualTo(ClprChannelStatus.ACTIVE);
        // Commitment stays in pending store (freed only when channel is closed)
        assertThat(commitmentStore.contains(commitment)).isTrue();
        assertThat(stored.ownershipCommitment()).isEqualTo(commitment);
    }

    @Test
    void rejectsEcdsaSignatureFromWrongKey() {
        commitmentStore.put(ECDSA_COMMITMENT);
        // Sign with a *different* private key — produces a structurally valid 64-byte sig
        // that doesn't match the public key in the commitment
        final var differentSecretKey = new byte[] {
            0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f, 0x30,
            0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
            0x39, 0x3a, 0x3b, 0x3c, 0x3d, 0x3e, 0x3f, 0x40
        };
        final var wrongSig = signChannelId(differentSecretKey, CHANNEL_ID);
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(ECDSA_PUBLIC_KEY))
                .signature(Bytes.wrap(wrongSig))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .build();
        setupHandleContext(txnWith(op));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_SIGNATURE));
    }

    @Test
    void rejectsInvalidEd25519Signature() {
        final var seed = new byte[32];
        seed[0] = 0x01;
        final var ed25519PrivKey = keyFrom(seed);
        final var ed25519PubKey = ed25519PrivKey.getAbyte();
        final var ed25519Commitment = computeCommitment(CHANNEL_ID, Bytes.wrap(ed25519PubKey));
        commitmentStore.put(ed25519Commitment);

        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(ed25519PubKey))
                .signature(Bytes.wrap(new byte[64]))
                .signatureScheme(ClprSignatureScheme.ED25519)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .build();
        setupHandleContext(txnWith(op));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_SIGNATURE));
    }

    @Test
    void rejectsCommitmentWithWrongPublicKey() {
        // Commit with the real key, then reveal with a different key of the same length
        commitmentStore.put(ECDSA_COMMITMENT);
        final var differentKey = new byte[64];
        Arrays.fill(differentKey, (byte) 0xAA);
        // keccak256(CHANNEL_ID || differentKey) != ecdsaCommitment
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(differentKey))
                .signature(Bytes.wrap(new byte[64]))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .build();
        setupHandleContext(txnWith(op));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_COMMITMENT_MISMATCH));
    }

    @Test
    void initializesQueueMetadata() {
        commitmentStore.put(ECDSA_COMMITMENT);
        setupHandleContext(validEcdsaTxn());
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
        commitmentStore.put(ECDSA_COMMITMENT);
        setupHandleContext(validEcdsaTxn());
        setupVerifierAccount(true);

        subject.handle(handleContext);

        final var stored = channelStore.getChannel(CHANNEL_ID);
        assertThat(stored).isNotNull();
        assertThat(stored.peerConfigTimestamp()).isEqualTo(PEER_CONFIG.timestamp());
        assertThat(stored.peerThrottles()).isEqualTo(PEER_CONFIG.throttles());
    }

    @Test
    void storesAllPeerEndpointsWhenMaxPeerEndpointsIsZero() {
        commitmentStore.put(ECDSA_COMMITMENT);
        final var peerConfig = peerConfigWithEndpointCount(12);
        setupHandleContext(validEcdsaTxnWithPeerConfig(peerConfig));
        setupVerifierAccount(true);

        subject.handle(handleContext);

        then(channelLifecycle).should().seedPeerEndpoints(CHANNEL_ID, peerConfig.endpoints());
    }

    @Test
    void truncatesPeerEndpointsWhenMaxPeerEndpointsIsNonZero() {
        commitmentStore.put(ECDSA_COMMITMENT);
        final var peerConfig = peerConfigWithEndpointCount(5);
        setupHandleContext(
                validEcdsaTxnWithPeerConfig(peerConfig), enabledConfig(), localConfigWithMaxPeerEndpoints(3));
        setupVerifierAccount(true);

        subject.handle(handleContext);

        then(channelLifecycle)
                .should()
                .seedPeerEndpoints(CHANNEL_ID, peerConfig.endpoints().subList(0, 3));
    }

    @Test
    void rejectsPeerConfigWithNegativeTimestampSeconds() {
        commitmentStore.put(ECDSA_COMMITMENT);
        final var peerConfig = PEER_CONFIG
                .copyBuilder()
                .timestamp(Timestamp.newBuilder().seconds(-1).nanos(0).build())
                .build();
        setupHandleContext(validEcdsaTxnWithPeerConfig(peerConfig));
        setupVerifierAccount(true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_VERIFIER_CONFIG_FAILED));
    }

    @Test
    void rejectsPeerConfigWithNanosOutOfRange() {
        commitmentStore.put(ECDSA_COMMITMENT);
        final var peerConfig = PEER_CONFIG
                .copyBuilder()
                .timestamp(Timestamp.newBuilder()
                        .seconds(1000)
                        .nanos(1_000_000_000)
                        .build())
                .build();
        setupHandleContext(validEcdsaTxnWithPeerConfig(peerConfig));
        setupVerifierAccount(true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_VERIFIER_CONFIG_FAILED));
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
        lenient()
                .when(storeFactory.writableStore(WritablePendingCommitmentStore.class))
                .thenReturn(commitmentStore);
        lenient().when(storeFactory.writableStore(WritableChannelStore.class)).thenReturn(channelStore);
        lenient().when(storeFactory.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
        lenient()
                .when(storeFactory.readableStore(ReadableLedgerConfigurationStore.class))
                .thenReturn(localConfigStore);
        lenient().when(localConfigStore.getConfiguration()).thenReturn(localLedgerConfig);
        lenient().when(storeFactory.serviceApi(SmartContractServiceApi.class)).thenReturn(smartContractServiceApi);
    }

    private void setupVerifierAccount(final boolean isSmartContract) {
        final var account = Account.newBuilder().smartContract(isSmartContract).build();
        given(accountStore.getContractById(VERIFIER_CONTRACT_ID)).willReturn(isSmartContract ? account : null);
    }

    private TransactionBody validEcdsaTxn() {
        return validEcdsaTxnWithPeerConfig(PEER_CONFIG);
    }

    private TransactionBody validEcdsaTxnWithPeerConfig(final ClprLedgerConfiguration peerConfig) {
        final var sig = signChannelId(SECRET_KEY, CHANNEL_ID);
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(ECDSA_PUBLIC_KEY))
                .signature(Bytes.wrap(sig))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(peerConfig))
                .build();
        return txnWith(op);
    }

    private static Configuration enabledConfig() {
        return HederaTestConfigBuilder.create().withValue("clpr.enabled", true).getOrCreateConfig();
    }

    private static Configuration manifestEnabledConfig() {
        return HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", true)
                .withValue("clpr.endpointManifestEnabled", true)
                .getOrCreateConfig();
    }

    private static ClprLedgerConfiguration localConfigWithMaxPeerEndpoints(final int maxPeerEndpoints) {
        return ClprLedgerConfiguration.newBuilder()
                .throttles(ClprThrottles.newBuilder()
                        .maxPeerEndpoints(maxPeerEndpoints)
                        .build())
                .build();
    }

    private static ClprLedgerConfiguration peerConfigWithEndpointCount(final int count) {
        final var endpoints = new ArrayList<ClprEndpoint>();
        for (int i = 0; i < count; i++) {
            endpoints.add(ClprEndpoint.newBuilder().build());
        }
        return PEER_CONFIG.copyBuilder().endpoints(endpoints).build();
    }

    private TransactionBody txnWith(final ClprCompleteChannelTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprCompleteChannel(op)
                .build();
    }

    private static Bytes computeCommitment(final Bytes channelId, final Bytes publicKey) {
        final var payload = new byte[(int) (channelId.length() + publicKey.length())];
        channelId.getBytes(0, payload, 0, (int) channelId.length());
        publicKey.getBytes(0, payload, (int) channelId.length(), (int) publicKey.length());
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(payload));
    }

    private static byte[] signChannelId(final byte[] secKey, final Bytes connId) {
        final var messageHash = MiscCryptoUtils.keccak256DigestOf(connId.toByteArray());
        final var recoverableSig = new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        final var signResult = LibSecp256k1.secp256k1_ecdsa_sign_recoverable(
                LibSecp256k1.CONTEXT, recoverableSig, messageHash, secKey, null, null);
        if (signResult != 1) {
            throw new IllegalStateException("Failed to sign message");
        }
        final var compactSigBuffer = ByteBuffer.allocate(64);
        final var recId = new IntByReference(0);
        LibSecp256k1.secp256k1_ecdsa_recoverable_signature_serialize_compact(
                LibSecp256k1.CONTEXT, compactSigBuffer, recId, recoverableSig);
        return compactSigBuffer.array();
    }
}
