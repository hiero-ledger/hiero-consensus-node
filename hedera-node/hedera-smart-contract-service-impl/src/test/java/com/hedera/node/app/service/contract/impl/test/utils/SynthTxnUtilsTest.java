// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.utils;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.AN_ED25519_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CANONICAL_ALIAS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_DURATION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_MEMO;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.DEFAULT_AUTO_RENEW_PERIOD;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthAccountCreationFromHapi;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthContractCreationForExternalization;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthContractCreationFromParent;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthHollowAccountCreation;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import org.junit.jupiter.api.Test;

class SynthTxnUtilsTest {

    private static final String LAZY_CREATION_MEMO = "";

    @Test
    void createsExpectedHollowSynthBody() {
        final var expected = CryptoCreateTransactionBody.newBuilder()
                .key(IMMUTABILITY_SENTINEL_KEY)
                .memo(LAZY_CREATION_MEMO)
                .alias(CANONICAL_ALIAS)
                .autoRenewPeriod(DEFAULT_AUTO_RENEW_PERIOD)
                .build();
        assertEquals(expected, synthHollowAccountCreation(CANONICAL_ALIAS));
    }

    @Test
    void convertsParentWithStakedNodeAndDeclinedRewardAndAutoRenewIdAndNoAdminKeyAsExpected() {
        final var parent = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(123L).build())
                .key(Key.newBuilder().contractID(ContractID.newBuilder().contractNum(123L)))
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .stakedNodeId(3)
                .declineReward(true)
                .autoRenewSeconds(666L)
                .maxAutoAssociations(321)
                .memo("Something")
                .build();
        final var matchingCreation = synthContractCreationFromParent(CALLED_CONTRACT_ID, parent);
        assertEquals(parent.maxAutoAssociations(), matchingCreation.maxAutomaticTokenAssociations());
        assertEquals(parent.declineReward(), matchingCreation.declineReward());
        assertEquals(parent.memo(), matchingCreation.memo());
        assertEquals(
                parent.autoRenewSeconds(), matchingCreation.autoRenewPeriod().seconds());
        assertEquals(parent.autoRenewAccountId(), matchingCreation.autoRenewAccountId());
        assertEquals(Key.newBuilder().contractID(CALLED_CONTRACT_ID).build(), matchingCreation.adminKey());
    }

    @Test
    void convertsParentWithStakedAccountAndNoAutoRenewIdAndAdminKeyAsExpected() {
        final var parent = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(123L).build())
                .key(AN_ED25519_KEY)
                .stakedAccountId(A_NEW_ACCOUNT_ID)
                .declineReward(true)
                .autoRenewSeconds(666L)
                .maxAutoAssociations(321)
                .memo("Something")
                .build();
        final var matchingCreation = synthContractCreationFromParent(CALLED_CONTRACT_ID, parent);
        assertEquals(parent.maxAutoAssociations(), matchingCreation.maxAutomaticTokenAssociations());
        assertEquals(parent.declineReward(), matchingCreation.declineReward());
        assertEquals(parent.memo(), matchingCreation.memo());
        assertEquals(
                parent.autoRenewSeconds(), matchingCreation.autoRenewPeriod().seconds());
        assertEquals(parent.autoRenewAccountId(), matchingCreation.autoRenewAccountId());
        assertEquals(parent.key(), matchingCreation.adminKey());
    }

    @Test
    void onlySetContractKeyForExternalization() {
        final var matchingKey = Key.newBuilder().contractID(CALLED_CONTRACT_ID).build();
        final var matchingCreation = synthContractCreationForExternalization(CALLED_CONTRACT_ID);
        assertEquals(matchingKey, matchingCreation.adminKey());
    }

    @Test
    void canConvertContractCreationWithAdminKeyAndStakedNodeIdToCryptoCreation() {
        final var bodyIn = ContractCreateTransactionBody.newBuilder()
                .adminKey(AN_ED25519_KEY)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .autoRenewPeriod(SOME_DURATION)
                .declineReward(true)
                .stakedNodeId(123L)
                .maxAutomaticTokenAssociations(321)
                .memo(SOME_MEMO)
                .build();
        final var bodyOut = CryptoCreateTransactionBody.newBuilder()
                .key(AN_ED25519_KEY)
                .autoRenewPeriod(SOME_DURATION)
                .declineReward(true)
                .stakedNodeId(123L)
                .maxAutomaticTokenAssociations(321)
                .memo(SOME_MEMO)
                .build();
        assertEquals(bodyOut, synthAccountCreationFromHapi(CALLED_CONTRACT_ID, null, bodyIn));
    }

    @Test
    void canConvertContractCreationWithoutAdminKeyAndStakedAccountIdToCryptoCreation() {
        final var bodyIn = ContractCreateTransactionBody.newBuilder()
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .autoRenewPeriod(SOME_DURATION)
                .declineReward(true)
                .stakedAccountId(NON_SYSTEM_ACCOUNT_ID)
                .maxAutomaticTokenAssociations(321)
                .memo(SOME_MEMO)
                .build();
        final var bodyOut = CryptoCreateTransactionBody.newBuilder()
                .key(Key.newBuilder().contractID(CALLED_CONTRACT_ID).build())
                .autoRenewPeriod(SOME_DURATION)
                .declineReward(true)
                .stakedAccountId(NON_SYSTEM_ACCOUNT_ID)
                .maxAutomaticTokenAssociations(321)
                .memo(SOME_MEMO)
                .build();
        assertEquals(bodyOut, synthAccountCreationFromHapi(CALLED_CONTRACT_ID, null, bodyIn));
    }

    @Test
    void canConvertContractCreationWithEmptyAdminKeyAndNoStakedToCryptoCreation() {
        final var bodyIn = ContractCreateTransactionBody.newBuilder()
                .adminKey(Key.newBuilder().keyList(KeyList.DEFAULT))
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .autoRenewPeriod(SOME_DURATION)
                .maxAutomaticTokenAssociations(321)
                .memo(SOME_MEMO)
                .build();
        final var bodyOut = CryptoCreateTransactionBody.newBuilder()
                .key(Key.newBuilder().contractID(CALLED_CONTRACT_ID).build())
                .autoRenewPeriod(SOME_DURATION)
                .maxAutomaticTokenAssociations(321)
                .memo(SOME_MEMO)
                .build();
        assertEquals(bodyOut, synthAccountCreationFromHapi(CALLED_CONTRACT_ID, null, bodyIn));
    }

    @Test
    void canConvertAliasedContractCreationToCryptoCreation() {
        final var bodyIn = ContractCreateTransactionBody.newBuilder()
                .autoRenewPeriod(SOME_DURATION)
                .build();
        final var alias = tuweniToPbjBytes(EIP_1014_ADDRESS);
        final var bodyOut = CryptoCreateTransactionBody.newBuilder()
                .key(Key.newBuilder().contractID(CALLED_CONTRACT_ID).build())
                .autoRenewPeriod(SOME_DURATION)
                .alias(alias)
                .build();
        assertEquals(bodyOut, synthAccountCreationFromHapi(CALLED_CONTRACT_ID, alias, bodyIn));
    }
}
