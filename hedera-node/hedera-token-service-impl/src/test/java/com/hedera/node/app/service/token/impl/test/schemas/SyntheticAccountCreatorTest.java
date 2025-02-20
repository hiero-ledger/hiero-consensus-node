// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.schemas;

import static com.hedera.hapi.util.HapiUtils.EMPTY_KEY_LIST;
import static com.hedera.node.app.service.token.impl.comparator.TokenComparators.ACCOUNT_COMPARATOR;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.DEFAULT_NUM_SYSTEM_ACCOUNTS;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.EVM_ADDRESSES;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.EXPECTED_ENTITY_EXPIRY;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.EXPECTED_TREASURY_TINYBARS_BALANCE;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.GENESIS_KEY;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.NUM_RESERVED_SYSTEM_ENTITIES;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.TREASURY_ACCOUNT_NUM;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.buildConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.impl.schemas.SyntheticAccountCreator;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.assertj.core.api.Assertions;
import org.bouncycastle.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class SyntheticAccountCreatorTest {

    @Mock
    private Consumer<SortedSet<Account>> systemAccounts;

    @Mock
    private Consumer<SortedSet<Account>> stakingAccounts;

    @Mock
    private Consumer<SortedSet<Account>> miscAccounts;

    @Mock
    private Consumer<SortedSet<Account>> treasuryClones;

    @Mock
    private Consumer<SortedSet<Account>> blocklistAccounts;

    @Captor
    private ArgumentCaptor<SortedSet<Account>> sysAcctRcdsCaptor;

    @Captor
    private ArgumentCaptor<SortedSet<Account>> stakingAcctRcdsCaptor;

    @Captor
    private ArgumentCaptor<SortedSet<Account>> multiuseAcctRcdsCaptor;

    @Captor
    private ArgumentCaptor<SortedSet<Account>> treasuryCloneRcdsCaptor;

    @Captor
    private ArgumentCaptor<SortedSet<Account>> blocklistAcctRcdsCaptor;

    private Configuration config;
    private long firstUserEntityNum;

    @BeforeEach
    void setUp() {
        config = buildConfig(DEFAULT_NUM_SYSTEM_ACCOUNTS, true);
        firstUserEntityNum = config.getConfigData(HederaConfig.class).firstUserEntity();
    }

    @Test
    void createsAllSyntheticRecords() {
        final var subject = new SyntheticAccountCreator();
        subject.generateSyntheticAccounts(
                config, systemAccounts, stakingAccounts, treasuryClones, miscAccounts, blocklistAccounts);

        // Verify system records created
        verify(systemAccounts).accept(sysAcctRcdsCaptor.capture());
        final var sysAcctRcdsResult = sysAcctRcdsCaptor.getValue();
        Assertions.assertThat(sysAcctRcdsResult)
                .isNotNull()
                .hasSize(DEFAULT_NUM_SYSTEM_ACCOUNTS)
                .allSatisfy(this::verifySystemSynthRecord);
        Assertions.assertThat(sysAcctRcdsResult.stream().map(Account::accountId).map(AccountID::accountNum))
                .allMatch(acctNum -> 1 <= acctNum && acctNum <= DEFAULT_NUM_SYSTEM_ACCOUNTS);
        final var aggregateBalance = sysAcctRcdsResult.stream()
                .mapToLong(a -> a != null ? a.tinybarBalance() : 0)
                .sum();
        Assertions.assertThat(aggregateBalance).isEqualTo(EXPECTED_TREASURY_TINYBARS_BALANCE);

        // Verify staking records created
        verify(stakingAccounts).accept(stakingAcctRcdsCaptor.capture());
        final var stakingAcctRcdsResult = stakingAcctRcdsCaptor.getValue();
        Assertions.assertThat(stakingAcctRcdsResult).isNotNull().hasSize(2).allSatisfy(this::verifyStakingSynthRecord);
        Assertions.assertThat(stakingAcctRcdsResult.stream()
                        .map(Account::accountId)
                        .map(AccountID::accountNum)
                        .toArray())
                .containsExactly(800L, 801L);

        // Verify multipurpose records created
        verify(miscAccounts).accept(multiuseAcctRcdsCaptor.capture());
        final var multiuseAcctsResult = multiuseAcctRcdsCaptor.getValue();
        Assertions.assertThat(multiuseAcctsResult).isNotNull().hasSize(101).allSatisfy(this::verifyMultiUseSynthRecord);
        Assertions.assertThat(
                        multiuseAcctsResult.stream().map(Account::accountId).map(AccountID::accountNum))
                .allMatch(acctNum -> 900 <= acctNum && acctNum <= 1000);

        // Verify treasury clone records created
        verify(treasuryClones).accept(treasuryCloneRcdsCaptor.capture());
        final var treasuryCloneAcctsResult = treasuryCloneRcdsCaptor.getValue();
        Assertions.assertThat(treasuryCloneAcctsResult)
                .isNotNull()
                .hasSize(501)
                .allSatisfy(this::verifyTreasuryCloneSynthRecord);
        Assertions.assertThat(treasuryCloneAcctsResult.stream()
                        .map(Account::accountId)
                        .map(AccountID::accountNum))
                .allMatch(acctNum ->
                        Arrays.contains(V0490TokenSchema.nonContractSystemNums(NUM_RESERVED_SYSTEM_ENTITIES), acctNum));

        // Verify blocklist records created
        verify(blocklistAccounts).accept(blocklistAcctRcdsCaptor.capture());
        final var expectedBlocklistAcctRcdsSize = 6;
        final var blocklistAcctsResult = blocklistAcctRcdsCaptor.getValue();
        Assertions.assertThat(blocklistAcctsResult).isNotNull().hasSize(expectedBlocklistAcctRcdsSize);
        for (final Account acctRecord : blocklistAcctsResult) {
            Assertions.assertThat(acctRecord).isNotNull();

            // These account ID numbers are placeholders until we can get a real entity ID assigned by the
            // EntityIdService (which happens later)
            final var placeholderAcctNum = acctRecord.accountId().accountNum().longValue();
            Assertions.assertThat(placeholderAcctNum)
                    .isBetween(firstUserEntityNum, firstUserEntityNum + expectedBlocklistAcctRcdsSize);
            Assertions.assertThat(acctRecord.receiverSigRequired()).isTrue();
            Assertions.assertThat(acctRecord.declineReward()).isTrue();
            Assertions.assertThat(acctRecord.deleted()).isFalse();
            Assertions.assertThat(acctRecord.expirationSecond()).isEqualTo(EXPECTED_ENTITY_EXPIRY);
            Assertions.assertThat(acctRecord.autoRenewSeconds()).isEqualTo(EXPECTED_ENTITY_EXPIRY);
            Assertions.assertThat(acctRecord.smartContract()).isFalse();
            Assertions.assertThat(acctRecord.key()).isNotNull();
            Assertions.assertThat(acctRecord.alias())
                    .isEqualTo(Bytes.fromHex(EVM_ADDRESSES[(int) (placeholderAcctNum - firstUserEntityNum)]));
        }
    }

    @Test
    void blocklistNotEnabled() {
        final var subject = new SyntheticAccountCreator();

        config = buildConfig(DEFAULT_NUM_SYSTEM_ACCOUNTS, false);

        subject.generateSyntheticAccounts(
                config, systemAccounts, stakingAccounts, treasuryClones, miscAccounts, blocklistAccounts);

        // No synthetic records should be created when the blocklist isn't enabled
        verify(blocklistAccounts).accept(Collections.emptySortedSet());
    }

    @Test
    void correctEntityIdsUsed() {
        final var subject = new SyntheticAccountCreator();
        subject.generateSyntheticAccounts(
                config, systemAccounts, stakingAccounts, treasuryClones, miscAccounts, blocklistAccounts);

        verify(systemAccounts).accept(sysAcctRcdsCaptor.capture());
        verify(stakingAccounts).accept(stakingAcctRcdsCaptor.capture());
        verify(treasuryClones).accept(treasuryCloneRcdsCaptor.capture());
        verify(miscAccounts).accept(multiuseAcctRcdsCaptor.capture());
        verify(blocklistAccounts).accept(blocklistAcctRcdsCaptor.capture());
        final var allSynthRcds = new TreeSet<>(ACCOUNT_COMPARATOR);
        allSynthRcds.addAll(sysAcctRcdsCaptor.getValue());
        allSynthRcds.addAll(stakingAcctRcdsCaptor.getValue());
        allSynthRcds.addAll(treasuryCloneRcdsCaptor.getValue());
        allSynthRcds.addAll(multiuseAcctRcdsCaptor.getValue());
        allSynthRcds.addAll(blocklistAcctRcdsCaptor.getValue());
        Assertions.assertThat(allSynthRcds.stream().map(Account::accountId).map(AccountID::accountNum))
                .allMatch(i ->
                        // Verify contract entity IDs aren't used
                        (i < 350 || i >= 400)
                                &&
                                // Verify entity IDs between system account records and reward account records aren't
                                // used
                                (i < 751 || i >= 800)
                                &&
                                // Verify entity IDs between staking reward records and misc account records aren't used
                                (i < 802 || i >= 900));
    }

    private void verifySystemSynthRecord(final Account account) {
        assertThat(account).isNotNull();
        final long expectedBalance =
                account.accountId().accountNum() == TREASURY_ACCOUNT_NUM ? EXPECTED_TREASURY_TINYBARS_BALANCE : 0;
        assertBasicSynthRecord(account, expectedBalance, EXPECTED_ENTITY_EXPIRY);
        assertThat(account.keyOrThrow().ed25519OrThrow().toHex()).isEqualTo(GENESIS_KEY);
        Assertions.assertThat(account.autoRenewSeconds()).isEqualTo(EXPECTED_ENTITY_EXPIRY);
    }

    private void verifyStakingSynthRecord(final Account account) {
        assertBasicSynthRecord(account, 0, 33197904000L);
        Assertions.assertThat(account.key()).isEqualTo(EMPTY_KEY_LIST);
    }

    private void verifyMultiUseSynthRecord(final Account account) {
        assertBasicSynthRecord(account, 0, EXPECTED_ENTITY_EXPIRY);
        assertThat(account.keyOrThrow().ed25519OrThrow().toHex()).isEqualTo(GENESIS_KEY);
    }

    private void verifyTreasuryCloneSynthRecord(final Account account) {
        assertBasicSynthRecord(account, 0, EXPECTED_ENTITY_EXPIRY);
        assertThat(account.keyOrThrow().ed25519OrThrow().toHex()).isEqualTo(GENESIS_KEY);
        Assertions.assertThat(account.autoRenewSeconds()).isEqualTo(EXPECTED_ENTITY_EXPIRY);
    }

    private void assertBasicSynthRecord(Account account, long balance, long expiry) {
        assertThat(account).isNotNull();
        assertThat(account.tinybarBalance()).isEqualTo(balance);
        assertThat(account.alias()).isEqualTo(Bytes.EMPTY);
        assertThat(account.expirationSecond()).isEqualTo(expiry);
        assertThat(account.memo()).isEmpty();
        assertThat(account.deleted()).isFalse();
        assertThat(account.stakedToMe()).isZero();
        assertThat(account.stakePeriodStart()).isZero();
        assertThat(account.stakedId().kind()).isEqualTo(Account.StakedIdOneOfType.UNSET);
        assertThat(account.receiverSigRequired()).isFalse();
        assertThat(account.hasHeadNftId()).isFalse();
        assertThat(account.headNftSerialNumber()).isZero();
        assertThat(account.numberOwnedNfts()).isZero();
        assertThat(account.maxAutoAssociations()).isZero();
        assertThat(account.usedAutoAssociations()).isZero();
        assertThat(account.declineReward()).isTrue();
        assertThat(account.numberAssociations()).isZero();
        assertThat(account.smartContract()).isFalse();
        assertThat(account.numberPositiveBalances()).isZero();
        assertThat(account.ethereumNonce()).isZero();
        assertThat(account.stakeAtStartOfLastRewardedPeriod()).isZero();
        assertThat(account.hasAutoRenewAccountId()).isFalse();
        assertThat(account.contractKvPairsNumber()).isZero();
        assertThat(account.cryptoAllowances()).isEmpty();
        assertThat(account.approveForAllNftAllowances()).isEmpty();
        assertThat(account.tokenAllowances()).isEmpty();
        assertThat(account.numberTreasuryTitles()).isZero();
        assertThat(account.expiredAndPendingRemoval()).isFalse();
        assertThat(account.firstContractStorageKey()).isEqualTo(Bytes.EMPTY);
    }
}
