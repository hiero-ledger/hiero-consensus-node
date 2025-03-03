// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.exceptions.HapiQueryCheckStateException;
import com.hederahashgraph.api.proto.java.TokenFreezeStatus;
import com.hederahashgraph.api.proto.java.TokenKycStatus;
import com.hederahashgraph.api.proto.java.TokenRelationship;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ExpectedTokenRel {
    private static final Logger log = LogManager.getLogger(ExpectedTokenRel.class);
    private final String token;

    private OptionalInt decimals = OptionalInt.empty();
    private OptionalLong balance = OptionalLong.empty();
    private Optional<TokenKycStatus> kycStatus = Optional.empty();
    private Optional<TokenFreezeStatus> freezeStatus = Optional.empty();

    private ExpectedTokenRel(String token) {
        this.token = token;
    }

    public static ExpectedTokenRel relationshipWith(String token) {
        return new ExpectedTokenRel(token);
    }

    public static void assertNoUnexpectedRels(
            String account, List<String> expectedAbsent, List<TokenRelationship> actualRels, HapiSpec spec) {
        for (String unexpectedToken : expectedAbsent) {
            for (TokenRelationship actualRel : actualRels) {
                var unexpectedId = spec.registry().getTokenID(unexpectedToken);
                if (actualRel.getTokenId().equals(unexpectedId)) {
                    String errMsg = String.format(
                            "Account '%s' should have had no relationship with token '%s'!", account, unexpectedToken);
                    log.error(errMsg);
                    throw new HapiQueryCheckStateException(errMsg);
                }
            }
        }
    }

    public static void assertExpectedRels(
            String account, List<ExpectedTokenRel> expectedRels, List<TokenRelationship> actualRels, HapiSpec spec) {
        for (ExpectedTokenRel rel : expectedRels) {
            boolean found = false;
            var expectedId = spec.registry().getTokenID(rel.getToken());
            for (TokenRelationship actualRel : actualRels) {
                if (actualRel.getTokenId().equals(expectedId)) {
                    found = true;
                    rel.getDecimals().ifPresent(d -> assertEquals(d, actualRel.getDecimals()));
                    rel.getBalance().ifPresent(a -> assertEquals(a, actualRel.getBalance()));
                    rel.getKycStatus().ifPresent(s -> assertEquals(s, actualRel.getKycStatus()));
                    rel.getFreezeStatus().ifPresent(s -> assertEquals(s, actualRel.getFreezeStatus()));
                }
            }
            if (!found) {
                String errMsg =
                        String.format("Account '%s' had no relationship with token '%s'!", account, rel.getToken());
                log.error(errMsg);
                throw new HapiQueryCheckStateException(errMsg);
            }
        }
    }

    public ExpectedTokenRel decimals(int expected) {
        decimals = OptionalInt.of(expected);
        return this;
    }

    public ExpectedTokenRel balance(long expected) {
        balance = OptionalLong.of(expected);
        return this;
    }

    public ExpectedTokenRel kyc(TokenKycStatus expected) {
        kycStatus = Optional.of(expected);
        return this;
    }

    public ExpectedTokenRel freeze(TokenFreezeStatus expected) {
        freezeStatus = Optional.of(expected);
        return this;
    }

    public boolean matches(final HapiSpec spec, final TokenRelationship rel) {
        final var registry = spec.registry();
        final var tokenId = registry.getTokenID(token);
        if (!tokenId.equals(rel.getTokenId())) {
            return false;
        }
        final AtomicBoolean allDetailsMatch = new AtomicBoolean(true);
        balance.ifPresent(l -> {
            if (l != rel.getBalance()) {
                allDetailsMatch.set(false);
            }
        });
        kycStatus.ifPresent(status -> {
            if (status != rel.getKycStatus()) {
                allDetailsMatch.set(false);
            }
        });
        freezeStatus.ifPresent(status -> {
            if (status != rel.getFreezeStatus()) {
                allDetailsMatch.set(false);
            }
        });
        decimals.ifPresent(d -> {
            if (d != rel.getDecimals()) {
                allDetailsMatch.set(false);
            }
        });
        return allDetailsMatch.get();
    }

    public String getToken() {
        return token;
    }

    public OptionalInt getDecimals() {
        return decimals;
    }

    public OptionalLong getBalance() {
        return balance;
    }

    public Optional<TokenKycStatus> getKycStatus() {
        return kycStatus;
    }

    public Optional<TokenFreezeStatus> getFreezeStatus() {
        return freezeStatus;
    }

    @Override
    public String toString() {
        return "ExpectedTokenRel{"
                + "token='"
                + token
                + '\''
                + ", decimals="
                + decimals
                + ", balance="
                + balance
                + ", kycStatus="
                + kycStatus
                + ", freezeStatus="
                + freezeStatus
                + '}';
    }
}
