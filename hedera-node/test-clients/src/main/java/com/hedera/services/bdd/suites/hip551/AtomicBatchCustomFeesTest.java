// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.hbarLimit;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.maxCustomFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CUSTOM_FEE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
public class AtomicBatchCustomFeesTest {
    private static final String FT_WITH_FIXED_HBAR_FEE = "FT_WithFixedHbarFee";
    private static final String SENDER = "alice";
    private static final String RECEIVER = "bob";
    private static final String ACCOUNT_WITH_NO_ASSOCIATIONS = "carol";
    private static final String TREASURY = "treasury";
    private static final String FEE_COLLECTOR = "customFeeCollector";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    @DisplayName("FT transfer with custom fee rollback")
    @Tag(MATS)
    public Stream<DynamicTest> fungibleTokenTransferCustomFeeRollback() {
        final var successfulTransfer = cryptoTransfer(
                        moving(1, FT_WITH_FIXED_HBAR_FEE).between(SENDER, RECEIVER))
                .fee(ONE_HBAR)
                .payingWith(SENDER)
                .batchKey(BATCH_OPERATOR);
        // This transfer will fail because the account has no associations
        final var failingTransfer = cryptoTransfer(
                        moving(1, FT_WITH_FIXED_HBAR_FEE).between(SENDER, ACCOUNT_WITH_NO_ASSOCIATIONS))
                .fee(ONE_HBAR)
                .payingWith(SENDER)
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);

        return hapiTest(flattened(
                createAccounts(),
                tokenCreate(FT_WITH_FIXED_HBAR_FEE)
                        .treasury(TREASURY)
                        .withCustom(fixedHbarFee(1, FEE_COLLECTOR))
                        .initialSupply(100L),
                cryptoTransfer(moving(5, FT_WITH_FIXED_HBAR_FEE).between(TREASURY, SENDER)),
                getAccountBalance(FEE_COLLECTOR).hasTinyBars(0L),
                atomicBatch(successfulTransfer).payingWith(BATCH_OPERATOR),
                getAccountBalance(FEE_COLLECTOR).hasTinyBars(1L),
                atomicBatch(successfulTransfer, failingTransfer)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountBalance(FEE_COLLECTOR).hasTinyBars(1L)));
    }

    @HapiTest
    @DisplayName("NFT transfer with custom fee rollback")
    public Stream<DynamicTest> nftTransferCustomFeeRollback() {
        final var successfulTransfer = cryptoTransfer(
                        movingUnique("NFT", 1L).between(SENDER, RECEIVER),
                        moving(100, "feeDenom").between(TREASURY, SENDER))
                .batchKey(BATCH_OPERATOR);
        // This transfer will fail because the account has no associations
        final var failingTransfer = cryptoTransfer(
                        movingUnique("NFT", 2L).between(SENDER, ACCOUNT_WITH_NO_ASSOCIATIONS))
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);

        return hapiTest(flattened(
                createAccounts(),
                newKeyNamed("supplyKey"),
                tokenCreate("feeDenom").treasury(TREASURY).initialSupply(200),
                tokenAssociate(FEE_COLLECTOR, "feeDenom"),
                tokenCreate("NFT")
                        .withCustom(royaltyFeeNoFallback(1, 4, FEE_COLLECTOR))
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TREASURY)
                        .supplyKey("supplyKey")
                        .initialSupply(0),
                mintToken("NFT", List.of(copyFromUtf8("meta1"), copyFromUtf8("meta2"))),
                cryptoTransfer(
                        movingUnique("NFT", 1).between(TREASURY, SENDER),
                        movingUnique("NFT", 2).between(TREASURY, SENDER)),
                getAccountBalance(SENDER).hasTokenBalance("feeDenom", 0L),
                atomicBatch(
                                cryptoTransfer(
                                                movingUnique("NFT", 1L).between(SENDER, RECEIVER),
                                                moving(100, "feeDenom").between(TREASURY, SENDER))
                                        .batchKey(BATCH_OPERATOR),
                                failingTransfer)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountBalance(SENDER).hasTokenBalance("feeDenom", 0L),
                atomicBatch(successfulTransfer).payingWith(BATCH_OPERATOR),
                getAccountBalance(SENDER).hasTokenBalance("feeDenom", 75L)));
    }

    @HapiTest
    @DisplayName("Submit message to topic with custom fee rollback")
    public Stream<DynamicTest> submitMessageToTopicWithCustomFeesGetsReverted() {
        final var successfulSubmit = submitMessageTo("topic")
                .maxCustomFee(maxCustomFee(SENDER, hbarLimit(2)))
                .payingWith(SENDER)
                .batchKey(BATCH_OPERATOR);
        // Not enough custom fee limits, so it should fail
        final var failingSubmit = submitMessageTo("topic")
                .maxCustomFee(maxCustomFee(SENDER, hbarLimit(1)))
                .payingWith(SENDER)
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(MAX_CUSTOM_FEE_LIMIT_EXCEEDED);

        return hapiTest(flattened(
                createAccounts(),
                createTopic("topic").withConsensusCustomFee(fixedConsensusHbarFee(2, FEE_COLLECTOR)),
                getAccountBalance(FEE_COLLECTOR).hasTinyBars(0L),
                atomicBatch(successfulSubmit).payingWith(BATCH_OPERATOR),
                getAccountBalance(FEE_COLLECTOR).hasTinyBars(2L),
                atomicBatch(successfulSubmit, failingSubmit)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountBalance(FEE_COLLECTOR).hasTinyBars(2L)));
    }

    @HapiTest
    @DisplayName("Airdrop with custom fees rollback")
    public Stream<DynamicTest> airdropWithCustomFeesGetsReverted() {
        final var successfulAirdrop = tokenAirdrop(
                        moving(1, FT_WITH_FIXED_HBAR_FEE).between(SENDER, RECEIVER))
                .payingWith(SENDER)
                .batchKey(BATCH_OPERATOR);
        // This airdrop will fail because the sender didn't sign the transaction
        final var failingAirdrop = tokenAirdrop(
                        moving(1, FT_WITH_FIXED_HBAR_FEE).between(SENDER, RECEIVER))
                .payingWith(DEFAULT_PAYER)
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_SIGNATURE);

        return hapiTest(flattened(
                createAccounts(),
                tokenCreate(FT_WITH_FIXED_HBAR_FEE)
                        .treasury(TREASURY)
                        .withCustom(fixedHbarFee(1, FEE_COLLECTOR))
                        .initialSupply(100L),
                cryptoTransfer(moving(5, FT_WITH_FIXED_HBAR_FEE).between(TREASURY, SENDER)),
                getAccountBalance(FEE_COLLECTOR).hasTinyBars(0L),
                atomicBatch(successfulAirdrop).payingWith(BATCH_OPERATOR),
                getAccountBalance(FEE_COLLECTOR).hasTinyBars(1L),
                atomicBatch(successfulAirdrop, failingAirdrop)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountBalance(FEE_COLLECTOR).hasTinyBars(1L)));
    }

    private List<SpecOperation> createAccounts() {
        return List.of(
                cryptoCreate(SENDER).maxAutomaticTokenAssociations(100),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(100),
                cryptoCreate(ACCOUNT_WITH_NO_ASSOCIATIONS).maxAutomaticTokenAssociations(0),
                cryptoCreate(TREASURY),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                cryptoCreate(BATCH_OPERATOR));
    }
}
