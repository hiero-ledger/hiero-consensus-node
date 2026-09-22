// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip904;

import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenAirdrop;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/** Regression: airdrop with a Long.MIN_VALUE sender amount is rejected, not wrapped past the balance check. */
@Tag(INTEGRATION)
@TargetEmbeddedMode(CONCURRENT)
@DisplayName("Token airdrop with out-of-range amount is rejected at creation")
public class TokenAirdropAmountRangeTest {
    private static final String PAYER = "payer";
    private static final String SENDER_ONE = "senderOne";
    private static final String SENDER_TWO = "senderTwo";
    private static final String LARGE_RECEIVER = "largeReceiver";
    private static final String MAX_RECEIVER = "maxReceiver";
    private static final String COMPANION_ONE = "companionOne";
    private static final String COMPANION_TWO = "companionTwo";
    private static final String DUST_RECEIVER = "dustReceiver";
    private static final String TREASURY = "treasury";
    private static final String TOKEN = "token";

    private static final long ACCOUNT_HBAR_BALANCE = 100 * ONE_HBAR;
    private static final long INITIAL_SENDER_BALANCE = 10L;
    private static final long INITIAL_LARGE_RECEIVER_BALANCE = 1_000_000L;
    private static final long INITIAL_MAX_RECEIVER_BALANCE = 2L;
    private static final long HALF_LARGE_RECEIVER_DEBIT = INITIAL_LARGE_RECEIVER_BALANCE / 2;
    private static final long HUGE_TO_LARGE_RECEIVER = Long.MAX_VALUE - HALF_LARGE_RECEIVER_DEBIT + 1L;
    private static final long PENDING_COMPANION_AMOUNT = HALF_LARGE_RECEIVER_DEBIT - 1L;

    @HapiTest
    @DisplayName("Airdrop with Long.MIN_VALUE sender amount is rejected and moves no tokens")
    final Stream<DynamicTest> airdropWithMinValueSenderAmountIsRejected() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ACCOUNT_HBAR_BALANCE),
                cryptoCreate(SENDER_ONE).balance(ACCOUNT_HBAR_BALANCE),
                cryptoCreate(SENDER_TWO).balance(ACCOUNT_HBAR_BALANCE),
                cryptoCreate(LARGE_RECEIVER).balance(ACCOUNT_HBAR_BALANCE).receiverSigRequired(true),
                cryptoCreate(MAX_RECEIVER).balance(ACCOUNT_HBAR_BALANCE).receiverSigRequired(true),
                cryptoCreate(COMPANION_ONE).balance(ACCOUNT_HBAR_BALANCE).receiverSigRequired(true),
                cryptoCreate(COMPANION_TWO).balance(ACCOUNT_HBAR_BALANCE).receiverSigRequired(true),
                cryptoCreate(DUST_RECEIVER).balance(ACCOUNT_HBAR_BALANCE),
                cryptoCreate(TREASURY).balance(ACCOUNT_HBAR_BALANCE),
                tokenCreate(TOKEN).treasury(TREASURY).initialSupply(2_000_100L),
                tokenAssociate(SENDER_ONE, TOKEN),
                tokenAssociate(SENDER_TWO, TOKEN),
                tokenAssociate(LARGE_RECEIVER, TOKEN),
                tokenAssociate(MAX_RECEIVER, TOKEN),
                tokenAssociate(COMPANION_ONE, TOKEN),
                tokenAssociate(COMPANION_TWO, TOKEN),
                tokenAssociate(DUST_RECEIVER, TOKEN),
                cryptoTransfer(
                        moving(INITIAL_SENDER_BALANCE, TOKEN).between(TREASURY, SENDER_ONE),
                        moving(INITIAL_SENDER_BALANCE, TOKEN).between(TREASURY, SENDER_TWO),
                        moving(INITIAL_LARGE_RECEIVER_BALANCE, TOKEN).between(TREASURY, LARGE_RECEIVER),
                        moving(INITIAL_MAX_RECEIVER_BALANCE, TOKEN).between(TREASURY, MAX_RECEIVER)),
                // Long.MIN_VALUE debit + credits summing to 2^63 (zero-sum); rejected now, accepted before the fix.
                airdropToLargeReceiver(SENDER_ONE, COMPANION_ONE).hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),
                airdropToLargeReceiver(SENDER_TWO, COMPANION_TWO).hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),
                airdropToMaxReceiver(SENDER_ONE).hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),
                airdropToMaxReceiver(SENDER_TWO).hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),
                // Nothing moved: no pending airdrops, no dust, balances unchanged.
                getAccountBalance(LARGE_RECEIVER).hasTokenBalance(TOKEN, INITIAL_LARGE_RECEIVER_BALANCE),
                getAccountBalance(MAX_RECEIVER).hasTokenBalance(TOKEN, INITIAL_MAX_RECEIVER_BALANCE),
                getAccountBalance(SENDER_ONE).hasTokenBalance(TOKEN, INITIAL_SENDER_BALANCE),
                getAccountBalance(SENDER_TWO).hasTokenBalance(TOKEN, INITIAL_SENDER_BALANCE),
                getAccountBalance(DUST_RECEIVER).hasTokenBalance(TOKEN, 0L));
    }

    private static HapiTokenAirdrop airdropToLargeReceiver(final String sender, final String companion) {
        return rawAirdrop(
                sender,
                credit(LARGE_RECEIVER, HUGE_TO_LARGE_RECEIVER),
                credit(companion, PENDING_COMPANION_AMOUNT),
                credit(DUST_RECEIVER, 1L));
    }

    private static HapiTokenAirdrop airdropToMaxReceiver(final String sender) {
        return rawAirdrop(sender, credit(MAX_RECEIVER, Long.MAX_VALUE), credit(DUST_RECEIVER, 1L));
    }

    private static HapiTokenAirdrop rawAirdrop(final String sender, final Credit... credits) {
        return tokenAirdrop((spec, op) -> {
                    final var transferList = TokenTransferList.newBuilder()
                            .setToken(spec.registry().getTokenID(TOKEN))
                            .addTransfers(accountAmount(spec, sender, Long.MIN_VALUE));
                    for (final var credit : credits) {
                        transferList.addTransfers(accountAmount(spec, credit.account(), credit.amount()));
                    }
                    op.addTokenTransfers(transferList);
                })
                .payingWith(PAYER)
                .signedBy(PAYER, sender);
    }

    private static AccountAmount accountAmount(final HapiSpec spec, final String account, final long amount) {
        return AccountAmount.newBuilder()
                .setAccountID(spec.registry().getAccountID(account))
                .setAmount(amount)
                .build();
    }

    private static Credit credit(final String account, final long amount) {
        return new Credit(account, amount);
    }

    private record Credit(String account, long amount) {}
}
