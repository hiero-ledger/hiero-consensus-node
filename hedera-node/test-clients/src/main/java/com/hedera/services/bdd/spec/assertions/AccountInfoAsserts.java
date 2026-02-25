// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.suites.HapiSuite.EMPTY_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.CryptoGetInfoResponse.AccountInfo;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;

public class AccountInfoAsserts extends BaseErroringAssertsProvider<AccountInfo> {

    public static final String BAD_ALIAS = "Bad Alias!";

    boolean hasTokenAssociationExpectations = false;

    public static AccountInfoAsserts accountWith() {
        return new AccountInfoAsserts();
    }

    public AccountInfoAsserts noChangesFromSnapshot(final String snapshot) {
        hasTokenAssociationExpectations = true;
        registerProvider((spec, o) -> {
            final var expected = spec.registry().getAccountInfo(snapshot);
            final var actual = (AccountInfo) o;
            assertEquals(expected, actual, "Changes occurred since snapshot '" + snapshot + "'");
        });
        return this;
    }

    public AccountInfoAsserts newAssociationsFromSnapshot(final String snapshot, final List<ExpectedTokenRel> newRels) {
        hasTokenAssociationExpectations = true;
        for (final var newRel : newRels) {
            registerProvider((spec, o) -> {
                final var baseline = spec.registry().getAccountInfo(snapshot);
                for (final var existingRel : baseline.getTokenRelationshipsList()) {
                    assertFalse(
                            newRel.matches(spec, existingRel),
                            "Expected no existing rel to match " + newRel + ", but " + existingRel + " did");
                }

                final var current = (AccountInfo) o;
                var someMatches = false;
                for (final var currentRel : current.getTokenRelationshipsList()) {
                    someMatches |= newRel.matches(spec, currentRel);
                }
                assertTrue(someMatches, "Expected some new rel to match " + newRel + ", but none did");
            });
        }
        return this;
    }

    public AccountInfoAsserts withShard(int shard) {
        registerProvider((spec, o) ->
                assertEquals(shard, ((AccountInfo) o).getAccountID().getShardNum(), "Bad shard!"));
        return this;
    }

    public AccountInfoAsserts withRealm(long realm) {
        registerProvider((spec, o) ->
                assertEquals(realm, ((AccountInfo) o).getAccountID().getRealmNum(), "Bad realm!"));
        return this;
    }

    public AccountInfoAsserts accountId(String account) {
        registerProvider((spec, o) -> assertEquals(
                spec.registry().getAccountID(account), ((AccountInfo) o).getAccountID(), "Bad account Id!"));
        return this;
    }

    public AccountInfoAsserts stakedAccountId(String acctNum) {
        return stakedAccountId(Long.parseLong(acctNum));
    }

    public AccountInfoAsserts stakedAccountId(long acctNum) {
        registerProvider((spec, o) -> assertEquals(
                asAccount(spec.shard(), spec.realm(), acctNum),
                ((AccountInfo) o).getStakingInfo().getStakedAccountId(),
                "Bad stakedAccountId id!"));
        return this;
    }

    public AccountInfoAsserts stakedAccountIdWithLiteral(String idLiteral) {
        registerProvider((spec, o) -> assertEquals(
                HapiPropertySource.asAccount(idLiteral),
                ((AccountInfo) o).getStakingInfo().getStakedAccountId(),
                "Bad stakedAccountId id!"));
        return this;
    }

    public AccountInfoAsserts noStakedAccountId() {
        registerProvider((spec, o) -> assertEquals(
                AccountID.getDefaultInstance(),
                ((AccountInfo) o).getStakingInfo().getStakedAccountId(),
                "Bad stakedAccountId id!"));
        return this;
    }

    public AccountInfoAsserts someStakePeriodStart() {
        registerProvider((spec, o) -> assertNotEquals(
                0, ((AccountInfo) o).getStakingInfo().getStakePeriodStart().getSeconds(), "Wrong stakePeriodStart"));
        return this;
    }

    public AccountInfoAsserts noStakePeriodStart() {
        registerProvider((spec, o) -> assertEquals(
                0, ((AccountInfo) o).getStakingInfo().getStakePeriodStart().getSeconds(), "Wrong stakePeriodStart"));
        return this;
    }

    public AccountInfoAsserts noStakingNodeId() {
        registerProvider((spec, o) ->
                assertEquals(0, ((AccountInfo) o).getStakingInfo().getStakedNodeId(), "Bad stakedNodeId id!"));
        return this;
    }

    public AccountInfoAsserts stakedNodeId(long idLiteral) {
        registerProvider((spec, o) ->
                assertEquals(idLiteral, ((AccountInfo) o).getStakingInfo().getStakedNodeId(), "Bad stakedNodeId id!"));
        return this;
    }

    public AccountInfoAsserts isDeclinedReward(boolean isDeclined) {
        registerProvider((spec, o) -> assertEquals(
                isDeclined, ((AccountInfo) o).getStakingInfo().getDeclineReward(), "Bad isDeclinedReward!"));
        return this;
    }

    public AccountInfoAsserts hasEmptyKey() {
        registerProvider((spec, o) -> assertEquals(EMPTY_KEY, ((AccountInfo) o).getKey(), "Has non-empty key!"));
        return this;
    }

    public AccountInfoAsserts hasNonEmptyKey() {
        registerProvider((spec, o) -> assertNotEquals(EMPTY_KEY, ((AccountInfo) o).getKey(), "Has empty key!"));
        return this;
    }

    public AccountInfoAsserts key(String key) {
        registerProvider(
                (spec, o) -> assertEquals(spec.registry().getKey(key), ((AccountInfo) o).getKey(), "Bad key!"));
        return this;
    }

    public AccountInfoAsserts key(Key key) {
        registerProvider((spec, o) -> assertEquals(key, ((AccountInfo) o).getKey(), "Bad key!"));
        return this;
    }

    public AccountInfoAsserts receiverSigReq(Boolean isReq) {
        registerProvider((spec, o) ->
                assertEquals(isReq, ((AccountInfo) o).getReceiverSigRequired(), "Bad receiver sig requirement!"));
        return this;
    }

    public AccountInfoAsserts balance(long amount) {
        registerProvider((spec, o) -> assertEquals(amount, ((AccountInfo) o).getBalance(), "Bad balance!"));
        return this;
    }

    /**
     * Asserts that the account balance is within epsilon of the given amount.
     *
     * @param amount  the expected balance
     * @param epsilon the allowed difference between the expected and actual balance
     * @return this
     */
    public AccountInfoAsserts approxBalance(final long amount, final long epsilon) {
        registerProvider((spec, o) -> assertTrue(
                Math.abs(((AccountInfo) o).getBalance() - amount) <= epsilon,
                "Bad balance, expected " + amount + " +/- " + epsilon + ", but was " + ((AccountInfo) o).getBalance()));
        return this;
    }

    public AccountInfoAsserts expectedBalanceWithChargedUsd(
            final long amount, final double expectedUsdToSubtract, final double allowedPercentDiff) {
        registerProvider((spec, o) -> {
            final var rates = spec.ratesProvider().rates();
            var expectedTinyBarsToSubtract =
                    expectedUsdToSubtract * 100 * rates.getHbarEquiv() / rates.getCentEquiv() * ONE_HBAR;
            final var newAmount = ((AccountInfo) o).getBalance();
            final var actualSubtractedTinybars = amount - newAmount;
            final var errorMsgIfOutsideTolerance = "Expected to deduct "
                    + (long) expectedTinyBarsToSubtract
                    + " tinybar to equal ≈ $"
                    + expectedUsdToSubtract
                    + " at a "
                    + rates.getHbarEquiv()
                    + "ℏ <-> "
                    + rates.getCentEquiv()
                    + "¢ exchange rate (actually deducted "
                    + (amount - newAmount)
                    + " tinybars)";
            assertEquals(
                    expectedTinyBarsToSubtract,
                    actualSubtractedTinybars,
                    (allowedPercentDiff / 100.0) * expectedTinyBarsToSubtract,
                    errorMsgIfOutsideTolerance);
        });
        return this;
    }

    public AccountInfoAsserts hasAlias() {
        registerProvider((spec, o) -> assertFalse(((AccountInfo) o).getAlias().isEmpty(), "Has no Alias!"));
        return this;
    }

    public AccountInfoAsserts alias(ByteString alias) {
        registerProvider((spec, o) -> assertEquals(alias, ((AccountInfo) o).getAlias(), BAD_ALIAS));
        return this;
    }

    public AccountInfoAsserts alias(String alias) {
        registerProvider((spec, o) ->
                assertEquals(spec.registry().getKey(alias).toByteString(), ((AccountInfo) o).getAlias(), BAD_ALIAS));
        return this;
    }

    public AccountInfoAsserts evmAddress(ByteString evmAddress) {
        registerProvider((spec, o) -> assertEquals(
                CommonUtils.hex(evmAddress.toByteArray()), ((AccountInfo) o).getContractAccountID(), BAD_ALIAS));
        return this;
    }

    /**
     * Asserts that the account has the given EVM address.
     * @param evmAddress the EVM address
     * @return this
     */
    public AccountInfoAsserts evmAddress(@NonNull final Address evmAddress) {
        requireNonNull(evmAddress);
        return evmAddress(ByteString.copyFrom(explicitFromHeadlong(evmAddress)));
    }

    public AccountInfoAsserts noAlias() {
        registerProvider((spec, o) -> assertTrue(((AccountInfo) o).getAlias().isEmpty(), BAD_ALIAS));
        return this;
    }

    public AccountInfoAsserts balanceLessThan(long amount) {
        registerProvider((spec, o) -> {
            long actual = ((AccountInfo) o).getBalance();
            String errorMessage = String.format("Bad balance! %s is not less than %s", actual, amount);
            assertTrue(actual < amount, errorMessage);
        });
        return this;
    }

    public AccountInfoAsserts memo(String memo) {
        registerProvider((spec, o) -> assertEquals(memo, ((AccountInfo) o).getMemo(), "Bad memo!"));
        return this;
    }

    public static Function<HapiSpec, Function<Long, Optional<String>>> lessThan(final long expected) {
        return spec -> actual -> {
            if (actual == null) {
                return Optional.of(
                        String.format("Expected non-null numeric value less than <%d>, was <null>!", expected));
            }

            final var isLessThanExpected = actual < expected;

            final var msg =
                    isLessThanExpected ? null : String.format("Expected <%d> to be less than <%d>!", actual, expected);
            return Optional.ofNullable(msg);
        };
    }

    public AccountInfoAsserts balance(Function<HapiSpec, Function<Long, Optional<String>>> dynamicCondition) {
        registerProvider((spec, o) -> {
            Function<Long, Optional<String>> expectation = dynamicCondition.apply(spec);
            long actual = ((AccountInfo) o).getBalance();
            Optional<String> failure = expectation.apply(actual);
            if (failure.isPresent()) {
                Assertions.fail("Bad balance! :: " + failure.get());
            }
        });
        return this;
    }

    public static Function<HapiSpec, Function<Long, Optional<String>>> changeFromSnapshot(
            String snapshot, ToLongFunction<HapiSpec> expDeltaFn) {
        return approxChangeFromSnapshot(snapshot, expDeltaFn, 0L);
    }

    /**
     * Returns a factory that creates a matcher that will return an appropriate error message when an account's
     * balance has changed from the balance captured by the given snapshot.
     *
     * @param snapshot the name of the snapshot
     * @return the factory
     */
    public static Function<HapiSpec, Function<Long, Optional<String>>> unchangedFromSnapshot(String snapshot) {
        return approxChangeFromSnapshot(snapshot, 0L, 0L);
    }

    public static Function<HapiSpec, Function<Long, Optional<String>>> changeFromSnapshot(
            String snapshot, long expDelta) {
        return approxChangeFromSnapshot(snapshot, expDelta, 0L);
    }

    public static Function<HapiSpec, Function<Long, Optional<String>>> tokenChangeFromSnapshot(
            String token, String snapshot, long expDelta) {
        return approxTokenChangeFromSnapshot(token, snapshot, expDelta, 0L);
    }

    public static Function<HapiSpec, Function<Long, Optional<String>>> approxChangeFromSnapshot(
            String snapshot, long expDelta, long epsilon) {
        return approxChangeFromSnapshot(snapshot, ignore -> expDelta, epsilon);
    }

    public static Function<HapiSpec, Function<Long, Optional<String>>> approxTokenChangeFromSnapshot(
            String token, String snapshot, long expDelta, long epsilon) {
        return approxTokenChangeFromSnapshot(token, snapshot, ignore -> expDelta, epsilon);
    }

    public static Function<HapiSpec, Function<Long, Optional<String>>> approxChangeFromSnapshot(
            String snapshot, ToLongFunction<HapiSpec> expDeltaFn, long epsilon) {
        return spec -> actual -> {
            long expDelta = expDeltaFn.applyAsLong(spec);
            long actualDelta = actual - spec.registry().getBalanceSnapshot(snapshot);
            if (Math.abs(actualDelta - expDelta) <= epsilon) {
                return Optional.empty();
            } else {
                return Optional.of(String.format(
                        "Expected balance change from '%s' to be <%d +/- %d>, was" + " <%d>!",
                        snapshot, expDelta, epsilon, actualDelta));
            }
        };
    }

    public static Function<HapiSpec, Function<Long, Optional<String>>> approxTokenChangeFromSnapshot(
            String token, String snapshot, ToLongFunction<HapiSpec> expDeltaFn, long epsilon) {
        return spec -> actual -> {
            long expDelta = expDeltaFn.applyAsLong(spec);
            long actualDelta = actual - spec.registry().getTokenBalanceSnapshot(token, snapshot);
            if (Math.abs(actualDelta - expDelta) <= epsilon) {
                return Optional.empty();
            } else {
                return Optional.of(String.format(
                        "Expected token %s balance change from '%s' to be <%d +/- %d>, was" + " <%d>!",
                        token, snapshot, expDelta, epsilon, actualDelta));
            }
        };
    }

    /**
     * Returns a factory that creates a matcher that will return an appropriate error message when an account's
     * balance did not decrease from the balance captured by the given snapshot.
     *
     * @param snapshot the name of the snapshot
     * @return the factory
     */
    public static Function<HapiSpec, Function<Long, Optional<String>>> reducedFromSnapshot(
            @NonNull final String snapshot) {
        requireNonNull(snapshot);
        return spec -> actual -> {
            final var snapshotBalance = spec.registry().getBalanceSnapshot(snapshot);
            final var balanceChangeSinceSnapshot = actual - snapshotBalance;
            if (balanceChangeSinceSnapshot < 0) {
                return Optional.empty();
            } else {
                return Optional.of(
                        String.format("Expected balance to decrease, but went from %d to %d", snapshotBalance, actual));
            }
        };
    }

    public AccountInfoAsserts expiry(long approxTime, long epsilon) {
        registerProvider((spec, o) -> {
            long expiry = ((AccountInfo) o).getExpirationTime().getSeconds();
            assertTrue(
                    Math.abs(approxTime - expiry) <= epsilon,
                    String.format("Expiry %d not in [%d, %d]!", expiry, approxTime - epsilon, approxTime + epsilon));
        });
        return this;
    }

    public AccountInfoAsserts expiry(String registryEntry, long delta) {
        registerProvider((spec, o) -> {
            long expiry = ((AccountInfo) o).getExpirationTime().getSeconds();
            long expected = spec.registry()
                            .getAccountInfo(registryEntry)
                            .getExpirationTime()
                            .getSeconds()
                    + delta;
            assertEquals(expected, expiry, "Bad expiry!");
        });
        return this;
    }

    public AccountInfoAsserts autoRenew(long period) {
        registerProvider((spec, o) ->
                assertEquals(period, ((AccountInfo) o).getAutoRenewPeriod().getSeconds(), "Bad auto-renew period!"));
        return this;
    }

    public AccountInfoAsserts nonce(long nonce) {
        registerProvider((spec, o) -> assertEquals(nonce, ((AccountInfo) o).getEthereumNonce(), "Bad nonce!"));
        return this;
    }

    public AccountInfoAsserts pendingRewards(long reward) {
        registerProvider((spec, o) ->
                assertEquals(reward, ((AccountInfo) o).getStakingInfo().getPendingReward(), "Bad pending rewards!"));
        return this;
    }

    public AccountInfoAsserts maxAutoAssociations(int num) {
        registerProvider((spec, o) -> assertEquals(
                num, ((AccountInfo) o).getMaxAutomaticTokenAssociations(), "Bad maxAutomaticTokenAssociations!"));
        return this;
    }

    public AccountInfoAsserts ownedNfts(int num) {
        registerProvider((spec, o) -> assertEquals(num, ((AccountInfo) o).getOwnedNfts(), "Bad ownedNfts!"));
        return this;
    }

    public boolean hasTokenAssociationExpectation() {
        return hasTokenAssociationExpectations;
    }
}
