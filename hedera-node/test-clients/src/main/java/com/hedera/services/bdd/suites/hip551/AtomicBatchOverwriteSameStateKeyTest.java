// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.precompile.ContractBurnHTSSuite.ALICE;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.genRandomBytes;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.lang.String.valueOf;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests the construction of legacy records for inner transactions within atomic batches
 * using trace data.
 * <p>
 * Some operations require state changes to translate the block stream in to records,
 * later transactions in the same atomic batch can overwrite earlier changes.
 * These tests verify that the system correctly captures each transaction's
 * effects using trace data, allowing accurate record generation even when
 * state is overwritten within the same batch.
 */
@HapiTestLifecycle
@Tag(TOKEN)
public class AtomicBatchOverwriteSameStateKeyTest {

    private static final String OPERATOR = "operator";
    private static final String ADMIN_KEY = "adminKey";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    /**
     * Mint, Burn and Delete NFT token
     * @return HAPI test
     */
    @HapiTest
    @DisplayName("Mint, Burn and Delete NFT token")
    public Stream<DynamicTest> mintBurnAndDeleteNftWithoutCustomFeesSuccessInBatch() {
        final String nft = "nft";
        final String adminKey = ADMIN_KEY;
        final String nftSupplyKey = "nftSupplyKey";
        final String owner = "owner";
        final String batchOperator = "batchOperator";
        // create token mint transaction
        final var mintNft = mintToken(
                        nft,
                        IntStream.range(0, 10)
                                .mapToObj(a -> copyFromUtf8(valueOf(a)))
                                .toList())
                .payingWith(owner)
                .batchKey(batchOperator);

        // create token burn inner transaction
        final var burnNft = burnToken(nft, List.of(1L, 2L, 3L, 4L, 5L))
                .payingWith(owner)
                .signedBy(owner, nftSupplyKey)
                .batchKey(batchOperator);

        // delete token inner transaction
        final var deleteToken =
                tokenDelete(nft).payingWith(owner).signedBy(owner, adminKey).batchKey(batchOperator);

        return hapiTest(
                // create keys and accounts,
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(adminKey),
                newKeyNamed(nftSupplyKey),
                // create non-fungible token
                tokenCreate(nft)
                        .initialSupply(0)
                        .treasury(owner)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .adminKey(adminKey)
                        .supplyKey(nftSupplyKey),
                // perform the atomic batch transaction
                atomicBatch(mintNft, burnNft, deleteToken)
                        .payingWith(batchOperator)
                        .hasKnownStatus(SUCCESS));
    }

    /**
     * Multiple crypto updates on same state key
     * @return HAPI test
     */
    @HapiTest
    @DisplayName("Multiple crypto updates on same state key")
    public Stream<DynamicTest> multipleCryptoUpdatesOnSameStateInBatch() {
        final var key = "key";
        final var newKey = "newKey1";
        final var newKey2 = "newKey2";
        final var newKey3 = "newKey3";
        final var account = "account";

        return hapiTest(
                newKeyNamed(key),
                cryptoCreate(OPERATOR),
                cryptoCreate(account).key(key).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(newKey),
                newKeyNamed(newKey2),
                newKeyNamed(newKey3),
                atomicBatch(
                                cryptoUpdate(account)
                                        .key(newKey)
                                        .memo("memo1")
                                        .payingWith(GENESIS)
                                        .signedBy(GENESIS, key, newKey)
                                        .batchKey(OPERATOR),
                                cryptoCreate("foo").batchKey(OPERATOR),
                                cryptoUpdate(account)
                                        .key(newKey2)
                                        .memo("memo2")
                                        .payingWith(GENESIS)
                                        .signedBy(GENESIS, newKey, newKey2)
                                        .batchKey(OPERATOR),
                                cryptoUpdate(account)
                                        .key(newKey3)
                                        .memo("memo3")
                                        .payingWith(GENESIS)
                                        .signedBy(GENESIS, newKey2, newKey3)
                                        .batchKey(OPERATOR))
                        .payingWith(OPERATOR));
        // StreamValidationTest must not fail on the first two updates
        // just because the same slot they use is overwritten by the third one.
    }

    /**
     * Multiple token updates on same state key in batch
     * @return HAPI test
     */
    @HapiTest
    @DisplayName("Multiple token updates on same state key in batch")
    public Stream<DynamicTest> multipleTokenUpdatesOnSameStateInBatch() {
        final var token = "test";
        final var treasury = "treasury";
        final var treasury1 = "treasury1";
        final var treasury2 = "treasury2";
        final var treasury3 = "treasury3";
        final var payer = "payer";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(OPERATOR),
                cryptoCreate(payer).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(treasury),
                cryptoCreate(treasury1).maxAutomaticTokenAssociations(-1),
                cryptoCreate(treasury2).maxAutomaticTokenAssociations(-1),
                cryptoCreate(treasury3).maxAutomaticTokenAssociations(-1),
                tokenCreate(token)
                        .adminKey(ADMIN_KEY)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000)
                        .treasury(treasury)
                        .supplyKey(ADMIN_KEY),
                atomicBatch(
                                tokenUpdate(token)
                                        .treasury(treasury1)
                                        .payingWith(payer)
                                        .signedBy(payer, ADMIN_KEY, treasury1)
                                        .batchKey(OPERATOR),
                                tokenUpdate(token)
                                        .treasury(treasury2)
                                        .payingWith(payer)
                                        .signedBy(payer, ADMIN_KEY, treasury2)
                                        .batchKey(OPERATOR),
                                tokenUpdate(token)
                                        .treasury(treasury3)
                                        .payingWith(payer)
                                        .signedBy(payer, ADMIN_KEY, treasury3)
                                        .batchKey(OPERATOR))
                        .payingWith(OPERATOR));
        // StreamValidationTest must not fail on the first two updates
        // just because the same slot they use is overwritten by the third one.
    }

    /**
     * Submit to topic twice in batch
     * @return HAPI test
     */
    @HapiTest
    @DisplayName("Submit to topic twice in batch")
    public Stream<DynamicTest> submitToTopicTwiceInBatch() {
        final var topic = "topic";
        final var submitKey = "submitKey";
        final var batchOperator = "batchOperator";
        final var topicSubmitter = "feePayer";

        final var submit1 = submitMessageTo(topic)
                .payingWith(topicSubmitter)
                .signedByPayerAnd(submitKey, topicSubmitter)
                .batchKey(batchOperator);
        final var submit2 = submitMessageTo(topic)
                .payingWith(topicSubmitter)
                .signedByPayerAnd(submitKey, topicSubmitter)
                .batchKey(batchOperator);

        return hapiTest(
                newKeyNamed(submitKey),
                newKeyNamed(batchOperator),
                cryptoCreate("collector").balance(ONE_HUNDRED_HBARS),
                cryptoCreate(topicSubmitter).balance(ONE_HUNDRED_HBARS),
                createTopic(topic).submitKeyName(submitKey),
                atomicBatch(submit1, submit2).signedByPayerAnd(batchOperator));
    }

    /**
     * Multiple mint precompile calls
     * @return HAPI test
     */
    @HapiTest
    @DisplayName("Multiple mint precompile calls")
    public Stream<DynamicTest> multipleMintPrecompileCalls() {
        final var nft = "nft";
        final var gasToOffer = 2_000_000L;
        final var mintContract = "MintContract";
        final var supplyKey = "supplyKey";
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();
        final KeyShape listOfPredefinedAndContract = KeyShape.threshOf(1, PREDEFINED_SHAPE, CONTRACT);
        final var nftMetadata = (Object) new byte[][] {genRandomBytes(100)};
        return hapiTest(
                cryptoCreate(ALICE).balance(10 * ONE_HUNDRED_HBARS),
                tokenCreate(nft)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(ALICE)
                        .adminKey(ALICE)
                        .treasury(ALICE)
                        .exposingAddressTo(tokenAddress::set),
                uploadInitCode(mintContract),
                sourcing(() -> contractCreate(mintContract, tokenAddress.get())
                        .payingWith(ALICE)
                        .gas(gasToOffer)),
                newKeyNamed(supplyKey).shape(listOfPredefinedAndContract.signedWith(sigs(ALICE, mintContract))),
                tokenUpdate(nft).supplyKey(supplyKey).signedByPayerAnd(ALICE),

                // mint NFT via precompile as inner batch txn
                atomicBatch(
                                contractCall(mintContract, "mintNonFungibleToken", nftMetadata)
                                        .batchKey(ALICE)
                                        .payingWith(ALICE)
                                        .alsoSigningWithFullPrefix(supplyKey)
                                        .gas(gasToOffer),
                                contractCall(mintContract, "mintNonFungibleToken", nftMetadata)
                                        .batchKey(ALICE)
                                        .payingWith(ALICE)
                                        .alsoSigningWithFullPrefix(supplyKey)
                                        .gas(gasToOffer),
                                contractCall(mintContract, "mintNonFungibleToken", nftMetadata)
                                        .batchKey(ALICE)
                                        .payingWith(ALICE)
                                        .alsoSigningWithFullPrefix(supplyKey)
                                        .gas(gasToOffer),
                                burnToken(nft, List.of(1L, 2L, 3L))
                                        .batchKey(ALICE)
                                        .payingWith(ALICE)
                                        .signedBy(ALICE, supplyKey))
                        .payingWith(ALICE));
    }

    /**
     * Multiple wipe token
     * @return HAPI test
     */
    @HapiTest
    @DisplayName("Multiple wipe token")
    public Stream<DynamicTest> multipleWipeToken() {
        final String nft = "nft";
        final String adminKey = ADMIN_KEY;
        final String treasury = "treasury";
        final String batchOperator = "batchOperator";
        final String account = "account";

        return hapiTest(
                cryptoCreate(treasury),
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(account).maxAutomaticTokenAssociations(-1),
                newKeyNamed(adminKey),
                tokenCreate(nft)
                        .initialSupply(0)
                        .treasury(treasury)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .adminKey(adminKey)
                        .supplyKey(adminKey)
                        .wipeKey(adminKey),
                atomicBatch(
                                // mint 1
                                mintToken(nft, List.of(copyFromUtf8(valueOf(1L))))
                                        .batchKey(batchOperator),
                                cryptoTransfer(movingUnique(nft, 1L).between(treasury, account))
                                        .batchKey(batchOperator),
                                wipeTokenAccount(nft, account, List.of(1L)).batchKey(batchOperator),

                                // mint 2
                                mintToken(nft, List.of(copyFromUtf8(valueOf(1L))))
                                        .batchKey(batchOperator),
                                burnToken(nft, List.of(2L)).batchKey(batchOperator),

                                // mint 3
                                mintToken(nft, List.of(copyFromUtf8(valueOf(1L))))
                                        .batchKey(batchOperator),
                                cryptoTransfer(movingUnique(nft, 3L).between(treasury, account))
                                        .batchKey(batchOperator),
                                wipeTokenAccount(nft, account, List.of(3L)).batchKey(batchOperator),

                                // mint 4
                                mintToken(nft, List.of(copyFromUtf8(valueOf(1L))))
                                        .batchKey(batchOperator),
                                burnToken(nft, List.of(4L)).batchKey(batchOperator))
                        .payingWith(batchOperator));
    }
}
