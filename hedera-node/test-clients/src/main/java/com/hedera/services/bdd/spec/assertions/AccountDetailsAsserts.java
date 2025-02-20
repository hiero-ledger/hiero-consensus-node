// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import static com.hederahashgraph.api.proto.java.GetAccountDetailsResponse.AccountDetails;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hederahashgraph.api.proto.java.GrantedCryptoAllowance;
import com.hederahashgraph.api.proto.java.GrantedNftAllowance;
import com.hederahashgraph.api.proto.java.GrantedTokenAllowance;
import com.hederahashgraph.api.proto.java.Key;

public class AccountDetailsAsserts extends BaseErroringAssertsProvider<AccountDetails> {
    public static AccountDetailsAsserts accountDetailsWith() {
        return new AccountDetailsAsserts();
    }

    public AccountDetailsAsserts key(Key key) {
        registerProvider((spec, o) -> assertEquals(key, ((AccountDetails) o).getKey(), "Bad key!"));
        return this;
    }

    public AccountDetailsAsserts noAlias() {
        registerProvider((spec, o) -> assertTrue(((AccountDetails) o).getAlias().isEmpty(), "Bad Alias!"));
        return this;
    }

    public AccountDetailsAsserts memo(String memo) {
        registerProvider((spec, o) -> assertEquals(memo, ((AccountDetails) o).getMemo(), "Bad memo!"));
        return this;
    }

    public AccountDetailsAsserts expiry(long approxTime, long epsilon) {
        registerProvider((spec, o) -> {
            long expiry = ((AccountDetails) o).getExpirationTime().getSeconds();
            assertTrue(
                    Math.abs(approxTime - expiry) <= epsilon,
                    String.format("Expiry %d not in [%d, %d]!", expiry, approxTime - epsilon, approxTime + epsilon));
        });
        return this;
    }

    public AccountDetailsAsserts noAllowances() {
        registerProvider((spec, o) -> {
            assertEquals(((AccountDetails) o).getGrantedCryptoAllowancesCount(), 0, "Bad CryptoAllowances count!");
            assertEquals(((AccountDetails) o).getGrantedTokenAllowancesCount(), 0, "Bad TokenAllowances count!");
            assertEquals(((AccountDetails) o).getGrantedNftAllowancesCount(), 0, "Bad NftAllowances count!");
        });
        return this;
    }

    public AccountDetailsAsserts cryptoAllowancesContaining(String spender, long allowance) {

        registerProvider((spec, o) -> {
            var cryptoAllowance = GrantedCryptoAllowance.newBuilder()
                    .setAmount(allowance)
                    .setSpender(spec.registry().getAccountID(spender))
                    .build();
            assertTrue(
                    ((AccountDetails) o).getGrantedCryptoAllowancesList().contains(cryptoAllowance),
                    "Bad CryptoAllowances!");
        });
        return this;
    }

    public AccountDetailsAsserts tokenAllowancesContaining(String token, String spender, long allowance) {
        registerProvider((spec, o) -> {
            var tokenAllowance = GrantedTokenAllowance.newBuilder()
                    .setAmount(allowance)
                    .setTokenId(spec.registry().getTokenID(token))
                    .setSpender(spec.registry().getAccountID(spender))
                    .build();
            assertTrue(
                    ((AccountDetails) o).getGrantedTokenAllowancesList().contains(tokenAllowance),
                    "Bad TokenAllowances!");
        });
        return this;
    }

    public AccountDetailsAsserts nftApprovedAllowancesContaining(String token, String spender) {
        registerProvider((spec, o) -> {
            var nftAllowance = GrantedNftAllowance.newBuilder()
                    .setTokenId(spec.registry().getTokenID(token))
                    .setSpender(spec.registry().getAccountID(spender))
                    .build();
            assertTrue(((AccountDetails) o).getGrantedNftAllowancesList().contains(nftAllowance), "Bad NftAllowances!");
        });
        return this;
    }

    public AccountDetailsAsserts cryptoAllowancesCount(int count) {
        registerProvider((spec, o) ->
                assertEquals(count, ((AccountDetails) o).getGrantedCryptoAllowancesCount(), "Bad CryptoAllowances!"));
        return this;
    }

    public AccountDetailsAsserts tokenAllowancesCount(int count) {
        registerProvider((spec, o) ->
                assertEquals(count, ((AccountDetails) o).getGrantedTokenAllowancesCount(), "Bad TokenAllowances!"));
        return this;
    }

    public AccountDetailsAsserts nftApprovedForAllAllowancesCount(int count) {
        registerProvider((spec, o) ->
                assertEquals(count, ((AccountDetails) o).getGrantedNftAllowancesCount(), "Bad NFTAllowances!"));
        return this;
    }

    public AccountDetailsAsserts balanceLessThan(long amount) {
        registerProvider((spec, o) -> {
            long actual = ((AccountDetails) o).getBalance();
            String errorMessage = String.format("Bad balance! %s is not less than %s", actual, amount);
            assertTrue(actual < amount, errorMessage);
        });
        return this;
    }

    public AccountDetailsAsserts balance(long amount) {
        registerProvider((spec, o) -> assertEquals(amount, ((AccountDetails) o).getBalance(), "Bad balance!"));
        return this;
    }

    public AccountDetailsAsserts deleted(boolean deleted) {
        registerProvider((spec, o) -> assertEquals(deleted, ((AccountDetails) o).getDeleted(), "Bad deleted!"));
        return this;
    }
}
