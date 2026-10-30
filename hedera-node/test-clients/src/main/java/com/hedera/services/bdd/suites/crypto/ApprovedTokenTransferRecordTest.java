// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFeeNetOfTransfers;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingWithAllowance;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.assertions.ErroringAsserts;
import com.hedera.services.bdd.spec.assertions.ErroringAssertsProvider;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * The record's token transfer lists are rebuilt from net state changes, which know nothing about allowances; these
 * tests check that a fungible debit made via an allowance is still externalized with {@code is_approval=true} and the
 * owner's numeric id, while ordinary debits and credits stay {@code false}.
 */
@Tag(CRYPTO)
public class ApprovedTokenTransferRecordTest {
    private static final String OWNER = "approvedXferOwner";
    private static final String OWNER_KEY = "approvedXferOwnerKey";
    private static final String SPENDER = "approvedXferSpender";
    private static final String RECEIVER = "approvedXferReceiver";
    private static final String COLLECTOR = "approvedXferCollector";
    private static final String FUNGIBLE_TOKEN = "approvedXferToken";
    private static final String TXN = "approvedXfer";
    private static final long ALLOWANCE = 100L;
    private static final long AMOUNT = 30L;

    @HapiTest
    final Stream<DynamicTest> approvedDebitIsExternalizedWithIsApproval() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                tokenCreate(FUNGIBLE_TOKEN).treasury(TOKEN_TREASURY).initialSupply(1_000L),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(ALLOWANCE, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance().payingWith(OWNER).addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, ALLOWANCE),
                cryptoTransfer(movingWithAllowance(AMOUNT, FUNGIBLE_TOKEN).between(OWNER, RECEIVER))
                        .payingWith(SPENDER)
                        .signedBy(SPENDER)
                        .via(TXN),
                getTxnRecord(TXN)
                        .hasPriority(recordWith()
                                .tokenTransfers(adjustment(FUNGIBLE_TOKEN, OWNER, -AMOUNT, true))
                                .tokenTransfers(adjustment(FUNGIBLE_TOKEN, RECEIVER, AMOUNT, false))));
    }

    @HapiTest
    final Stream<DynamicTest> ordinaryDebitIsExternalizedWithoutIsApproval() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                tokenCreate(FUNGIBLE_TOKEN).treasury(TOKEN_TREASURY).initialSupply(1_000L),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(ALLOWANCE, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER)),
                cryptoTransfer(moving(AMOUNT, FUNGIBLE_TOKEN).between(OWNER, RECEIVER))
                        .payingWith(OWNER)
                        .via(TXN),
                getTxnRecord(TXN)
                        .hasPriority(recordWith()
                                .tokenTransfers(adjustment(FUNGIBLE_TOKEN, OWNER, -AMOUNT, false))
                                .tokenTransfers(adjustment(FUNGIBLE_TOKEN, RECEIVER, AMOUNT, false))));
    }

    /** The owner is named by its key alias in the transfer, but the record still shows its numeric id. */
    @HapiTest
    final Stream<DynamicTest> aliasedOwnerIsExternalizedWithNumericIdAndIsApproval() {
        return hapiTest(
                newKeyNamed(OWNER_KEY).shape(KeyShape.ED25519),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(SPENDER).balance(THOUSAND_HBAR),
                cryptoCreate(RECEIVER),
                // Auto-creation puts the owner's alias in state; then register its id under the key name
                cryptoTransfer(tinyBarsFromAccountToAlias(SPENDER, OWNER_KEY, ONE_HUNDRED_HBARS)),
                doingContextual(spec -> updateSpecFor(spec, OWNER_KEY)),
                tokenCreate(FUNGIBLE_TOKEN).treasury(TOKEN_TREASURY).initialSupply(1_000L),
                tokenAssociate(OWNER_KEY, FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(ALLOWANCE, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER_KEY)),
                cryptoApproveAllowance()
                        .payingWith(OWNER_KEY)
                        .addTokenAllowance(OWNER_KEY, FUNGIBLE_TOKEN, SPENDER, ALLOWANCE),
                cryptoTransfer((spec, builder) -> builder.addTokenTransfers(TokenTransferList.newBuilder()
                                .setToken(spec.registry().getTokenID(FUNGIBLE_TOKEN))
                                .addTransfers(AccountAmount.newBuilder()
                                        .setAccountID(AccountID.newBuilder()
                                                .setAlias(spec.registry()
                                                        .getKey(OWNER_KEY)
                                                        .toByteString()))
                                        .setAmount(-AMOUNT)
                                        .setIsApproval(true))
                                .addTransfers(AccountAmount.newBuilder()
                                        .setAccountID(spec.registry().getAccountID(RECEIVER))
                                        .setAmount(AMOUNT))))
                        .payingWith(SPENDER)
                        .signedBy(SPENDER)
                        .via(TXN),
                getTxnRecord(TXN)
                        .hasPriority(recordWith()
                                .tokenTransfers(adjustment(FUNGIBLE_TOKEN, OWNER_KEY, -AMOUNT, true))
                                .tokenTransfers(adjustment(FUNGIBLE_TOKEN, RECEIVER, AMOUNT, false))));
    }

    /** A fee charged to the owner makes its net debit differ from the approved amount; the flag must survive. */
    @HapiTest
    final Stream<DynamicTest> approvedDebitRebuiltWithCustomFeeStaysApproved() {
        final var fee = AMOUNT / 10;
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(COLLECTOR),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                tokenCreate(FUNGIBLE_TOKEN)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(1_000L)
                        .withCustom(fractionalFeeNetOfTransfers(1, 10, 1, OptionalLong.empty(), COLLECTOR))
                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, COLLECTOR),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(ALLOWANCE, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance().payingWith(OWNER).addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, ALLOWANCE),
                cryptoTransfer(movingWithAllowance(AMOUNT, FUNGIBLE_TOKEN).between(OWNER, RECEIVER))
                        .payingWith(SPENDER)
                        .signedBy(SPENDER)
                        .via(TXN),
                getTxnRecord(TXN)
                        .hasPriority(recordWith()
                                .tokenTransfers(adjustment(FUNGIBLE_TOKEN, OWNER, -(AMOUNT + fee), true))
                                .tokenTransfers(adjustment(FUNGIBLE_TOKEN, RECEIVER, AMOUNT, false))
                                .tokenTransfers(adjustment(FUNGIBLE_TOKEN, COLLECTOR, fee, false))));
    }

    /** Asserts the record has exactly one adjustment of the account in the token, with the given amount and flag. */
    private static ErroringAssertsProvider<List<TokenTransferList>> adjustment(
            final String token, final String account, final long amount, final boolean isApproval) {
        return spec -> {
            final var tokenId = spec.registry().getTokenID(token);
            final var accountId = spec.registry().getAccountID(account);
            return (ErroringAsserts<List<TokenTransferList>>) allXfers -> {
                final var matching = allXfers.stream()
                        .filter(xfers -> tokenId.equals(xfers.getToken()))
                        .flatMap(xfers -> xfers.getTransfersList().stream())
                        .filter(aa -> accountId.equals(aa.getAccountID()))
                        .toList();
                try {
                    assertEquals(
                            1, matching.size(), "Expected one adjustment of " + account + " but found " + matching);
                    final var aa = matching.getFirst();
                    assertEquals(amount, aa.getAmount(), "Wrong amount for " + account);
                    assertEquals(isApproval, aa.getIsApproval(), "Wrong is_approval for " + account);
                } catch (Throwable t) {
                    return List.of(t);
                }
                return Collections.emptyList();
            };
        };
    }
}
