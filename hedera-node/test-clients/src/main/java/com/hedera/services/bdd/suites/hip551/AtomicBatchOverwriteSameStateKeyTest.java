// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.precompile.ContractBurnHTSSuite.ALICE;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.genRandomBytes;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@OrderedInIsolation
@HapiTestLifecycle
@Tag(TOKEN)
public class AtomicBatchOverwriteSameStateKeyTest {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        //        lifecycle.doAdhoc(
        //                overriding("atomicBatch.isEnabled", "true"), overriding("atomicBatch.maxNumberOfTransactions",
        // "50"));
    }

    @Order(1)
    @HapiTest
    @DisplayName("Mint, Burn and Delete NFT token without custom fees success in batch")
    public Stream<DynamicTest> mintBurnAndDeleteNFTWithoutCustomFeesSuccessInBatch() {
        final String nft = "nft";
        final String adminKey = "adminKey";
        final String nftSupplyKey = "nftSupplyKey";
        final String owner = "owner";
        final String batchOperator = "batchOperator";
        // create token mint transaction
        final var mintNFT = mintToken(
                        nft,
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList())
                .payingWith(owner)
                .batchKey(batchOperator);

        // create token burn inner transaction
        final var burnNFT = burnToken(nft, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L))
                .payingWith(owner)
                .signedBy(owner, nftSupplyKey)
                .batchKey(batchOperator);

        // delete token inner transaction
        final var deleteToken =
                tokenDelete(nft).payingWith(owner).signedBy(owner, adminKey).batchKey(batchOperator);

        return hapiTest(flattened(
                // create keys and accounts,
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(adminKey),
                newKeyNamed(nftSupplyKey),
                // create non-fungible token
                createMutableNFT(nft, owner, nftSupplyKey, adminKey),
                // perform the atomic batch transaction
                atomicBatch(mintNFT, mintNFT, burnNFT, mintNFT, deleteToken)
                        .payingWith(batchOperator)
                        .hasKnownStatus(SUCCESS)));
    }

    @Order(2)
    @HapiTest
    @DisplayName("Multiple crypto updates on same state key in batch")
    public Stream<DynamicTest> multipleCryptoUpdatesOnSameStateInBatch() {
        return hapiTest(
                newKeyNamed("key"),
                cryptoCreate("operator"),
                cryptoCreate("test").key("key").balance(ONE_HUNDRED_HBARS),
                newKeyNamed("newKey1"),
                newKeyNamed("newKey2"),
                newKeyNamed("newKey3"),
                atomicBatch(
                                cryptoUpdate("test")
                                        .key("newKey1")
                                        .memo("memo1")
                                        .payingWith(GENESIS)
                                        .signedBy(GENESIS, "key", "newKey1")
                                        .batchKey("operator"),
                                cryptoCreate("foo").batchKey("operator"),
                                cryptoUpdate("test")
                                        .key("newKey2")
                                        .memo("memo2")
                                        .payingWith(GENESIS)
                                        .signedBy(GENESIS, "newKey1", "newKey2")
                                        .batchKey("operator"),
                                cryptoUpdate("test")
                                        .key("newKey3")
                                        .memo("memo3")
                                        .payingWith(GENESIS)
                                        .signedBy(GENESIS, "newKey2", "newKey3")
                                        .batchKey("operator"))
                        .payingWith("operator"));
        // StreamValidationTest must not fail on the first two updates
        // just because the same slot they use is overwritten by the third one.
    }

    @Order(3)
    @HapiTest
    @DisplayName("Multiple token updates on same state key in batch")
    public Stream<DynamicTest> multipleTokenUpdatesOnSameStateInBatch() {
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("operator"),
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("treasury"),
                cryptoCreate("treasury_1").maxAutomaticTokenAssociations(-1),
                cryptoCreate("treasury_2").maxAutomaticTokenAssociations(-1),
                cryptoCreate("treasury_3").maxAutomaticTokenAssociations(-1),
                tokenCreate("test")
                        .adminKey("adminKey")
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000)
                        .treasury("treasury")
                        .supplyKey("adminKey"),
                atomicBatch(
                                tokenUpdate("test")
                                        .treasury("treasury_1")
                                        .payingWith("payer")
                                        .signedBy("payer", "adminKey", "treasury_1")
                                        .batchKey("operator"),
                                tokenUpdate("test")
                                        .treasury("treasury_2")
                                        .payingWith("payer")
                                        .signedBy("payer", "adminKey", "treasury_2")
                                        .batchKey("operator"),
                                tokenUpdate("test")
                                        .treasury("treasury_3")
                                        .payingWith("payer")
                                        .signedBy("payer", "adminKey", "treasury_3")
                                        .batchKey("operator"))
                        .payingWith("operator"));
        // StreamValidationTest must not fail on the first two updates
        // just because the same slot they use is overwritten by the third one.
    }

    @Order(4)
    @HapiTest
    @DisplayName("Submit to topic twice in batch")
    public Stream<DynamicTest> submitToTopicTwiceInBatch() {
        var topic = "topic";
        var submitKey = "submitKey";
        var batchOperator = "batchOperator";
        var topicSubmitter = "feePayer";

        var submit1 = submitMessageTo(topic)
                .payingWith(topicSubmitter)
                .signedByPayerAnd(submitKey, topicSubmitter)
                .batchKey(batchOperator);
        var submit2 = submitMessageTo(topic)
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

    @Order(5)
    @HapiTest
    @DisplayName("Validate mint precompile gas used for inner transaction")
    public Stream<DynamicTest> validateInnerCallToMintPrecompile() {
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
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
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

    //        @Order(6)
    //        @LeakyHapiTest
    //        final Stream<DynamicTest> streamsAreValid() {
    //            return hapiTest(validateStreams());
    //        }

    private HapiTokenCreate createMutableNFT(String tokenName, String treasury, String supplyKey, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .adminKey(adminKey)
                .supplyKey(supplyKey);
    }
}
