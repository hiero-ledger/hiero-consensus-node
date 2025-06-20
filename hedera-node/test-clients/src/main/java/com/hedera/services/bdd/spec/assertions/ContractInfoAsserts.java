// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import static com.hederahashgraph.api.proto.java.ContractGetInfoResponse.ContractInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoGetInfoResponse;
import com.hederahashgraph.api.proto.java.Key;
import java.util.List;

public class ContractInfoAsserts extends BaseErroringAssertsProvider<ContractInfo> {
    private static final String BAD_MEMO = "Bad memo";
    private static final String BAD_ADMIN_KEY = "Bad admin key";

    public static ContractInfoAsserts infoKnownFor(String contract) {
        return contractWith().knownInfoFor(contract);
    }

    public static ContractInfoAsserts contractWith() {
        return new ContractInfoAsserts();
    }

    public ContractInfoAsserts knownInfoFor(String contract) {
        registerProvider((spec, o) -> {
            ContractInfo actual = (ContractInfo) o;
            assertEquals(spec.registry().getContractId(contract), actual.getContractID(), "Bad contract id!");
            assertEquals(
                    TxnUtils.equivAccount(spec.registry().getContractId(contract)),
                    actual.getAccountID(),
                    "Bad account id!");
            ContractInfo otherExpectedInfo = spec.registry().getContractInfo(contract);
            assertEquals(otherExpectedInfo.getContractAccountID(), actual.getContractAccountID(), "Bad Solidity id!");
            assertEquals(spec.registry().getKey(contract), actual.getAdminKey(), BAD_ADMIN_KEY);
            assertTrue(object2ContractInfo(o).getExpirationTime().getSeconds() != 0, "Expiry must not be null!");
            assertEquals(otherExpectedInfo.getAutoRenewPeriod(), actual.getAutoRenewPeriod(), "Bad auto renew period!");
            assertEquals(otherExpectedInfo.getMemo(), actual.getMemo(), BAD_MEMO);
        });
        return this;
    }

    public ContractInfoAsserts nonNullContractId() {
        registerProvider((spec, o) -> assertTrue(object2ContractInfo(o).hasContractID(), "Null contractId!"));
        return this;
    }

    public ContractInfoAsserts noStakePeriodStart() {
        registerProvider((spec, o) -> assertEquals(
                0,
                object2ContractInfo(o).getStakingInfo().getStakePeriodStart().getSeconds(),
                "Wrong stakePeriodStart"));
        return this;
    }

    public ContractInfoAsserts someStakePeriodStart() {
        registerProvider((spec, o) -> assertNotEquals(
                0,
                object2ContractInfo(o).getStakingInfo().getStakePeriodStart().getSeconds(),
                "Wrong stakePeriodStart"));
        return this;
    }

    public ContractInfoAsserts addressOrAlias(final String hexedEvm) {
        registerProvider(
                (spec, o) -> assertEquals(hexedEvm, object2ContractInfo(o).getContractAccountID(), "Bad EVM address"));
        return this;
    }

    public ContractInfoAsserts maxAutoAssociations(final int num) {
        registerProvider((spec, o) -> assertEquals(
                num, object2ContractInfo(o).getMaxAutomaticTokenAssociations(), "Bad Contract maxAutoAssociations"));
        return this;
    }

    public ContractInfoAsserts newAssociationsFromSnapshot(
            final String snapshot, final List<ExpectedTokenRel> newRels) {
        for (final var newRel : newRels) {
            registerProvider((spec, o) -> {
                final var baseline = spec.registry().getAccountInfo(snapshot);
                for (final var existingRel : baseline.getTokenRelationshipsList()) {
                    assertFalse(
                            newRel.matches(spec, existingRel),
                            "Expected no existing rel to match " + newRel + ", but " + existingRel + " did");
                }

                final var current = (CryptoGetInfoResponse.AccountInfo) o;
                var someMatches = false;
                for (final var currentRel : current.getTokenRelationshipsList()) {
                    someMatches |= newRel.matches(spec, currentRel);
                }
                assertTrue(someMatches, "Expected some new rel to match " + newRel + ", but none did");
            });
        }
        return this;
    }

    public ContractInfoAsserts hasAlreadyUsedAutomaticAssociations(final int num) {
        registerProvider((spec, o) -> {
            var actualTokenRels = object2ContractInfo(o).getTokenRelationshipsList();
            int actualCount = 0;
            for (var rel : actualTokenRels) {
                if (rel.getAutomaticAssociation()) {
                    actualCount++;
                }
            }
            assertEquals(actualCount, num);
        });
        return this;
    }

    public ContractInfoAsserts solidityAddress(String contract) {
        registerProvider((spec, o) -> assertEquals(
                TxnUtils.solidityIdFrom(spec.registry().getContractId(contract)),
                TxnUtils.solidityIdFrom(object2ContractInfo(o).getContractID()),
                "Bad Solidity address"));
        return this;
    }

    public ContractInfoAsserts isDeleted() {
        registerProvider((spec, o) -> assertTrue(object2ContractInfo(o).getDeleted(), "Bad deletion status!"));
        return this;
    }

    public ContractInfoAsserts isNotDeleted() {
        registerProvider((spec, o) -> assertFalse(object2ContractInfo(o).getDeleted(), "Should not have been deleted"));
        return this;
    }

    public ContractInfoAsserts memo(String expectedMemo) {
        registerProvider(
                (spec, o) -> assertEquals(expectedMemo, object2ContractInfo(o).getMemo(), BAD_MEMO));
        return this;
    }

    public ContractInfoAsserts balance(long balance) {
        registerProvider(
                (spec, o) -> assertEquals(balance, object2ContractInfo(o).getBalance(), "Bad balance!"));
        return this;
    }

    public ContractInfoAsserts balanceLessThan(long amount) {
        registerProvider((spec, o) -> {
            long actual = object2ContractInfo(o).getBalance();
            String errorMessage = String.format("Bad balance! %s is not less than %s", actual, amount);
            assertTrue(actual < amount, errorMessage);
        });
        return this;
    }

    public ContractInfoAsserts balanceGreaterThan(long amount) {
        registerProvider((spec, o) -> {
            long actual = object2ContractInfo(o).getBalance();
            String errorMessage = String.format("Bad balance! %s is not greater than %s", actual, amount);
            assertTrue(actual > amount, errorMessage);
        });
        return this;
    }

    public ContractInfoAsserts expiry(long expectedExpiry) {
        registerProvider((spec, o) -> assertEquals(
                expectedExpiry, object2ContractInfo(o).getExpirationTime().getSeconds(), "Bad expiry time!"));
        return this;
    }

    public ContractInfoAsserts approxExpiry(final long expectedExpiry, final long epsilonSecs) {
        registerProvider((spec, o) -> {
            final var actualExpiry = object2ContractInfo(o).getExpirationTime().getSeconds();
            final var diff = Math.abs(expectedExpiry - actualExpiry);
            assertTrue(
                    diff <= epsilonSecs,
                    "Expected expiry of " + expectedExpiry + " +/- " + epsilonSecs + "s, but got " + actualExpiry);
        });
        return this;
    }

    public ContractInfoAsserts propertiesInheritedFrom(String contract) {
        registerProvider((spec, o) -> {
            ContractInfo expected = spec.registry().getContractInfo(contract);
            ContractInfo actual = object2ContractInfo(o);
            assertEquals(expected.getAutoRenewPeriod(), actual.getAutoRenewPeriod(), "Bad auto renew period!");
            assertEquals(expected.getAdminKey(), actual.getAdminKey(), BAD_ADMIN_KEY);
            assertEquals(expected.getMemo(), actual.getMemo(), BAD_MEMO);
        });
        return this;
    }

    public ContractInfoAsserts adminKey(String expectedKeyName) {
        registerProvider((spec, o) -> {
            Key expectedKey = spec.registry().getKey(expectedKeyName);
            assertEquals(expectedKey, object2ContractInfo(o).getAdminKey(), BAD_ADMIN_KEY);
        });
        return this;
    }

    public ContractInfoAsserts defaultAdminKey() {
        registerProvider((spec, o) -> {
            final var contractId = object2ContractInfo(o).getContractID();
            final var expectedKey = Key.newBuilder().setContractID(contractId).build();
            assertEquals(expectedKey, object2ContractInfo(o).getAdminKey(), BAD_ADMIN_KEY);
        });
        return this;
    }

    public ContractInfoAsserts immutableContractKey(String name) {
        registerProvider((spec, o) -> {
            final var actualKey = object2ContractInfo(o).getAdminKey();
            assertTrue(actualKey.hasContractID(), "Expected a contract admin key, got " + actualKey);
            if (TxnUtils.isNumericLiteral(name)) {
                assertEquals(
                        HapiPropertySource.asContract(spec.shard(), spec.realm(), Long.parseLong(name)),
                        actualKey.getContractID(),
                        "Wrong immutable contract key");
            } else if (TxnUtils.isIdLiteral(name)) {
                assertEquals(
                        HapiPropertySource.asContract(name), actualKey.getContractID(), "Wrong immutable contract key");
            } else {
                assertEquals(
                        spec.registry().getContractId(name), actualKey.getContractID(), "Wrong immutable contract key");
            }
        });
        return this;
    }

    public ContractInfoAsserts autoRenew(long expectedAutoRenew) {
        registerProvider((spec, o) -> assertEquals(
                expectedAutoRenew, object2ContractInfo(o).getAutoRenewPeriod().getSeconds(), "Bad" + " autoRenew!"));
        return this;
    }

    public ContractInfoAsserts numKvPairs(int expectedKvPairs) {
        /* EVM storage maps 32-byte keys to 32-byte values */
        final long numStorageBytes = expectedKvPairs * 64L;
        registerProvider((spec, o) ->
                assertEquals(numStorageBytes, object2ContractInfo(o).getStorage(), "Bad storage size!"));
        return this;
    }

    public ContractInfoAsserts stakedAccountId(String idLiteral) {
        registerProvider((spec, o) -> assertEquals(
                TxnUtils.asId(idLiteral, spec),
                (object2ContractInfo(o)).getStakingInfo().getStakedAccountId(),
                "Bad stakedAccountId id!"));
        return this;
    }

    public ContractInfoAsserts stakedNodeId(long idLiteral) {
        registerProvider((spec, o) -> assertEquals(
                idLiteral, (object2ContractInfo(o)).getStakingInfo().getStakedNodeId(), "Bad stakedNodeId id!"));
        return this;
    }

    public ContractInfoAsserts isDeclinedReward(boolean isDeclined) {
        registerProvider((spec, o) -> assertEquals(
                isDeclined, (object2ContractInfo(o)).getStakingInfo().getDeclineReward(), "Bad isDeclinedReward!"));
        return this;
    }

    public ContractInfoAsserts noStakedAccountId() {
        registerProvider((spec, o) -> assertEquals(
                AccountID.getDefaultInstance(),
                (object2ContractInfo(o)).getStakingInfo().getStakedAccountId(),
                "Bad stakedAccountId id!"));
        return this;
    }

    public ContractInfoAsserts noStakingNodeId() {
        registerProvider((spec, o) ->
                assertEquals(0, (object2ContractInfo(o)).getStakingInfo().getStakedNodeId(), "Bad stakedNodeId id!"));
        return this;
    }

    public ContractInfoAsserts autoRenewAccountId(String id) {
        registerProvider((spec, o) -> assertEquals(
                spec.registry().getAccountID(id),
                object2ContractInfo(o).getAutoRenewAccountId(),
                "Bad autoRenewAccountId !"));
        return this;
    }

    public ContractInfoAsserts pendingRewards(long reward) {
        registerProvider((spec, o) -> assertEquals(
                reward, (object2ContractInfo(o)).getStakingInfo().getPendingReward(), "Bad pending rewards!"));
        return this;
    }

    private ContractInfo object2ContractInfo(Object o) {
        return (ContractInfo) o;
    }
}
