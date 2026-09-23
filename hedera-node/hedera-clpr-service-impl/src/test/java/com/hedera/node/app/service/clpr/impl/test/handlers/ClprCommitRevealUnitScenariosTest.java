// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_COMMITMENT_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.PENDING_COMMITMENTS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprCompleteChannelTransactionBody;
import com.hedera.hapi.node.clpr.ClprRegisterChannelTransactionBody;
import com.hedera.hapi.node.clpr.ClprSignatureScheme;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.WritablePendingCommitmentStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprCompleteChannelHandler;
import com.hedera.node.app.service.clpr.impl.handlers.ClprRegisterChannelHandler;
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
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.util.List;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprCommitRevealUnitScenariosTest {

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1001).build();
    private static final ContractID VERIFIER_CONTRACT_ID =
            ContractID.newBuilder().shardNum(0).realmNum(0).contractNum(5001).build();
    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[] {
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42
    });
    private static final byte[] SECRET_KEY = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
    };
    private static final ClprLedgerConfiguration PEER_CONFIG = ClprLedgerConfiguration.newBuilder()
            .protocolVersion(1)
            .chainId("eip155:1")
            .serviceAddress(Bytes.wrap(new byte[] {0x01, 0x02, 0x03}))
            .timestamp(Timestamp.newBuilder().seconds(1000).build())
            .build();

    @Mock
    private PureChecksContext registerPureChecksContext;

    @Mock
    private PureChecksContext completePureChecksContext;

    @Mock
    private HandleContext completeHandleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableStates writableStates;

    @Mock
    private ClprVerifierFactory verifierFactory;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private SmartContractServiceApi smartContractServiceApi;

    private ClprRegisterChannelHandler registerSubject;
    private ClprCompleteChannelHandler completeSubject;
    private WritablePendingCommitmentStore commitmentStore;
    private WritableChannelStore channelStore;

    @BeforeEach
    void setUp() {
        registerSubject = new ClprRegisterChannelHandler();
        completeSubject = new ClprCompleteChannelHandler(verifierFactory, new ClprChannelLifecycle() {
            @Override
            public void onChannelActivated(@NonNull final Bytes channelId) {}

            @Override
            public void onChannelClosed(@NonNull final Bytes channelId) {}

            @Override
            public void seedPeerEndpoints(
                    @NonNull final Bytes channelId, @NonNull final List<ClprEndpoint> endpoints) {}

            @Override
            public void recordPeerObservedManifestVersion(
                    @NonNull final Bytes channelId, final long peerObservedVersion) {}
        });

        lenient().when(verifierFactory.getVerifier(any())).thenReturn(new PassThroughClprVerifier());

        final var writableCommitments = MapWritableKVState.<ProtoBytes, ProtoBytes>builder(
                        PENDING_COMMITMENTS_STATE_ID, "ClprService:PENDING_COMMITMENTS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ProtoBytes>get(PENDING_COMMITMENTS_STATE_ID))
                .thenReturn(writableCommitments);
        commitmentStore = new WritablePendingCommitmentStore(writableStates);

        final var writableChannels = MapWritableKVState.<ProtoBytes, ClprChannel>builder(
                        CHANNELS_STATE_ID, "ClprService:CHANNELS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID))
                .thenReturn(writableChannels);
        channelStore = new WritableChannelStore(writableStates);
    }

    @Test
    void registerRejectsWrongLengthCommitment() {
        final var op = ClprRegisterChannelTransactionBody.newBuilder()
                .ownershipCommitment(Bytes.wrap(new byte[16]))
                .build();
        given(registerPureChecksContext.body()).willReturn(registerTxnWith(op));

        assertThatThrownBy(() -> registerSubject.pureChecks(registerPureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void completeRejectsWrongLengthChannelId() {
        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[16]))
                .publicKey(Bytes.wrap(new byte[32]))
                .signature(Bytes.wrap(new byte[64]))
                .signatureScheme(ClprSignatureScheme.ED25519)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.wrap(new byte[] {1}))
                .build();
        given(completePureChecksContext.body()).willReturn(completeTxnWith(op));

        assertThatThrownBy(() -> completeSubject.pureChecks(completePureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void completeRejectsRevealWithoutPriorCommit() {
        setupCompleteHandleContext(validEd25519Txn(), enabledConfig());

        assertThatThrownBy(() -> completeSubject.handle(completeHandleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_COMMITMENT_MISMATCH));
    }

    @Test
    void completeRejectsWrongSignature() {
        final var privateKey = Ed25519Utils.keyFrom(SECRET_KEY);
        final var publicKey = privateKey.getAbyte();
        commitmentStore.put(computeCommitment(CHANNEL_ID, Bytes.wrap(publicKey)));

        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(publicKey))
                .signature(Bytes.wrap(new byte[64]))
                .signatureScheme(ClprSignatureScheme.ED25519)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .build();
        setupCompleteHandleContext(completeTxnWith(op), enabledConfig());

        assertThatThrownBy(() -> completeSubject.handle(completeHandleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_SIGNATURE));
    }

    @Test
    void completeRejectsMalformedConfigProof() {
        final var privateKey = Ed25519Utils.keyFrom(SECRET_KEY);
        final var publicKey = privateKey.getAbyte();
        commitmentStore.put(computeCommitment(CHANNEL_ID, Bytes.wrap(publicKey)));
        setupVerifierAccount(true);

        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(publicKey))
                .signature(Bytes.wrap(signChannelIdEd25519(privateKey, CHANNEL_ID)))
                .signatureScheme(ClprSignatureScheme.ED25519)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(Bytes.wrap(new byte[] {(byte) 0xFF, (byte) 0xFF}))
                .build();
        setupCompleteHandleContext(completeTxnWith(op), enabledConfig());

        assertThatThrownBy(() -> completeSubject.handle(completeHandleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_VERIFIER_CONFIG_FAILED));
    }

    @Test
    void completeRejectsWhenClprDisabled() {
        final var privateKey = Ed25519Utils.keyFrom(SECRET_KEY);
        final var publicKey = privateKey.getAbyte();
        commitmentStore.put(computeCommitment(CHANNEL_ID, Bytes.wrap(publicKey)));

        setupCompleteHandleContext(validEd25519Txn(), disabledConfig());

        assertThatThrownBy(() -> completeSubject.handle(completeHandleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    private void setupCompleteHandleContext(final TransactionBody txn, final Configuration configuration) {
        lenient().when(completeHandleContext.body()).thenReturn(txn);
        lenient().when(completeHandleContext.payer()).thenReturn(PAYER_ID);
        lenient().when(completeHandleContext.configuration()).thenReturn(configuration);
        lenient().when(completeHandleContext.storeFactory()).thenReturn(storeFactory);
        lenient().when(completeHandleContext.consensusNow()).thenReturn(java.time.Instant.EPOCH);
        lenient()
                .when(storeFactory.writableStore(WritablePendingCommitmentStore.class))
                .thenReturn(commitmentStore);
        lenient().when(storeFactory.writableStore(WritableChannelStore.class)).thenReturn(channelStore);
        lenient().when(storeFactory.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
        lenient().when(storeFactory.serviceApi(SmartContractServiceApi.class)).thenReturn(smartContractServiceApi);
    }

    private void setupVerifierAccount(final boolean isSmartContract) {
        final var account = Account.newBuilder().smartContract(isSmartContract).build();
        given(accountStore.getContractById(VERIFIER_CONTRACT_ID)).willReturn(isSmartContract ? account : null);
    }

    private TransactionBody validEd25519Txn() {
        final var privateKey = Ed25519Utils.keyFrom(SECRET_KEY);
        final var publicKey = privateKey.getAbyte();
        final var signature = signChannelIdEd25519(privateKey, CHANNEL_ID);

        final var op = ClprCompleteChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .publicKey(Bytes.wrap(publicKey))
                .signature(Bytes.wrap(signature))
                .signatureScheme(ClprSignatureScheme.ED25519)
                .verifierContract(VERIFIER_CONTRACT_ID)
                .configProofBytes(ClprLedgerConfiguration.PROTOBUF.toBytes(PEER_CONFIG))
                .build();
        return completeTxnWith(op);
    }

    private static byte[] signChannelIdEd25519(final EdDSAPrivateKey privateKey, final Bytes channelId) {
        final var messageHash = MiscCryptoUtils.keccak256DigestOf(channelId.toByteArray());
        try {
            final var engine = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
            engine.initSign(privateKey);
            engine.update(messageHash);
            return engine.sign();
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to sign with Ed25519", e);
        }
    }

    private static Bytes computeCommitment(final Bytes channelId, final Bytes publicKey) {
        final var payload = new byte[(int) (channelId.length() + publicKey.length())];
        channelId.getBytes(0, payload, 0, (int) channelId.length());
        publicKey.getBytes(0, payload, (int) channelId.length(), (int) publicKey.length());
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(payload));
    }

    private static Configuration enabledConfig() {
        return HederaTestConfigBuilder.create().withValue("clpr.enabled", true).getOrCreateConfig();
    }

    private static Configuration disabledConfig() {
        return HederaTestConfigBuilder.create().withValue("clpr.enabled", false).getOrCreateConfig();
    }

    private static TransactionBody registerTxnWith(final ClprRegisterChannelTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprRegisterChannel(op)
                .build();
    }

    private static TransactionBody completeTxnWith(final ClprCompleteChannelTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprCompleteChannel(op)
                .build();
    }
}
