// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees.congestion;

import static com.hedera.node.app.hapi.utils.EntityType.ACCOUNT;
import static com.hedera.node.app.hapi.utils.EntityType.AIRDROP;
import static com.hedera.node.app.hapi.utils.EntityType.CONTRACT_BYTECODE;
import static com.hedera.node.app.hapi.utils.EntityType.FILE;
import static com.hedera.node.app.hapi.utils.EntityType.NFT;
import static com.hedera.node.app.hapi.utils.EntityType.TOKEN;
import static com.hedera.node.app.hapi.utils.EntityType.TOKEN_ASSOCIATION;
import static com.hedera.node.app.hapi.utils.EntityType.TOPIC;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.token.*;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.throttle.annotations.CryptoTransferThrottleMultiplier;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.data.TopicsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A modification of a {@link ThrottleMultiplier} that applies an additional
 * scaling factor to entity creations based on the current percent utilization
 * in state.
 */
@Singleton
public class UtilizationScaledThrottleMultiplier {
    private final ThrottleMultiplier delegate;
    private final ConfigProvider configProvider;

    @Inject
    public UtilizationScaledThrottleMultiplier(
            @NonNull @CryptoTransferThrottleMultiplier final ThrottleMultiplier delegate,
            @NonNull final ConfigProvider configProvider) {
        this.delegate = requireNonNull(delegate, "delegate must not be null");
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
    }

    /**
     * Returns the current congestion multiplier applying additional scaling factor based on
     * the number of entities saved in state.
     *
     *  @param txnInfo transaction info
     *  @param storeFactory provide the stores needed for determining entity utilization
     *
     * @return the current congestion multiplier
     */
    public long currentMultiplier(
            @NonNull final TransactionInfo txnInfo, @NonNull final ReadableStoreFactory storeFactory) {
        return currentMultiplier(txnInfo.txBody(), txnInfo.functionality(), storeFactory);
    }

    public long currentMultiplier(
            @NonNull final TransactionBody body,
            @NonNull final HederaFunctionality functionality,
            @NonNull final ReadableStoreFactory storeFactory) {
        final var throttleMultiplier = delegate.currentMultiplier();
        final var configuration = configProvider.getConfiguration();
        final var entityScaleFactors =
                configuration.getConfigData(FeesConfig.class).percentUtilizationScaleFactors();

        return switch (functionality) {
            case CRYPTO_CREATE -> entityScaleFactors
                    .scaleForNew(ACCOUNT, roundedAccountPercentUtil(storeFactory))
                    .scaling((int) throttleMultiplier);
            case CONTRACT_CREATE -> entityScaleFactors
                    .scaleForNew(CONTRACT_BYTECODE, roundedContractPercentUtil(storeFactory))
                    .scaling((int) throttleMultiplier);
            case FILE_CREATE -> entityScaleFactors
                    .scaleForNew(FILE, roundedFilePercentUtil(storeFactory))
                    .scaling((int) throttleMultiplier);
            case TOKEN_MINT -> {
                final var mintsWithMetadata =
                        !body.tokenMintOrThrow().metadata().isEmpty();
                yield mintsWithMetadata
                        ? entityScaleFactors
                                .scaleForNew(NFT, roundedNftPercentUtil(storeFactory))
                                .scaling((int) throttleMultiplier)
                        : throttleMultiplier;
            }
            case TOKEN_CREATE -> entityScaleFactors
                    .scaleForNew(TOKEN, roundedTokenPercentUtil(storeFactory))
                    .scaling((int) throttleMultiplier);
            case TOKEN_ASSOCIATE_TO_ACCOUNT -> entityScaleFactors
                    .scaleForNew(TOKEN_ASSOCIATION, roundedTokenRelPercentUtil(storeFactory))
                    .scaling((int) throttleMultiplier);
            case CONSENSUS_CREATE_TOPIC -> entityScaleFactors
                    .scaleForNew(TOPIC, roundedTopicPercentUtil(storeFactory))
                    .scaling((int) throttleMultiplier);
            case TOKEN_AIRDROP -> entityScaleFactors
                    .scaleForNew(AIRDROP, roundedAirdropPercentUtil(storeFactory))
                    .scaling((int) throttleMultiplier);
            default -> throttleMultiplier;
        };
    }

    private int roundedAccountPercentUtil(@NonNull final ReadableStoreFactory storeFactory) {
        final var configuration = configProvider.getConfiguration();
        final var maxNumOfAccounts =
                configuration.getConfigData(AccountsConfig.class).maxNumber();

        final var accountsStore = storeFactory.getStore(ReadableAccountStore.class);
        final var numAccountsAndContracts = accountsStore.sizeOfAccountState();

        final var contractsStore = storeFactory.getStore(ContractStateStore.class);
        final var numContracts = contractsStore.getNumBytecodes();
        final var numAccounts = numAccountsAndContracts - numContracts;

        return maxNumOfAccounts == 0 ? 100 : (int) ((100 * numAccounts) / maxNumOfAccounts);
    }

    private int roundedContractPercentUtil(@NonNull final ReadableStoreFactory storeFactory) {
        final var configuration = configProvider.getConfiguration();
        final var maxNumOfContracts =
                configuration.getConfigData(ContractsConfig.class).maxNumber();

        final var contractsStore = storeFactory.getStore(ContractStateStore.class);
        final var numContracts = contractsStore.getNumBytecodes();

        return maxNumOfContracts == 0 ? 100 : (int) ((100 * numContracts) / maxNumOfContracts);
    }

    private int roundedFilePercentUtil(@NonNull final ReadableStoreFactory storeFactory) {
        final var configuration = configProvider.getConfiguration();
        final var maxNumOfFiles = configuration.getConfigData(FilesConfig.class).maxNumber();

        final var fileStore = storeFactory.getStore(ReadableFileStore.class);
        final var numOfFiles = fileStore.sizeOfState();

        return maxNumOfFiles == 0 ? 100 : (int) ((100 * numOfFiles) / maxNumOfFiles);
    }

    private int roundedNftPercentUtil(@NonNull final ReadableStoreFactory storeFactory) {
        final var configuration = configProvider.getConfiguration();
        final var maxNumOfNfts = configuration.getConfigData(TokensConfig.class).nftsMaxAllowedMints();

        final var nftStore = storeFactory.getStore(ReadableNftStore.class);
        final var numOfNfts = nftStore.sizeOfState();

        return maxNumOfNfts == 0 ? 100 : (int) ((100 * numOfNfts) / maxNumOfNfts);
    }

    private int roundedTokenPercentUtil(@NonNull final ReadableStoreFactory storeFactory) {
        final var configuration = configProvider.getConfiguration();
        final var maxNumOfTokens =
                configuration.getConfigData(TokensConfig.class).maxNumber();

        final var tokenStore = storeFactory.getStore(ReadableTokenStore.class);
        final var numOfTokens = tokenStore.sizeOfState();

        return maxNumOfTokens == 0 ? 100 : (int) ((100 * numOfTokens) / maxNumOfTokens);
    }

    private int roundedTokenRelPercentUtil(@NonNull final ReadableStoreFactory storeFactory) {
        final var configuration = configProvider.getConfiguration();
        final var maxNumOfTokenRels =
                configuration.getConfigData(TokensConfig.class).maxAggregateRels();

        final var tokenRelStore = storeFactory.getStore(ReadableTokenRelationStore.class);
        final var numOfTokensRels = tokenRelStore.sizeOfState();

        return maxNumOfTokenRels == 0 ? 100 : (int) ((100 * numOfTokensRels) / maxNumOfTokenRels);
    }

    private int roundedTopicPercentUtil(@NonNull final ReadableStoreFactory storeFactory) {
        final var configuration = configProvider.getConfiguration();
        final var maxNumberOfTopics =
                configuration.getConfigData(TopicsConfig.class).maxNumber();

        final var topicStore = storeFactory.getStore(ReadableTopicStore.class);
        final var numOfTopics = topicStore.sizeOfState();

        return maxNumberOfTopics == 0 ? 100 : (int) ((100 * numOfTopics) / maxNumberOfTopics);
    }

    private int roundedAirdropPercentUtil(@NonNull final ReadableStoreFactory storeFactory) {
        final var configuration = configProvider.getConfiguration();
        final var maxNumAirdrops =
                configuration.getConfigData(TokensConfig.class).maxAllowedPendingAirdrops();

        final var airdropStore = storeFactory.getStore(ReadableAirdropStore.class);
        final var numPendingAirdrops = airdropStore.sizeOfState();

        return maxNumAirdrops == 0 ? 100 : (int) ((100 * numPendingAirdrops) / maxNumAirdrops);
    }

    /**
     * Updates the congestion multiplier for the given consensus time.
     *
     * @param consensusTime the consensus time
     */
    public void updateMultiplier(@NonNull final Instant consensusTime) {
        delegate.updateMultiplier(consensusTime);
    }

    /**
     * Rebuilds the internal state of the ThrottleMultiplier.
     */
    public void resetExpectations() {
        delegate.resetExpectations();
    }

    /**
     * Resets the congestion level starts to the given values.
     *
     * @param startTimes the saved congestion level starts
     */
    public void resetCongestionLevelStarts(@NonNull final Instant[] startTimes) {
        delegate.resetCongestionLevelStarts(startTimes);
    }

    /**
     * Returns the congestion level starts.
     *
     * @return the congestion level starts
     */
    @NonNull
    public Instant[] congestionLevelStarts() {
        return delegate.congestionLevelStarts();
    }
}
