// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.api;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_LABEL;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
import com.hedera.node.app.service.token.api.ContractChangeSummary;
import com.hedera.node.app.service.token.fixtures.FakeFeeRecordBuilder;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.api.TokenServiceApiImpl;
import com.hedera.node.app.service.token.impl.validators.StakingValidator;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableKVStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenServiceApiImplTest {

    public static final Configuration DEFAULT_CONFIG = HederaTestConfigBuilder.createConfig();
    private static final Bytes EVM_ADDRESS =
            com.hedera.pbj.runtime.io.buffer.Bytes.fromHex("89abcdef89abcdef89abcdef89abcdef89abcdef");
    private static final Bytes OTHER_EVM_ADDRESS = Bytes.fromHex("29abcde089abcde089abcde089abcde089abcde0");
    private static final Bytes SOME_STORE_KEY = com.hedera.pbj.runtime.io.buffer.Bytes.fromHex("0123456789");

    public static final ContractID CONTRACT_ID_BY_NUM =
            ContractID.newBuilder().contractNum(666).build();
    public static final ContractID OTHER_CONTRACT_ID_BY_NUM =
            ContractID.newBuilder().contractNum(777).build();
    public static final ContractID CONTRACT_ID_BY_ALIAS =
            ContractID.newBuilder().evmAddress(EVM_ADDRESS).build();
    public static final AccountID EOA_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(888).build();
    public static final AccountID CONTRACT_ACCOUNT_ID = AccountID.newBuilder()
            .accountNum(CONTRACT_ID_BY_NUM.contractNumOrThrow())
            .build();
    public static final ContractID CONTRACT_ID = ContractID.newBuilder()
            .contractNum(CONTRACT_ID_BY_NUM.contractNumOrThrow())
            .build();
    public static final AccountID OTHER_CONTRACT_ACCOUNT_ID = AccountID.newBuilder()
            .accountNum(OTHER_CONTRACT_ID_BY_NUM.contractNumOrThrow())
            .build();

    private final WritableKVState<Bytes, AccountID> aliasesState =
            new MapWritableKVState<>(ALIASES_STATE_ID, ALIASES_STATE_LABEL);
    private final WritableKVState<AccountID, Account> accountState =
            new MapWritableKVState<>(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL);
    private final WritableStates writableStates = new MapWritableStates(Map.of(
            ACCOUNTS_STATE_ID, accountState,
            ALIASES_STATE_ID, aliasesState));
    private final WritableStates entityWritableStates = new MapWritableStates(Map.of(
            ENTITY_ID_STATE_ID,
            new FunctionWritableSingletonState<>(
                    ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> EntityNumber.DEFAULT, c -> {}),
            ENTITY_COUNTS_STATE_ID,
            new FunctionWritableSingletonState<>(
                    ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> EntityCounts.DEFAULT, c -> {})));
    private WritableAccountStore accountStore;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private Predicate<CryptoTransferTransactionBody> customFeeTest;

    @Mock
    private LongConsumer onNodeFee;

    @Mock
    private LongConsumer onNodeRefund;

    private WritableEntityCounters entityCounters;

    private TokenServiceApiImpl subject;

    @BeforeEach
    void setUp() {
        entityCounters = new WritableEntityIdStoreImpl(entityWritableStates);
        accountStore = new WritableAccountStore(writableStates, entityCounters);
        subject = new TokenServiceApiImpl(DEFAULT_CONFIG, writableStates, customFeeTest, entityCounters);
    }

    @Test
    void delegatesToCustomFeeTest() {
        given(customFeeTest.test(CryptoTransferTransactionBody.DEFAULT)).willReturn(true);
        assertTrue(subject.checkForCustomFees(CryptoTransferTransactionBody.DEFAULT));
    }

    @Test
    void delegatesStakingValidationAsExpected() {
        try (var mockedValidator = mockStatic(StakingValidator.class)) {
            subject.assertValidStakingElectionForCreation(
                    false, "STAKED_NODE_ID", null, 123L, accountStore, networkInfo);
            mockedValidator.verify(
                    () -> StakingValidator.validateStakedIdForCreation(
                            false, "STAKED_NODE_ID", null, 123L, accountStore, networkInfo),
                    times(1));
        }
    }

    @Test
    void canUpdateStorageMetadata() {
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .contractKvPairsNumber(3)
                .smartContract(true)
                .build());

        subject.updateStorageMetadata(CONTRACT_ID, SOME_STORE_KEY, 7);

        final var postIncrementAccount = requireNonNull(accountState.get(CONTRACT_ACCOUNT_ID));
        assertEquals(SOME_STORE_KEY, postIncrementAccount.firstContractStorageKey());
        assertEquals(10, postIncrementAccount.contractKvPairsNumber());
    }

    @Test
    void missingAccountHasZeroOriginalKvUsage() {
        assertEquals(0, subject.originalKvUsageFor(CONTRACT_ID));
    }

    @Test
    void extantContractHasOriginalUsage() {
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .contractKvPairsNumber(3)
                .smartContract(true)
                .build());
        ((WritableKVStateBase<?, ?>) accountState).commit();

        assertEquals(3, subject.originalKvUsageFor(CONTRACT_ID));
    }

    @Test
    void refusesToSetNegativeKvPairCount() {
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .contractKvPairsNumber(3)
                .smartContract(true)
                .build());

        assertThrows(
                IllegalArgumentException.class, () -> subject.updateStorageMetadata(CONTRACT_ID, SOME_STORE_KEY, -4));
    }

    @Test
    void refusesToUpdateKvCountsForNonContract() {
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .contractKvPairsNumber(3)
                .build());

        assertThrows(
                IllegalArgumentException.class, () -> subject.updateStorageMetadata(CONTRACT_ID, SOME_STORE_KEY, -3));
    }

    @Test
    void finalizesHollowAccountAsContractAsExpected() {
        final var numAssociations = 3;
        accountStore.putAndIncrementCount(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .numberAssociations(numAssociations)
                .key(IMMUTABILITY_SENTINEL_KEY)
                .build());

        subject.finalizeHollowAccountAsContract(CONTRACT_ACCOUNT_ID);

        assertEquals(1, accountStore.sizeOfAccountState());
        final var finalizedAccount = accountStore.getContractById(CONTRACT_ID_BY_NUM);
        assertNotNull(finalizedAccount);
        assertEquals(Key.newBuilder().contractID(CONTRACT_ID_BY_NUM).build(), finalizedAccount.key());
        assertTrue(finalizedAccount.smartContract());
        assertEquals(finalizedAccount.maxAutoAssociations(), numAssociations);
    }

    @Test
    void refusesToFinalizeNonHollowAccountAsContract() {
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .key(Key.DEFAULT)
                .build());

        assertThrows(
                IllegalArgumentException.class, () -> subject.finalizeHollowAccountAsContract(CONTRACT_ACCOUNT_ID));
    }

    @Test
    void createsExpectedContractWithAliasIfSet() {
        accountStore.putAndIncrementCount(
                Account.newBuilder().accountId(CONTRACT_ACCOUNT_ID).build());

        assertNull(accountStore.getContractById(CONTRACT_ID_BY_NUM));
        subject.markAsContract(CONTRACT_ACCOUNT_ID, null);

        assertEquals(1, accountStore.sizeOfAccountState());
        assertNotNull(accountStore.getContractById(CONTRACT_ID_BY_NUM));
    }

    @Test
    void marksDeletedByNumberIfSet() {
        accountStore.putAndIncrementCount(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(CONTRACT_ID_BY_NUM.contractNumOrThrow()))
                .smartContract(true)
                .build());

        subject.deleteContract(CONTRACT_ID_BY_NUM);

        assertEquals(1, accountStore.sizeOfAccountState());
        final var deletedContract = accountStore.getContractById(CONTRACT_ID_BY_NUM);
        assertTrue(deletedContract.deleted());
    }

    @Test
    void removesByAliasIfSet() {
        accountStore.putAndIncrementCount(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(CONTRACT_ID_BY_NUM.contractNumOrThrow()))
                .alias(EVM_ADDRESS)
                .smartContract(true)
                .build());
        accountStore.putAndIncrementCountAlias(EVM_ADDRESS, CONTRACT_ACCOUNT_ID);

        subject.deleteContract(CONTRACT_ID_BY_ALIAS);

        assertEquals(1, accountStore.sizeOfAccountState());
        final var deletedContract = accountStore.getContractById(CONTRACT_ID_BY_NUM);
        assertTrue(deletedContract.deleted());
        assertEquals(0, accountStore.sizeOfAliasesState());
    }

    @Test
    void warnsLoudlyButRemovesBothAliasesIfPresent() {
        // This scenario with two aliases referencing the same selfdestruct-ed contract is currently
        // impossible (since only auto-created accounts with ECDSA keys can have two aliases), but if
        // it somehow occurs, we might as well clean up both aliases
        accountStore.putAndIncrementCount(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(CONTRACT_ID_BY_NUM.contractNumOrThrow()))
                .alias(OTHER_EVM_ADDRESS)
                .smartContract(true)
                .build());
        accountStore.putAndIncrementCountAlias(EVM_ADDRESS, CONTRACT_ACCOUNT_ID);
        accountStore.putAndIncrementCountAlias(OTHER_EVM_ADDRESS, CONTRACT_ACCOUNT_ID);

        subject.deleteContract(CONTRACT_ID_BY_ALIAS);

        assertEquals(1, accountStore.sizeOfAccountState());
        final var deletedContract = requireNonNull(accountStore.getContractById(CONTRACT_ID_BY_NUM));
        assertTrue(deletedContract.deleted());
        assertEquals(0, accountStore.sizeOfAliasesState());
    }

    @Test
    void returnsUpdatedNoncesAndCreatedIds() {
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .ethereumNonce(123L)
                .smartContract(true)
                .build());
        ((WritableKVStateBase<?, ?>) accountState).commit();
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .ethereumNonce(124L)
                .smartContract(true)
                .build());
        accountStore.put(Account.newBuilder()
                .accountId(OTHER_CONTRACT_ACCOUNT_ID)
                .ethereumNonce(1L)
                .smartContract(true)
                .build());
        final var expectedSummary = new ContractChangeSummary(
                new ArrayList<>(List.of(OTHER_CONTRACT_ID_BY_NUM)),
                new ArrayList<>(List.of(
                        new ContractNonceInfo(CONTRACT_ID_BY_NUM, 124L),
                        new ContractNonceInfo(OTHER_CONTRACT_ID_BY_NUM, 1L))));
        final var actualSummary = subject.summarizeContractChanges();
        assertEquals(expectedSummary, actualSummary);
    }

    @Test
    void transfersRequestedValue() {
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .smartContract(true)
                .build());
        accountStore.put(Account.newBuilder()
                .tinybarBalance(123L)
                .accountId(EOA_ACCOUNT_ID)
                .smartContract(true)
                .build());

        subject.transferFromTo(EOA_ACCOUNT_ID, CONTRACT_ACCOUNT_ID, 23L);

        final var postTransferSender = requireNonNull(accountState.get(EOA_ACCOUNT_ID));
        final var postTransferReceiver = requireNonNull(accountState.get(CONTRACT_ACCOUNT_ID));
        assertEquals(100L, postTransferSender.tinybarBalance());
        assertEquals(23L, postTransferReceiver.tinybarBalance());
    }

    @Test
    void identicalFromToIsNoop() {
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .tinybarBalance(123L)
                .smartContract(true)
                .build());

        subject.transferFromTo(CONTRACT_ACCOUNT_ID, CONTRACT_ACCOUNT_ID, 23L);

        final var postTransfer = requireNonNull(accountState.get(CONTRACT_ACCOUNT_ID));
        assertEquals(123L, postTransfer.tinybarBalance());
    }

    @Test
    void refusesToTransferNegativeAmount() {
        assertThrows(
                IllegalArgumentException.class, () -> subject.transferFromTo(EOA_ACCOUNT_ID, CONTRACT_ACCOUNT_ID, -1L));
    }

    @Test
    void refusesToSetSenderBalanceNegative() {
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .smartContract(true)
                .build());
        accountStore.put(Account.newBuilder()
                .tinybarBalance(123L)
                .accountId(EOA_ACCOUNT_ID)
                .smartContract(true)
                .build());

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.transferFromTo(EOA_ACCOUNT_ID, CONTRACT_ACCOUNT_ID, 124L));
    }

    @Test
    void refusesToOverflowReceiverBalance() {
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .tinybarBalance(Long.MAX_VALUE)
                .smartContract(true)
                .build());
        accountStore.put(Account.newBuilder()
                .tinybarBalance(1L)
                .accountId(EOA_ACCOUNT_ID)
                .smartContract(true)
                .build());

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.transferFromTo(CONTRACT_ACCOUNT_ID, EOA_ACCOUNT_ID, Long.MAX_VALUE));
    }

    @Test
    void updatesSenderAccountNonce() {
        accountStore.put(Account.newBuilder()
                .accountId(EOA_ACCOUNT_ID)
                .ethereumNonce(123L)
                .build());
        subject.incrementSenderNonce(EOA_ACCOUNT_ID);
        final var postIncrementAccount = requireNonNull(accountState.get(EOA_ACCOUNT_ID));
        assertEquals(124L, postIncrementAccount.ethereumNonce());
    }

    @Test
    void setsAccountNonce() {
        accountStore.put(Account.newBuilder()
                .accountId(EOA_ACCOUNT_ID)
                .ethereumNonce(123L)
                .build());
        subject.setNonce(EOA_ACCOUNT_ID, 321L);
        final var postIncrementAccount = requireNonNull(accountState.get(EOA_ACCOUNT_ID));
        assertEquals(321L, postIncrementAccount.ethereumNonce());
    }

    @Test
    void updatesContractAccountNonce() {
        accountStore.put(Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .smartContract(true)
                .ethereumNonce(123L)
                .build());
        subject.incrementParentNonce(CONTRACT_ID_BY_NUM);
        final var postIncrementAccount = requireNonNull(accountState.get(CONTRACT_ACCOUNT_ID));
        assertEquals(124L, postIncrementAccount.ethereumNonce());
    }

    @Nested
    final class FeeChargingTests {
        // Using non-standard account numbers to tease out any bugs
        private static final long ORIGINAL_PAYER_BALANCE = 100L;
        private static final long ALL_FEES = 12;
        private static final long PAYER_BALANCE_AFTER_ALL_FEES = ORIGINAL_PAYER_BALANCE - ALL_FEES;

        private static final AccountID NODE_ACCOUNT_ID =
                AccountID.newBuilder().accountNum(666).build();
        private static final AccountID FUNDING_ACCOUNT_ID =
                AccountID.newBuilder().accountNum(12).build();
        private static final AccountID STAKING_REWARD_ACCOUNT_ID =
                AccountID.newBuilder().accountNum(13).build();
        private static final AccountID NODE_REWARD_ACCOUNT_ID =
                AccountID.newBuilder().accountNum(14).build();

        private Fees fees;
        private TestConfigBuilder configBuilder;
        private FakeFeeRecordBuilder rb;

        @BeforeEach
        void setUp() {
            configBuilder = HederaTestConfigBuilder.create()
                    .withValue("staking.fees.nodeRewardPercentage", 10)
                    .withValue("staking.fees.stakingRewardPercentage", 20)
                    .withValue("hedera.shard", 0)
                    .withValue("hedera.realm", 0)
                    .withValue("ledger.fundingAccount", 12)
                    .withValue("accounts.stakingRewardAccount", 13)
                    .withValue("accounts.nodeRewardAccount", 14);

            accountStore.put(Account.newBuilder().accountId(FUNDING_ACCOUNT_ID).build());

            accountStore.put(
                    Account.newBuilder().accountId(STAKING_REWARD_ACCOUNT_ID).build());

            accountStore.put(
                    Account.newBuilder().accountId(NODE_REWARD_ACCOUNT_ID).build());

            accountStore.put(Account.newBuilder().accountId(NODE_ACCOUNT_ID).build());

            accountStore.put(Account.newBuilder()
                    .accountId(EOA_ACCOUNT_ID)
                    .tinybarBalance(ORIGINAL_PAYER_BALANCE)
                    .build());

            rb = new FakeFeeRecordBuilder();
            fees = new Fees(2, 5, 5); // 10 tinybars
        }

        @Test
        void withStakingRewards() {
            // Given that staking is enabled
            final var config = configBuilder
                    .withValue("nodes.preserveMinNodeRewardBalance", false)
                    .getOrCreateConfig();
            final Map<AccountID, Long> adjustments = new HashMap<>();

            subject = new TokenServiceApiImpl(config, writableStates, customFeeTest, entityCounters);

            // When we charge network+service fees of 10 tinybars and a node fee of 2 tinybars
            subject.chargeFees(
                    EOA_ACCOUNT_ID,
                    NODE_ACCOUNT_ID,
                    fees,
                    rb,
                    (id, amount) -> adjustments.merge(id, amount, Long::sum),
                    onNodeFee);

            // Then we find that 10% go to node rewards, 20% to staking rewards, and the rest to the funding account
            final var payerAccount = requireNonNull(accountState.get(EOA_ACCOUNT_ID));
            assertThat(payerAccount.tinybarBalance()).isEqualTo(PAYER_BALANCE_AFTER_ALL_FEES);
            assertThat(adjustments.get(EOA_ACCOUNT_ID)).isEqualTo(-ALL_FEES);

            final var nodeRewardAccount = requireNonNull(accountState.get(NODE_REWARD_ACCOUNT_ID));
            assertThat(nodeRewardAccount.tinybarBalance()).isEqualTo(1);
            assertThat(adjustments.get(NODE_REWARD_ACCOUNT_ID)).isEqualTo(1);

            final var stakingRewardAccount = requireNonNull(accountState.get(STAKING_REWARD_ACCOUNT_ID));
            assertThat(stakingRewardAccount.tinybarBalance()).isEqualTo(2);
            assertThat(adjustments.get(STAKING_REWARD_ACCOUNT_ID)).isEqualTo(2);

            final var fundingAccount = requireNonNull(accountState.get(FUNDING_ACCOUNT_ID));
            assertThat(fundingAccount.tinybarBalance()).isEqualTo(7);
            assertThat(adjustments.get(FUNDING_ACCOUNT_ID)).isEqualTo(7);

            final var nodeAccount = requireNonNull(accountState.get(NODE_ACCOUNT_ID));
            assertThat(nodeAccount.tinybarBalance()).isEqualTo(2);
            assertThat(adjustments.get(NODE_ACCOUNT_ID)).isEqualTo(2);
            verify(onNodeFee).accept(2L);

            assertThat(rb.transactionFee()).isEqualTo(ALL_FEES);
        }

        @Test
        void balancesAreUnchangedAfterRefunds() {
            // Given that staking is enabled
            final var config = configBuilder
                    .withValue("nodes.preserveMinNodeRewardBalance", false)
                    .getOrCreateConfig();

            subject = new TokenServiceApiImpl(config, writableStates, customFeeTest, entityCounters);

            // When we charge network+service fees of 10 tinybars and a node fee of 2 tinybars
            subject.chargeFees(EOA_ACCOUNT_ID, NODE_ACCOUNT_ID, fees, rb, (id, amount) -> {}, onNodeFee);
            subject.refundFees(EOA_ACCOUNT_ID, NODE_ACCOUNT_ID, fees, rb, onNodeRefund);

            // Then we find that 10% go to node rewards, 20% to staking rewards, and the rest to the funding account
            final var payerAccount = requireNonNull(accountState.get(EOA_ACCOUNT_ID));
            assertThat(payerAccount.tinybarBalance()).isEqualTo(ORIGINAL_PAYER_BALANCE);

            final var nodeRewardAccount = requireNonNull(accountState.get(NODE_REWARD_ACCOUNT_ID));
            assertThat(nodeRewardAccount.tinybarBalance()).isEqualTo(0L);

            final var stakingRewardAccount = requireNonNull(accountState.get(STAKING_REWARD_ACCOUNT_ID));
            assertThat(stakingRewardAccount.tinybarBalance()).isEqualTo(0);

            final var fundingAccount = requireNonNull(accountState.get(FUNDING_ACCOUNT_ID));
            assertThat(fundingAccount.tinybarBalance()).isEqualTo(0);

            final var nodeAccount = requireNonNull(accountState.get(NODE_ACCOUNT_ID));
            assertThat(nodeAccount.tinybarBalance()).isEqualTo(0);
            verify(onNodeFee).accept(2L);
            verify(onNodeRefund).accept(2L);

            assertThat(rb.transactionFee()).isEqualTo(0L);
        }

        @Test
        void missingPayerAccount() {
            // When we try to charge a payer account that DOES NOT EXIST, then we get an IllegalStateException.
            final var unknownAccountId =
                    AccountID.newBuilder().accountNum(12345678L).build();
            assertThatThrownBy(() -> subject.chargeFees(unknownAccountId, NODE_ACCOUNT_ID, fees, rb, null, onNodeFee))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Payer account %s does not exist", unknownAccountId);
        }

        @Test
        void missingFundingAccount() {
            // Given a configuration that refers to a funding account that DOES NOT EXIST
            final var unknownAccountId =
                    AccountID.newBuilder().accountNum(12345678L).build();
            final var config = configBuilder
                    .withValue("ledger.fundingAccount", unknownAccountId.accountNumOrThrow())
                    .withValue("nodes.preserveMinNodeRewardBalance", false)
                    .getOrCreateConfig();

            subject = new TokenServiceApiImpl(config, writableStates, customFeeTest, entityCounters);

            // When we try to charge a payer account that DOES exist, then we get an IllegalStateException
            assertThatThrownBy(() -> subject.chargeFees(EOA_ACCOUNT_ID, NODE_ACCOUNT_ID, fees, rb, null, onNodeFee))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Funding account %s does not exist", unknownAccountId);
        }

        @Test
        void missingStakingRewardAccount() {
            // Given a configuration that refers to a staking reward account that DOES NOT EXIST
            final var unknownAccountId =
                    AccountID.newBuilder().accountNum(12345678L).build();
            final var config = configBuilder
                    .withValue("accounts.stakingRewardAccount", unknownAccountId.accountNumOrThrow())
                    .withValue("nodes.preserveMinNodeRewardBalance", false)
                    .getOrCreateConfig();

            subject = new TokenServiceApiImpl(config, writableStates, customFeeTest, entityCounters);

            // When we try to charge a payer account that DOES exist, then we get an IllegalStateException
            assertThatThrownBy(() -> subject.chargeFees(EOA_ACCOUNT_ID, NODE_ACCOUNT_ID, fees, rb, null, onNodeFee))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Staking reward account %s does not exist", unknownAccountId);
        }

        @Test
        void missingNodeRewardAccount() {
            // Given a configuration that refers to a node reward account that DOES NOT EXIST
            final var unknownAccountId =
                    AccountID.newBuilder().accountNum(12345678L).build();
            final var config = configBuilder
                    .withValue("accounts.nodeRewardAccount", unknownAccountId.accountNumOrThrow())
                    .getOrCreateConfig();

            subject = new TokenServiceApiImpl(config, writableStates, customFeeTest, entityCounters);

            // When we try to charge a payer account that DOES exist, then we get an IllegalStateException
            assertThatThrownBy(() -> subject.chargeFees(EOA_ACCOUNT_ID, NODE_ACCOUNT_ID, fees, rb, null, onNodeFee))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Node rewards account %s does not exist", unknownAccountId);
        }

        @Test
        void chargesRemainingBalanceIfInsufficient() {
            // Given a payer and unpayable fees, just charge the remaining payer balance
            fees = new Fees(1000, 100, 0); // more than the 100 the user has

            subject = new TokenServiceApiImpl(
                    configBuilder.getOrCreateConfig(), writableStates, customFeeTest, entityCounters);
            subject.chargeFees(EOA_ACCOUNT_ID, NODE_ACCOUNT_ID, fees, rb, null, onNodeFee);

            final var payerAccount = requireNonNull(accountState.get(EOA_ACCOUNT_ID));
            assertThat(payerAccount.tinybarBalance()).isEqualTo(0);
            assertThat(rb.transactionFee()).isEqualTo(100);
        }

        @Test
        void throwsIfUnableToPayNetworkFee() {
            // Given a payer and unpayable fees, just charge the remaining payer balance
            fees = new Fees(0, 101, 0); // more than the 100 the user has

            assertThrows(
                    IllegalArgumentException.class,
                    () -> subject.chargeFees(EOA_ACCOUNT_ID, NODE_ACCOUNT_ID, fees, rb, null, onNodeFee));
        }

        @Test
        void throwsWithNonZeroServiceFeeIfUnableToPayEverything() {
            // Given a payer and unpayable fees, just charge the remaining payer balance
            fees = new Fees(0, 99, 2); // more than the 100 the user has

            assertThrows(
                    IllegalArgumentException.class,
                    () -> subject.chargeFees(EOA_ACCOUNT_ID, NODE_ACCOUNT_ID, fees, rb, null, onNodeFee));
        }
    }
}
