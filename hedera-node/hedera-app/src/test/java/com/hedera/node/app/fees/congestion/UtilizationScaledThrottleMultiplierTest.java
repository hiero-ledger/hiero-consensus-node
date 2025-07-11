// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees.congestion;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_AIRDROP;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.data.TopicsConfig;
import com.hedera.node.config.types.EntityScaleFactors;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class})
class UtilizationScaledThrottleMultiplierTest {

    private static final long SOME_MULTIPLIER = 4L;

    private UtilizationScaledThrottleMultiplier utilizationScaledThrottleMultiplier;

    @Mock
    private ThrottleMultiplier delegate;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration configuration;

    @Mock
    private FeesConfig feesConfig;

    @Mock
    private AccountsConfig accountsConfig;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private FilesConfig filesConfig;

    @Mock
    private TokensConfig tokensConfig;

    @Mock
    private TopicsConfig topicsConfig;

    EntityScaleFactors entityScaleFactors = EntityScaleFactors.from("DEFAULT(1,10:1,5,25:1)");

    private static final long ENTITY_SCALE_FACTOR = 10L;

    @Mock
    private TransactionInfo txnInfo;

    private FakeState state;

    @BeforeEach
    void setUp() {
        utilizationScaledThrottleMultiplier = new UtilizationScaledThrottleMultiplier(delegate, configProvider);
    }

    @Test
    void testCurrentMultiplierCryptoCreate() {
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
        given(feesConfig.percentUtilizationScaleFactors()).willReturn(entityScaleFactors);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.maxNumber()).willReturn(100L);

        when(txnInfo.functionality()).thenReturn(CRYPTO_CREATE);
        when(delegate.currentMultiplier()).thenReturn(SOME_MULTIPLIER);

        state = new FakeState()
                .addService(
                        TokenService.NAME,
                        Map.of(
                                "ACCOUNTS",
                                Map.of(
                                        AccountID.newBuilder().accountNum(1L),
                                        com.hedera.hapi.node.state.token.Account.DEFAULT,
                                        AccountID.newBuilder().accountNum(2L),
                                        com.hedera.hapi.node.state.token.Account.DEFAULT,
                                        AccountID.newBuilder().accountNum(3L),
                                        com.hedera.hapi.node.state.token.Account.DEFAULT,
                                        AccountID.newBuilder().accountNum(4L),
                                        com.hedera.hapi.node.state.token.Account.DEFAULT,
                                        AccountID.newBuilder().accountNum(5L),
                                        com.hedera.hapi.node.state.token.Account.DEFAULT),
                                "ALIASES",
                                new HashMap<>()))
                .addService(
                        ContractService.NAME,
                        Map.of(
                                V0490ContractSchema.STORAGE_KEY,
                                new HashMap<>(),
                                V0490ContractSchema.BYTECODE_KEY,
                                Map.of(
                                        new EntityNumber(4L), Bytecode.DEFAULT,
                                        new EntityNumber(5L), Bytecode.DEFAULT)))
                .addService(
                        EntityIdService.NAME,
                        Map.of(
                                ENTITY_ID_STATE_KEY,
                                new AtomicReference<>(EntityNumber.newBuilder().build()),
                                ENTITY_COUNTS_KEY,
                                new AtomicReference<>(EntityCounts.newBuilder()
                                        .numAccounts(1L)
                                        .build())));

        var storeFactory = new ReadableStoreFactory(state);
        long multiplier = utilizationScaledThrottleMultiplier.currentMultiplier(txnInfo, storeFactory);

        assertEquals(SOME_MULTIPLIER * ENTITY_SCALE_FACTOR, multiplier);
    }

    @Test
    void testCurrentMultiplierContractCreate() {
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
        given(feesConfig.percentUtilizationScaleFactors()).willReturn(entityScaleFactors);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.maxNumber()).willReturn(100L);

        when(txnInfo.functionality()).thenReturn(CONTRACT_CREATE);
        when(delegate.currentMultiplier()).thenReturn(SOME_MULTIPLIER);

        state = new FakeState()
                .addService(
                        ContractService.NAME,
                        Map.of(
                                V0490ContractSchema.STORAGE_KEY,
                                new HashMap<>(),
                                V0490ContractSchema.BYTECODE_KEY,
                                Map.of(
                                        new EntityNumber(4L), Bytecode.DEFAULT,
                                        new EntityNumber(5L), Bytecode.DEFAULT)))
                .addService(
                        EntityIdService.NAME,
                        Map.of(
                                ENTITY_ID_STATE_KEY,
                                new AtomicReference<>(EntityNumber.newBuilder().build()),
                                ENTITY_COUNTS_KEY,
                                new AtomicReference<>(EntityCounts.newBuilder()
                                        .numContractBytecodes(1L)
                                        .build())));

        var storeFactory = new ReadableStoreFactory(state);
        long multiplier = utilizationScaledThrottleMultiplier.currentMultiplier(txnInfo, storeFactory);

        assertEquals(SOME_MULTIPLIER * ENTITY_SCALE_FACTOR, multiplier);
    }

    @Test
    void testCurrentMultiplierFileCreate() {
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
        given(feesConfig.percentUtilizationScaleFactors()).willReturn(entityScaleFactors);
        given(configuration.getConfigData(FilesConfig.class)).willReturn(filesConfig);
        given(filesConfig.maxNumber()).willReturn(100L);

        when(txnInfo.functionality()).thenReturn(FILE_CREATE);
        when(delegate.currentMultiplier()).thenReturn(SOME_MULTIPLIER);

        state = new FakeState()
                .addService(
                        FileService.NAME,
                        Map.of(
                                V0490FileSchema.BLOBS_KEY,
                                Map.of(
                                        FileID.newBuilder().fileNum(1L), File.DEFAULT,
                                        FileID.newBuilder().fileNum(2L), File.DEFAULT)))
                .addService(
                        EntityIdService.NAME,
                        Map.of(
                                ENTITY_ID_STATE_KEY,
                                new AtomicReference<>(EntityNumber.newBuilder().build()),
                                ENTITY_COUNTS_KEY,
                                new AtomicReference<>(
                                        EntityCounts.newBuilder().numFiles(1L).build())));

        var storeFactory = new ReadableStoreFactory(state);
        long multiplier = utilizationScaledThrottleMultiplier.currentMultiplier(txnInfo, storeFactory);

        assertEquals(SOME_MULTIPLIER * ENTITY_SCALE_FACTOR, multiplier);
    }

    @Test
    void testCurrentMultiplierTokenMintWithMetadata() {
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
        given(feesConfig.percentUtilizationScaleFactors()).willReturn(entityScaleFactors);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.nftsMaxAllowedMints()).willReturn(100L);

        final var nftMintTxnInfo = new TransactionInfo(
                SignedTransaction.DEFAULT,
                TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder().accountID(AccountID.DEFAULT))
                        .tokenMint(TokenMintTransactionBody.newBuilder().metadata(List.of(Bytes.EMPTY)))
                        .build(),
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                TOKEN_MINT,
                null);
        when(delegate.currentMultiplier()).thenReturn(SOME_MULTIPLIER);

        state = new FakeState()
                .addService(
                        TokenService.NAME,
                        Map.of(
                                V0490TokenSchema.NFTS_KEY,
                                Map.of(
                                        NftID.newBuilder()
                                                .tokenId(TokenID.newBuilder().tokenNum(1L)),
                                        Nft.DEFAULT,
                                        NftID.newBuilder()
                                                .tokenId(TokenID.newBuilder().tokenNum(2L)),
                                        Nft.DEFAULT)))
                .addService(
                        EntityIdService.NAME,
                        Map.of(
                                ENTITY_ID_STATE_KEY,
                                new AtomicReference<>(EntityNumber.newBuilder().build()),
                                ENTITY_COUNTS_KEY,
                                new AtomicReference<>(
                                        EntityCounts.newBuilder().numNfts(1L).build())));

        var storeFactory = new ReadableStoreFactory(state);
        long multiplier = utilizationScaledThrottleMultiplier.currentMultiplier(nftMintTxnInfo, storeFactory);

        assertEquals(SOME_MULTIPLIER * ENTITY_SCALE_FACTOR, multiplier);
    }

    @Test
    void testCurrentMultiplierTokenMintWithoutMetadata() {
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
        given(feesConfig.percentUtilizationScaleFactors()).willReturn(entityScaleFactors);

        final var tokenMintTxnInfo = new TransactionInfo(
                SignedTransaction.DEFAULT,
                TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder().accountID(AccountID.DEFAULT))
                        .tokenMint(TokenMintTransactionBody.DEFAULT)
                        .build(),
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                TOKEN_MINT,
                null);
        when(delegate.currentMultiplier()).thenReturn(SOME_MULTIPLIER);

        var storeFactory = new ReadableStoreFactory(new FakeState());
        long multiplier = utilizationScaledThrottleMultiplier.currentMultiplier(tokenMintTxnInfo, storeFactory);

        assertEquals(SOME_MULTIPLIER, multiplier);
    }

    @Test
    void testCurrentMultiplierTokenCreate() {
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
        given(feesConfig.percentUtilizationScaleFactors()).willReturn(entityScaleFactors);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.maxNumber()).willReturn(100L);

        when(txnInfo.functionality()).thenReturn(TOKEN_CREATE);
        when(delegate.currentMultiplier()).thenReturn(SOME_MULTIPLIER);

        state = new FakeState()
                .addService(
                        TokenService.NAME,
                        Map.of(
                                V0490TokenSchema.TOKENS_KEY,
                                Map.of(
                                        TokenID.newBuilder().tokenNum(1L), Token.DEFAULT,
                                        TokenID.newBuilder().tokenNum(2L), Token.DEFAULT)))
                .addService(
                        EntityIdService.NAME,
                        Map.of(
                                ENTITY_ID_STATE_KEY,
                                new AtomicReference<>(EntityNumber.newBuilder().build()),
                                ENTITY_COUNTS_KEY,
                                new AtomicReference<>(
                                        EntityCounts.newBuilder().numTokens(1L).build())));

        var storeFactory = new ReadableStoreFactory(state);
        long multiplier = utilizationScaledThrottleMultiplier.currentMultiplier(txnInfo, storeFactory);

        assertEquals(SOME_MULTIPLIER * ENTITY_SCALE_FACTOR, multiplier);
    }

    @Test
    void testCurrentMultiplierPendingAirdrops() {
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
        given(feesConfig.percentUtilizationScaleFactors()).willReturn(entityScaleFactors);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.maxAllowedPendingAirdrops()).willReturn(100L);

        when(txnInfo.functionality()).thenReturn(TOKEN_AIRDROP);
        when(delegate.currentMultiplier()).thenReturn(SOME_MULTIPLIER);

        state = new FakeState()
                .addService(
                        TokenService.NAME,
                        Map.of(
                                V0530TokenSchema.AIRDROPS_KEY,
                                Map.of(PendingAirdropId.DEFAULT, AccountPendingAirdrop.DEFAULT)))
                .addService(
                        EntityIdService.NAME,
                        Map.of(
                                ENTITY_ID_STATE_KEY,
                                new AtomicReference<>(EntityNumber.newBuilder().build()),
                                ENTITY_COUNTS_KEY,
                                new AtomicReference<>(EntityCounts.newBuilder()
                                        .numAirdrops(1L)
                                        .build())));

        var storeFactory = new ReadableStoreFactory(state);
        long multiplier = utilizationScaledThrottleMultiplier.currentMultiplier(txnInfo, storeFactory);

        assertEquals(SOME_MULTIPLIER * ENTITY_SCALE_FACTOR, multiplier);
    }

    @Test
    void testCurrentMultiplierTokenAssociateToAccount() {
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
        given(feesConfig.percentUtilizationScaleFactors()).willReturn(entityScaleFactors);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.maxAggregateRels()).willReturn(100L);

        when(txnInfo.functionality()).thenReturn(TOKEN_ASSOCIATE_TO_ACCOUNT);
        when(delegate.currentMultiplier()).thenReturn(SOME_MULTIPLIER);

        state = new FakeState()
                .addService(
                        TokenService.NAME,
                        Map.of(
                                V0490TokenSchema.TOKEN_RELS_KEY,
                                Map.of(
                                        EntityIDPair.newBuilder()
                                                .tokenId(TokenID.newBuilder().tokenNum(1L)),
                                        TokenRelation.DEFAULT,
                                        EntityIDPair.newBuilder()
                                                .tokenId(TokenID.newBuilder().tokenNum(2L)),
                                        TokenRelation.DEFAULT)))
                .addService(
                        EntityIdService.NAME,
                        Map.of(
                                ENTITY_ID_STATE_KEY,
                                new AtomicReference<>(EntityNumber.newBuilder().build()),
                                ENTITY_COUNTS_KEY,
                                new AtomicReference<>(EntityCounts.newBuilder()
                                        .numTokenRelations(1L)
                                        .build())));

        var storeFactory = new ReadableStoreFactory(state);
        long multiplier = utilizationScaledThrottleMultiplier.currentMultiplier(txnInfo, storeFactory);

        assertEquals(SOME_MULTIPLIER * ENTITY_SCALE_FACTOR, multiplier);
    }

    @Test
    void testCurrentMultiplierConsensusCreateTopic() {
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
        given(feesConfig.percentUtilizationScaleFactors()).willReturn(entityScaleFactors);
        given(configuration.getConfigData(TopicsConfig.class)).willReturn(topicsConfig);
        given(topicsConfig.maxNumber()).willReturn(100L);

        when(txnInfo.functionality()).thenReturn(CONSENSUS_CREATE_TOPIC);
        when(delegate.currentMultiplier()).thenReturn(SOME_MULTIPLIER);

        state = new FakeState()
                .addService(
                        ConsensusService.NAME,
                        Map.of(
                                ConsensusServiceImpl.TOPICS_KEY,
                                Map.of(
                                        TopicID.newBuilder().topicNum(1L), Topic.DEFAULT,
                                        TopicID.newBuilder().topicNum(2L), Topic.DEFAULT)))
                .addService(
                        EntityIdService.NAME,
                        Map.of(
                                ENTITY_ID_STATE_KEY,
                                new AtomicReference<>(EntityNumber.newBuilder().build()),
                                ENTITY_COUNTS_KEY,
                                new AtomicReference<>(
                                        EntityCounts.newBuilder().numTopics(1L).build())));

        var storeFactory = new ReadableStoreFactory(state);
        long multiplier = utilizationScaledThrottleMultiplier.currentMultiplier(txnInfo, storeFactory);

        assertEquals(SOME_MULTIPLIER * ENTITY_SCALE_FACTOR, multiplier);
    }

    @Test
    void testCurrentMultiplierDefaultFunctionality() {
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
        when(txnInfo.functionality()).thenReturn(CRYPTO_TRANSFER);
        when(delegate.currentMultiplier()).thenReturn(SOME_MULTIPLIER);

        var storeFactory = new ReadableStoreFactory(new FakeState());
        long multiplier = utilizationScaledThrottleMultiplier.currentMultiplier(txnInfo, storeFactory);

        assertEquals(SOME_MULTIPLIER, multiplier);
    }

    @Test
    void testUpdateMultiplier() {
        Instant consensusTime = Instant.now();
        utilizationScaledThrottleMultiplier.updateMultiplier(consensusTime);

        verify(delegate).updateMultiplier(consensusTime);
    }

    @Test
    void testResetExpectations() {
        utilizationScaledThrottleMultiplier.resetExpectations();

        verify(delegate).resetExpectations();
    }

    @Test
    void testResetCongestionLevelStarts() {
        Instant[] startTimes = {Instant.now(), Instant.now().plusSeconds(10)};
        utilizationScaledThrottleMultiplier.resetCongestionLevelStarts(startTimes);

        verify(delegate).resetCongestionLevelStarts(startTimes);
    }

    @Test
    void testCongestionLevelStarts() {
        Instant[] startTimes = {Instant.now(), Instant.now().plusSeconds(5)};
        when(delegate.congestionLevelStarts()).thenReturn(startTimes);

        Instant[] starts = utilizationScaledThrottleMultiplier.congestionLevelStarts();

        assertEquals(startTimes, starts);
    }
}
