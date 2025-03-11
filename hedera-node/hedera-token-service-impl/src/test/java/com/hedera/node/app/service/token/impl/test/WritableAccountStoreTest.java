// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableAccountStoreTest extends CryptoHandlerTestBase {

    @BeforeEach
    public void setUp() {
        super.setUp();
        resetStores();
    }

    @Test
    void finalizingAccountAsContractCountsAsNewContract() {
        final var hollowAccountId = AccountID.newBuilder().accountNum(666).build();
        writableAccounts.put(
                hollowAccountId, Account.newBuilder().accountId(hollowAccountId).build());
        writableAccounts.commit();

        final var finalizedContract = requireNonNull(writableStore.get(hollowAccountId))
                .copyBuilder()
                .ethereumNonce(1)
                .smartContract(true)
                .build();
        writableStore.put(finalizedContract);

        final List<ContractID> expectedCreationIds =
                List.of(ContractID.newBuilder().contractNum(666).build());
        final var actualCreationIds = writableStore.summarizeContractChanges().newContractIds();

        assertEquals(expectedCreationIds, actualCreationIds);
    }

    @Test
    void returnsOnlyUpdatedContractNonces() {
        final var beforeNonce = 123L;
        final var afterNonce = 456L;
        final var unchangedOtherNonce = 789;
        final var brandNewNonce = 999;
        final var contractIdByNum = AccountID.newBuilder().accountNum(666).build();
        final var otherContractIdByNum = AccountID.newBuilder().accountNum(777).build();
        final var newContractIdByNum = AccountID.newBuilder().accountNum(888).build();
        writableAccounts.put(contractIdByNum, contractWith(contractIdByNum, beforeNonce));
        writableAccounts.put(otherContractIdByNum, contractWith(otherContractIdByNum, unchangedOtherNonce));
        writableAccounts.commit();

        final var newContract = requireNonNull(writableStore.get(contractIdByNum))
                .copyBuilder()
                .ethereumNonce(afterNonce)
                .build();
        writableStore.put(newContract);
        writableStore.put(contractWith(newContractIdByNum, brandNewNonce));

        final var expectedUpdates = Set.of(
                new ContractNonceInfo(ContractID.newBuilder().contractNum(666).build(), afterNonce),
                new ContractNonceInfo(ContractID.newBuilder().contractNum(888).build(), brandNewNonce));
        final var actualUpdates = writableStore.summarizeContractChanges().updatedContractNonces();

        assertEquals(expectedUpdates, new HashSet<>(actualUpdates));
    }

    @Test
    void throwsIfNullValuesAsArgs() {
        assertThrows(NullPointerException.class, () -> new WritableAccountStore(null, writableEntityCounters));
        assertThrows(NullPointerException.class, () -> new WritableAccountStore(writableStates, null));
        assertThrows(NullPointerException.class, () -> writableStore.put(null));
    }

    @Test
    void getReturnsImmutableAccount() {
        refreshStoresWithCurrentTokenInWritable();

        writableStore.put(account);

        final var readaccount = writableStore.get(id);

        assertThat(readaccount).isNotNull();
        assertThat(account).isEqualTo(readaccount);
    }

    @Test
    void canRemoveAlias() {
        writableStore.putAndIncrementCountAlias(alias.aliasOrThrow(), id);
        assertEquals(1, writableStore.sizeOfAliasesState());
        writableStore.removeAlias(alias.aliasOrThrow());
    }

    @Test
    void getDoesntLookForAlias() {
        assertEquals(0, writableStore.sizeOfAliasesState());

        writableStore.putAndIncrementCount(account);
        writableStore.putAndIncrementCountAlias(alias.alias(), id);

        final var readaccount = writableStore.get(alias);

        assertThat(readaccount).isNull();
        assertEquals(1, writableStore.sizeOfAliasesState());
        assertEquals(Set.of(edKeyAlias), writableStore.modifiedAliasesInState());
    }

    @Test
    void getWithAliasedIdLooksForAlias() {
        assertEquals(0, writableStore.sizeOfAliasesState());

        writableStore.putAndIncrementCount(account);
        writableStore.putAndIncrementCountAlias(alias.alias(), id);

        final var readaccount = writableStore.getAliasedAccountById(alias);

        assertThat(readaccount).isNotNull();
        assertThat(account).isEqualTo(readaccount);
        assertEquals(1, writableStore.sizeOfAliasesState());
        assertEquals(Set.of(edKeyAlias), writableStore.modifiedAliasesInState());
    }

    @Test
    void getReturnEmptyIfAliasNotPresent() {
        writableStore.put(account);

        final var readaccount = writableStore.get(alias);

        assertThat(readaccount).isNull();
        assertThat(writableStore.sizeOfAliasesState()).isZero();
    }

    @Test
    void putsAccountChangesToStateInModifications() {
        assertThat(writableStore.get(id)).isNull();

        // put, keeps the account in the modifications
        writableStore.put(account);

        assertTrue(writableAccounts.contains(id));
        final var writtenaccount = writableAccounts.get(id);
        assertThat(account).isEqualTo(writtenaccount);
    }

    @Test
    void getsSizeOfState() {
        assertThat(writableStore.sizeOfAliasesState()).isZero();
        assertThat(writableStore.modifiedAccountsInState()).isEqualTo(Collections.EMPTY_SET);

        writableStore.putAndIncrementCount(account);
        assertThat(writableStore.sizeOfAccountState()).isEqualTo(1);
        assertThat(writableStore.modifiedAccountsInState()).isEqualTo(Set.of(idFactory.newAccountId(3)));
    }

    private Account contractWith(final AccountID id, final long nonce) {
        return Account.newBuilder()
                .accountId(id)
                .smartContract(true)
                .ethereumNonce(nonce)
                .build();
    }
}
