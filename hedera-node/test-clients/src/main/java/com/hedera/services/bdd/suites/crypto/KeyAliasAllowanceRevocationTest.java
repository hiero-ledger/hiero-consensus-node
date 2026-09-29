// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.allowanceTinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.AccountID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Regression test for revoking an HBAR allowance with a zero-amount update that names the spender by its legacy key
 * alias, i.e. the serialized protobuf {@code Key} its account was auto-created from. For an ED25519 key this alias is
 * 34 bytes long, so it is not an EVM address.
 *
 * <p>The allowance is granted naming the spender by account number, which is how allowances are stored.
 * {@code CryptoApproveAllowanceHandler} resolves the key alias in the revocation to that account number before
 * matching it against the owner's allowances, so the allowance is removed and the spender can no longer transfer the
 * owner's HBAR.
 */
@Tag(CRYPTO)
public class KeyAliasAllowanceRevocationTest {
    private static final String OWNER = "owner";
    private static final String RECEIVER = "receiver";
    private static final String SPENDER_KEY = "spenderKey";
    private static final String SPENDER = "spender";
    private static final long GRANT = 5 * ONE_HBAR;
    private static final long SPEND = ONE_HBAR;

    private final AtomicReference<ByteString> spenderAlias = new AtomicReference<>();

    @HapiTest
    Stream<DynamicTest> rawKeyAliasFormZeroUpdateRevokesNumericAllowance() {
        return hapiTest(
                // 1. Spender exists with a default (ED25519) key, auto-created via a transfer to its RAW
                //    protobuf-Key alias -- the legacy alias mechanism, distinct from the 20-byte EVM address
                //    form. This alias is longer than 20 bytes (an ED25519 key protobuf-encodes to 34 bytes:
                //    a 2-byte field tag/length header plus the 32-byte public key).
                newKeyNamed(SPENDER_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SPENDER_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, log) -> {
                    var rawKeyAlias = spec.registry().getKey(SPENDER_KEY).toByteString();
                    log.info(
                            "Spender's legacy key-alias is {} bytes long (not EVM-address size 20)",
                            rawKeyAlias.size());
                    if (rawKeyAlias.size() == 20) {
                        throw new AssertionError("expected a non-20-byte legacy key alias");
                    }
                    spenderAlias.set(rawKeyAlias);
                    allRunFor(spec, getAliasedAccountInfo(rawKeyAlias).exposingIdTo(id -> {
                        spec.registry().saveAccountId(SPENDER, id);
                        spec.registry().saveKey(SPENDER, spec.registry().getKey(SPENDER_KEY));
                    }));
                }),

                // 2. Owner grants a numeric HBAR allowance to the spender.
                cryptoApproveAllowance().payingWith(OWNER).addCryptoAllowance(OWNER, SPENDER, GRANT),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().cryptoAllowancesCount(1).cryptoAllowancesContaining(SPENDER, GRANT)),

                // 3. Owner submits the documented revocation: a zero-amount update, but with the spender
                //    written as its raw protobuf-Key alias instead of the numeric id it was granted under.
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 0L)
                        .withBodyMutation((builder, spec) -> {
                            var op = builder.getCryptoApproveAllowance().toBuilder();
                            var allowance = op.getCryptoAllowances(0).toBuilder();
                            allowance.setSpender(AccountID.newBuilder().setAlias(spenderAlias.get()));
                            return builder.setCryptoApproveAllowance(op.setCryptoAllowances(0, allowance));
                        })
                        .hasKnownStatus(SUCCESS),

                // 4. The allowance granted under the spender's numeric id is removed.
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().cryptoAllowancesCount(0)),

                // 5. A transfer by the spender under the allowance now fails, and the owner's balance is unchanged.
                balanceSnapshot("ownerBeforeSpend", OWNER),
                cryptoTransfer(allowanceTinyBarsFromTo(OWNER, RECEIVER, SPEND))
                        .payingWith(SPENDER)
                        .signedBy(SPENDER_KEY)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                getAccountBalance(OWNER).hasTinyBars(changeFromSnapshot("ownerBeforeSpend", 0L)));
    }
}
