// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.config;

import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.data.TopicsConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.LongGauge.Config;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;

public class ConfigMetrics {

    private static final LongGauge.Config ACCOUNTS_MAX_NUMBER_CONFIG = new Config("app", "accountsMaxNumber")
            .withDescription("The maximum number of accounts that can be created");

    private static final LongGauge.Config CONTRACTS_MAX_NUMBER_CONFIG = new Config("app", "contractsMaxNumber")
            .withDescription("The maximum number of smart contracts that can be created");

    private static final LongGauge.Config STORAGE_SLOTS_MAX_NUMBER_CONFIG = new Config("app", "storageSlotsMaxNumber")
            .withDescription("The maximum number of storage slots that can be created");

    private static final LongGauge.Config FILES_MAX_NUMBER_CONFIG =
            new Config("app", "filesMaxNumber").withDescription("The maximum number of files that can be created");

    private static final LongGauge.Config SCHEDULES_MAX_NUMBER_CONFIG = new Config("app", "schedulesMaxNumber")
            .withDescription("The maximum number of schedules that can be created");

    private static final LongGauge.Config TOKENS_MAX_NUMBER_CONFIG =
            new Config("app", "tokensMaxNumber").withDescription("The maximum number of tokens that can be created");

    private static final LongGauge.Config NFTS_MAX_NUMBER_CONFIG =
            new Config("app", "nftsMaxNumber").withDescription("The maximum number of NFTs that can be created");

    private static final LongGauge.Config TOKEN_ASSOCIATIONS_MAX_NUMBER_CONFIG = new Config(
                    "app", "tokenAssociationsMaxNumber")
            .withDescription("The maximum number of token associations that can be created");

    private static final LongGauge.Config TOPICS_MAX_NUMBER_CONFIG =
            new Config("app", "topicsMaxNumber").withDescription("The maximum number of topics that can be created");

    private final LongGauge accountMaxNumber;
    private final LongGauge contractsMaxNumber;
    private final LongGauge storageSlotsMaxNumber;
    private final LongGauge filesMaxNumber;
    private final LongGauge schedulesMaxNumber;
    private final LongGauge tokensMaxNumber;
    private final LongGauge nftsMaxNumber;
    private final LongGauge tokenAssocitationsMaxNumber;
    private final LongGauge topicsMaxNumber;

    public ConfigMetrics(@NonNull final Metrics metrics) {
        this.accountMaxNumber = metrics.getOrCreate(ACCOUNTS_MAX_NUMBER_CONFIG);
        this.contractsMaxNumber = metrics.getOrCreate(CONTRACTS_MAX_NUMBER_CONFIG);
        this.storageSlotsMaxNumber = metrics.getOrCreate(STORAGE_SLOTS_MAX_NUMBER_CONFIG);
        this.filesMaxNumber = metrics.getOrCreate(FILES_MAX_NUMBER_CONFIG);
        this.schedulesMaxNumber = metrics.getOrCreate(SCHEDULES_MAX_NUMBER_CONFIG);
        this.tokensMaxNumber = metrics.getOrCreate(TOKENS_MAX_NUMBER_CONFIG);
        this.nftsMaxNumber = metrics.getOrCreate(NFTS_MAX_NUMBER_CONFIG);
        this.tokenAssocitationsMaxNumber = metrics.getOrCreate(TOKEN_ASSOCIATIONS_MAX_NUMBER_CONFIG);
        this.topicsMaxNumber = metrics.getOrCreate(TOPICS_MAX_NUMBER_CONFIG);
    }

    public void reportMetrics(@NonNull final Configuration configuration) {
        final var accountsConfig = configuration.getConfigData(AccountsConfig.class);
        accountMaxNumber.set(accountsConfig.maxNumber());

        final var contractsConfig = configuration.getConfigData(ContractsConfig.class);
        contractsMaxNumber.set(contractsConfig.maxNumber());
        storageSlotsMaxNumber.set(contractsConfig.maxKvPairsAggregate());

        final var filesConfig = configuration.getConfigData(FilesConfig.class);
        filesMaxNumber.set(filesConfig.maxNumber());

        final var schedulingConfig = configuration.getConfigData(SchedulingConfig.class);
        schedulesMaxNumber.set(schedulingConfig.maxNumber());

        final var tokensConfig = configuration.getConfigData(TokensConfig.class);
        tokensMaxNumber.set(tokensConfig.maxNumber());
        nftsMaxNumber.set(tokensConfig.nftsMaxAllowedMints());
        tokenAssocitationsMaxNumber.set(tokensConfig.maxAggregateRels());

        final var topicsConfig = configuration.getConfigData(TopicsConfig.class);
        topicsMaxNumber.set(topicsConfig.maxNumber());
    }
}
