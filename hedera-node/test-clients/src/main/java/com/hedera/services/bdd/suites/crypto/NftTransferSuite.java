// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class NftTransferSuite {
    private static final String KEY = "multipurpose";
    private static final String USER_ACCOUNT_PREFIX = "party-";
    private static final String FEE_COLLECTOR = "feeCollector";
    private static final int NUM_ACCOUNTS = 10;
    // If this is set too high then prod Netty settings create a high
    // risk of server sending GOAWAY; and in practice it seems quite
    // hard to stabilize HapiSpec behavior in this event
    private static final int NUM_TOKEN_TYPES = 10;
    private static final int NUM_ROUNDS = 100;

    private static HapiSpecOperation mintTokensFor(String tokenName, int numTokens) {
        return mintToken(
                        tokenName,
                        IntStream.range(0, numTokens)
                                .mapToObj(id -> ByteString.copyFromUtf8("nft" + id))
                                .toList())
                .noLogging();
    }

    private static SpecOperation createAccounts(String prefix, int numAccounts) {
        // Create user accounts to partake in crypto transfers
        return parFor(
                0, numAccounts, id -> cryptoCreate(prefix + id).noLogging().balance(ONE_HUNDRED_HBARS));
    }

    private static String userAccountName(int id) {
        return USER_ACCOUNT_PREFIX + id;
    }

    private static String tokenTypeName(int id) {
        return "token" + id;
    }

    private static String tokenTreasuryName(int id) {
        return "token-treasury-" + id;
    }

    private static HapiSpecOperation createTokenTypes() {
        return blockingOrder(
                createAccounts("token-treasury-", NftTransferSuite.NUM_TOKEN_TYPES),
                parFor(0, NftTransferSuite.NUM_TOKEN_TYPES, id -> tokenCreate(tokenTypeName(id))
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NftTransferSuite.KEY)
                        .initialSupply(0L)
                        .treasury(tokenTreasuryName(id))
                        .noLogging()
                        .withCustom(fixedHbarFee(1L, NftTransferSuite.FEE_COLLECTOR))));
    }

    private static HapiSpecOperation createBasicAccounts() {
        return blockingOrder(
                newKeyNamed(KEY), cryptoCreate(FEE_COLLECTOR).noLogging().balance(0L));
    }

    private static SpecOperation associateAccountsWithTokenTypes() {
        String[] tokenNames = IntStream.range(0, NftTransferSuite.NUM_TOKEN_TYPES)
                .mapToObj(NftTransferSuite::tokenTypeName)
                .toArray(String[]::new);
        return parFor(0, NftTransferSuite.NUM_ACCOUNTS, id -> tokenAssociate(userAccountName(id), tokenNames)
                .noLogging());
    }

    private static HapiSpecOperation createAccountsAndNfts() {
        return blockingOrder(
                // Create user accounts to partake in crypto transfers
                inParallel(createAccounts(USER_ACCOUNT_PREFIX, NftTransferSuite.NUM_ACCOUNTS), createTokenTypes()),
                inParallel(
                        associateAccountsWithTokenTypes(),
                        parFor(
                                0,
                                NftTransferSuite.NUM_TOKEN_TYPES,
                                id -> mintTokensFor(tokenTypeName(id), NftTransferSuite.NUM_ACCOUNTS))));
    }

    private static List<HapiSpecOperation> opsFor(int from, int to, IntFunction<HapiSpecOperation> functionToRun) {
        return IntStream.range(from, to).mapToObj(functionToRun).toList();
    }

    private static SpecOperation parFor(int from, int to, IntFunction<HapiSpecOperation> functionToRun) {
        return inParallel(IntStream.range(from, to).mapToObj(functionToRun).toArray(SpecOperation[]::new));
    }

    private static HapiSpecOperation seqFor(int from, int to, IntFunction<HapiSpecOperation> functionToRun) {
        return blockingOrder(IntStream.range(from, to).mapToObj(functionToRun).toArray(HapiSpecOperation[]::new));
    }

    private static SpecOperation transferInitial() {
        return inParallel(IntStream.range(0, NUM_ACCOUNTS)
                .mapToObj(accountId -> opsFor(0, NftTransferSuite.NUM_TOKEN_TYPES, tokenId -> cryptoTransfer(
                                TokenMovement.movingUnique(tokenTypeName(tokenId), accountId + 1)
                                        .between(tokenTreasuryName(tokenId), userAccountName(accountId)))
                        .payingWith(GENESIS)
                        .noLogging()))
                .flatMap(List::stream)
                .toArray(SpecOperation[]::new));
    }

    private static HapiSpecOperation transferRound(int roundNum) {
        final int roundIdx = roundNum % 2;
        final int halfAccounts = NftTransferSuite.NUM_ACCOUNTS / 2;
        final int fromOffset;
        final int toOffset;

        if (roundIdx == 0) {
            fromOffset = 0;
            toOffset = 1;
        } else {
            fromOffset = 1;
            toOffset = 0;
        }

        HapiSpecOperation[] ops = IntStream.range(0, halfAccounts)
                .mapToObj(accountId -> opsFor(0, NftTransferSuite.NUM_TOKEN_TYPES, tokenId -> cryptoTransfer(
                                TokenMovement.movingUnique(tokenTypeName(tokenId), 2L * accountId + 1)
                                        .between(
                                                userAccountName(2 * accountId + fromOffset),
                                                userAccountName(2 * accountId + toOffset)))
                        .noLogging()
                        .payingWith(GENESIS)))
                .flatMap(List::stream)
                .toArray(HapiSpecOperation[]::new);
        return blockingOrder(logIt("----- Beginning round #" + roundNum + " -----"), inParallel(ops));
    }

    private static HapiSpecOperation setupNftTest() {
        return blockingOrder(createBasicAccounts(), createAccountsAndNfts());
    }

    @HapiTest
    final Stream<DynamicTest> transferNfts() {
        return hapiTest(setupNftTest(), transferInitial(), seqFor(0, NUM_ROUNDS, NftTransferSuite::transferRound));
    }
}
