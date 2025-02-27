// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.node.app.service.token.impl.schemas.SyntheticAccountCreator.asAccountId;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Initial mod-service schema for the token service.
 */
public class V0490TokenSchema extends Schema {
    private static final Logger log = LogManager.getLogger(V0490TokenSchema.class);

    // These need to be big so databases are created at right scale. If they are too small then the on disk hash map
    // buckets will be too full which results in very poor performance. Have chosen 10 billion as should give us
    // plenty of runway.
    private static final long MAX_STAKING_INFOS = 1_000_000L;
    private static final long MAX_TOKENS = 1_000_000_000L;
    private static final long MAX_ACCOUNTS = 1_000_000_000L;
    private static final long MAX_TOKEN_RELS = 1_000_000_000L;
    private static final long MAX_MINTABLE_NFTS = 1_000_000_000L;
    private static final long FIRST_RESERVED_SYSTEM_CONTRACT = 350L;
    private static final long LAST_RESERVED_SYSTEM_CONTRACT = 399L;
    private static final long FIRST_POST_SYSTEM_FILE_ENTITY = 200L;
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    public static final String NFTS_KEY = "NFTS";
    public static final String TOKENS_KEY = "TOKENS";
    public static final String ALIASES_KEY = "ALIASES";
    public static final String ACCOUNTS_KEY = "ACCOUNTS";
    public static final String TOKEN_RELS_KEY = "TOKEN_RELS";
    public static final String STAKING_INFO_KEY = "STAKING_INFOS";
    public static final String STAKING_NETWORK_REWARDS_KEY = "STAKING_NETWORK_REWARDS";

    private final SyntheticAccountCreator syntheticAccountCreator;

    /**
     * Constructor for this schema. Each of the supplier params should produce a {@link SortedSet} of
     * {@link Account} objects, where each account object represents a _synthetic record_ (see {@link
     * SyntheticAccountCreator} for more details). Even though these sorted sets contain account
     * objects, these account objects may or may not yet exist in state. They're usually not needed,
     * but are required for an event recovery situation.
     *
     */
    public V0490TokenSchema(@NonNull final SyntheticAccountCreator syntheticAccountCreator) {
        super(VERSION);
        this.syntheticAccountCreator = requireNonNull(syntheticAccountCreator);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.onDisk(TOKENS_KEY, TokenID.PROTOBUF, Token.PROTOBUF, MAX_TOKENS),
                StateDefinition.onDisk(ACCOUNTS_KEY, AccountID.PROTOBUF, Account.PROTOBUF, MAX_ACCOUNTS),
                StateDefinition.onDisk(ALIASES_KEY, ProtoBytes.PROTOBUF, AccountID.PROTOBUF, MAX_ACCOUNTS),
                StateDefinition.onDisk(NFTS_KEY, NftID.PROTOBUF, Nft.PROTOBUF, MAX_MINTABLE_NFTS),
                StateDefinition.onDisk(TOKEN_RELS_KEY, EntityIDPair.PROTOBUF, TokenRelation.PROTOBUF, MAX_TOKEN_RELS),
                StateDefinition.onDisk(
                        STAKING_INFO_KEY, EntityNumber.PROTOBUF, StakingNodeInfo.PROTOBUF, MAX_STAKING_INFOS),
                StateDefinition.singleton(STAKING_NETWORK_REWARDS_KEY, NetworkStakingRewards.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final var isGenesis = ctx.previousVersion() == null;
        if (isGenesis) {
            createGenesisSchema(ctx);
        }
    }

    private void createGenesisSchema(@NonNull final MigrationContext ctx) {
        // Create the network rewards state
        initializeNetworkRewards(ctx);
        initializeStakingNodeInfo(ctx);

        // Get the map for storing all the created accounts
        final var accounts = ctx.newStates().<AccountID, Account>get(ACCOUNTS_KEY);
        // We will use these various configs for creating accounts. It would be nice to consolidate them somehow
        final var ledgerConfig = ctx.appConfig().getConfigData(LedgerConfig.class);
        final var hederaConfig = ctx.appConfig().getConfigData(HederaConfig.class);
        final var accountsConfig = ctx.appConfig().getConfigData(AccountsConfig.class);

        // Generate synthetic accounts based on the genesis configuration
        final Consumer<SortedSet<Account>> noOpCb = ignore -> {};
        syntheticAccountCreator.generateSyntheticAccounts(ctx.appConfig(), noOpCb, noOpCb, noOpCb, noOpCb, noOpCb);
        // ---------- Create system accounts -------------------------
        for (final Account acct : syntheticAccountCreator.systemAccounts()) {
            accounts.put(acct.accountIdOrThrow(), acct);
        }
        log.info(
                "Created {} system accounts",
                syntheticAccountCreator.systemAccounts().size());
        // ---------- Create staking fund accounts -------------------------
        for (final Account acct : syntheticAccountCreator.stakingAccounts()) {
            accounts.put(acct.accountIdOrThrow(), acct);
        }
        log.info(
                "Created {} staking accounts",
                syntheticAccountCreator.stakingAccounts().size());
        // ---------- Create treasury clones -------------------------
        for (final Account acct : syntheticAccountCreator.treasuryClones()) {
            accounts.put(acct.accountIdOrThrow(), acct);
        }
        log.info(
                "Created {} treasury clones",
                syntheticAccountCreator.treasuryClones().size());
        // ---------- Create miscellaneous accounts -------------------------
        for (final Account acct : syntheticAccountCreator.multiUseAccounts()) {
            accounts.put(acct.accountIdOrThrow(), acct);
        }
        log.info(
                "Created {} miscellaneous accounts",
                syntheticAccountCreator.multiUseAccounts().size());
        // ---------- Create blocklist accounts -------------------------
        if (accountsConfig.blocklistEnabled()) {
            final var existingAliases = ctx.newStates().<Bytes, AccountID>get(ALIASES_KEY);
            if (existingAliases.size() != 0) {
                throw new IllegalStateException("Aliases map should be empty at genesis");
            }
            for (final Account acct : syntheticAccountCreator.blocklistAccounts()) {
                final var id = asAccountId(ctx.newEntityNumForAccount(), hederaConfig);
                if (!Objects.equals(
                        id.accountNumOrThrow(), acct.accountIdOrThrow().accountNumOrThrow())) {
                    throw new IllegalStateException(
                            "Next entity num " + id + " did not match synthetic block list account " + acct);
                }
                // Put the account and its alias in state
                accounts.put(id, acct);
                existingAliases.put(acct.alias(), id);
            }
        }
        log.info(
                "Created {} blocklist accounts",
                syntheticAccountCreator.blocklistAccounts().size());

        // ---------- Balances Safety Check -------------------------
        // Add up the balances of all accounts, they must match 50,000,000,000 HBARs (config)
        final var totalBalance = getTotalBalanceOfAllAccounts(accounts, hederaConfig);
        if (totalBalance != ledgerConfig.totalTinyBarFloat()) {
            throw new IllegalStateException("Total balance of all accounts does not match the total float: actual: "
                    + totalBalance + " vs expected: " + ledgerConfig.totalTinyBarFloat());
        }
        log.info(
                "Ledger float is {} tinyBars; {} modified accounts.",
                totalBalance,
                accounts.modifiedKeys().size());
    }

    /**
     * Get the total balance of all accounts. Since we cannot iterate over the accounts in VirtualMap,
     * we have to do this manually.
     *
     * @param accounts The accounts map
     * @param hederaConfig The Hedera configuration
     * @return The total balance of all accounts
     */
    public long getTotalBalanceOfAllAccounts(
            @NonNull final WritableKVState<AccountID, Account> accounts, @NonNull final HederaConfig hederaConfig) {
        long totalBalance = 0;
        long curAccountId = 1; // Start with the first account ID
        long totalAccounts = 704; // Since this runs only on genesis, these will only be system accounts
        do {
            final Account account = accounts.get(asAccountId(curAccountId, hederaConfig));
            if (account != null) {
                totalBalance += account.tinybarBalance();
                totalAccounts--;
            }
            curAccountId++;
        } while (totalAccounts > 0);
        return totalBalance;
    }

    /**
     * Get the entity numbers of all system entities that are not contracts.
     *
     * @param numReservedSystemEntities The number of reserved system entities
     * @return The entity numbers of all system entities that are not contracts
     */
    @VisibleForTesting
    public static long[] nonContractSystemNums(final long numReservedSystemEntities) {
        return LongStream.rangeClosed(FIRST_POST_SYSTEM_FILE_ENTITY, numReservedSystemEntities)
                .filter(i -> i < FIRST_RESERVED_SYSTEM_CONTRACT || i > LAST_RESERVED_SYSTEM_CONTRACT)
                .toArray();
    }

    private void initializeStakingNodeInfo(@NonNull final MigrationContext ctx) {
        final var config = ctx.appConfig();
        final var ledgerConfig = config.getConfigData(LedgerConfig.class);
        final var stakingConfig = config.getConfigData(StakingConfig.class);
        final var addressBook = ctx.genesisNetworkInfo().addressBook();
        final var numberOfNodes = addressBook.size();

        final long maxStakePerNode = ledgerConfig.totalTinyBarFloat() / numberOfNodes;
        final long minStakePerNode = 0;

        final var numRewardHistoryStoredPeriods = stakingConfig.rewardHistoryNumStoredPeriods();
        final var stakingInfoState = ctx.newStates().get(STAKING_INFO_KEY);
        final var rewardSumHistory = new Long[numRewardHistoryStoredPeriods + 1];
        Arrays.fill(rewardSumHistory, 0L);

        for (final var node : addressBook) {
            final var nodeNumber = node.nodeId();
            final var stakingInfo = StakingNodeInfo.newBuilder()
                    .nodeNumber(nodeNumber)
                    .maxStake(maxStakePerNode)
                    .minStake(minStakePerNode)
                    .rewardSumHistory(Arrays.asList(rewardSumHistory))
                    .weight(500)
                    .build();
            stakingInfoState.put(EntityNumber.newBuilder().number(nodeNumber).build(), stakingInfo);
        }
    }

    private void initializeNetworkRewards(@NonNull final MigrationContext ctx) {
        // Set genesis network rewards state
        final var networkRewardsState = ctx.newStates().getSingleton(STAKING_NETWORK_REWARDS_KEY);
        final var networkRewards = NetworkStakingRewards.newBuilder()
                .pendingRewards(0)
                .totalStakedRewardStart(0)
                .totalStakedStart(0)
                .stakingRewardsActivated(true)
                .build();
        networkRewardsState.put(networkRewards);
    }
}
