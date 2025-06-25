// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdateNfts;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_METADATA_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_METADATA_OR_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenUpdateNftsSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
public class AtomicTokenUpdateNftsSuite {

    private static String TOKEN_TREASURY = "treasury";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungible";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String METADATA_KEY = "metadataKey";
    private static final String ADMIN_KEY = "adminKey";

    private static final String NFT_TEST_METADATA = " test metadata";
    private static final String RECEIVER = "receiver";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    /**
     * <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-850.md">HIP-850</a>
     * Tests that the transaction fails if it is not signed by the metadata key or the supply key.
     * @return the dynamic test
     */
    @HapiTest
    final Stream<DynamicTest> failsIfNoMetadataKeyOrSupplyKeySigns() {
        return defaultHapiSpec("failsIfNoMetadataKeyOrSupplyKeySigns")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(4),
                        cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(4),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(12L)
                                .supplyKey(SUPPLY_KEY)
                                .metadataKey(METADATA_KEY)
                                .initialSupply(0L),
                        tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                        mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("a"), copyFromUtf8("b"))))
                .when()
                .then(atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L))
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via("nftUpdateTxn")
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-850.md">HIP-850</a>
     * Tests that the transaction succeeds if it is signed by the supply key and metadata key while serials are in the treasury.
     * @return the dynamic test
     */
    @HapiTest
    final Stream<DynamicTest> supplyKeyAndMetadataKeyInTreasury() {
        return defaultHapiSpec("supplyKeyAndMetadataKeyInTreasury")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(4),
                        cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(4),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(12L)
                                .supplyKey(SUPPLY_KEY)
                                .metadataKey(METADATA_KEY)
                                .initialSupply(0L),
                        tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                        mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("a"), copyFromUtf8("b"))))
                .when()
                .then(atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L))
                                .signedBy(SUPPLY_KEY, METADATA_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via("nftUpdateTxn")
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    /**
     * <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-850.md">HIP-850</a>
     * Tests that the transaction succeeds if it is signed by the supply key and metadata key while serials are outside the treasury.
     * @return the dynamic test
     */
    @HapiTest
    final Stream<DynamicTest> supplyKeyAndMetadataKeyOutsideTreasury() {
        return defaultHapiSpec("supplyKeyAndMetadataKeyOutsideTreasury")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(4),
                        cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(4),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(12L)
                                .supplyKey(SUPPLY_KEY)
                                .metadataKey(METADATA_KEY)
                                .initialSupply(0L),
                        tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                        mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("a"), copyFromUtf8("b"))))
                .when(cryptoTransfer(
                        TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, RECEIVER)))
                .then(atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L))
                                .signedBy(SUPPLY_KEY, METADATA_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via("nftUpdateTxn")
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    /**
     * <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-850.md">HIP-850</a>
     * Tests that the transaction succeeds if it is signed by the metadata key while serials are in the treasury.
     * @return the dynamic test
     */
    @HapiTest
    final Stream<DynamicTest> metadataKeySignedInTreasury() {
        return defaultHapiSpec("supplyKeyAndMetadataKeyOutsideTreasury")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(4),
                        cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(4),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(12L)
                                .supplyKey(SUPPLY_KEY)
                                .metadataKey(METADATA_KEY)
                                .initialSupply(0L),
                        tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                        mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("a"), copyFromUtf8("b"))))
                .when()
                .then(atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L))
                                .signedBy(METADATA_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via("nftUpdateTxn")
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    /**
     * <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-850.md">HIP-850</a>
     * Tests the transaction succeeds if it is signed by the metadata key while serials are outside the treasury.
     * @return the dynamic test
     */
    @HapiTest
    final Stream<DynamicTest> metadataKeySignedOutsideTreasury() {
        return defaultHapiSpec("metadataKeySignedOutsideTreasury")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(4),
                        cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(4),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(12L)
                                .supplyKey(SUPPLY_KEY)
                                .metadataKey(METADATA_KEY)
                                .initialSupply(0L),
                        tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                        mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("a"), copyFromUtf8("b"))))
                .when(cryptoTransfer(
                        TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, RECEIVER)))
                .then(atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L))
                                .signedBy(METADATA_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via("nftUpdateTxn")
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    /**
     * <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-850.md">HIP-850</a>
     * Tests that the transaction succeeds if it is signed by the supply key while serials are in the treasury.
     * @return the dynamic test
     */
    @HapiTest
    final Stream<DynamicTest> supplyKeySignedInTreasury() {
        return defaultHapiSpec("supplyKeySignedInTreasury")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(4),
                        cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(4),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(12L)
                                .supplyKey(SUPPLY_KEY)
                                .metadataKey(METADATA_KEY)
                                .initialSupply(0L),
                        tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                        mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("a"), copyFromUtf8("b"))))
                .when()
                .then(atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L))
                                .signedBy(SUPPLY_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via("nftUpdateTxn")
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    /**
     * <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-850.md">HIP-850</a>
     * Tests that the transaction fails if it is signed by the supply key while serials are outside the treasury.
     * @return the dynamic test
     */
    @HapiTest
    final Stream<DynamicTest> supplyKeySignedOutsideTreasury() {
        return defaultHapiSpec("supplyKeySignedOutsideTreasury")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(4),
                        cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(4),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(12L)
                                .supplyKey(SUPPLY_KEY)
                                .metadataKey(METADATA_KEY)
                                .initialSupply(0L),
                        tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                        mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("a"), copyFromUtf8("b"))))
                .when(cryptoTransfer(
                        TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, RECEIVER)))
                .then(atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L))
                                .signedBy(SUPPLY_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via("nftUpdateTxn")
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-850.md">HIP-850</a>
     * Tests that the transaction succeeds if it is signed by the supply key (Token no metadata key) while serials are in the treasury.
     * @return the dynamic test
     */
    @HapiTest
    final Stream<DynamicTest> noMetadataKeySignedInTreasury() {
        return defaultHapiSpec("noMetadataKeySignedInTreasury")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(4),
                        cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(4),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(12L)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(0L),
                        tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                        mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("a"), copyFromUtf8("b"))))
                .when()
                .then(atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L))
                                .signedBy(SUPPLY_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via("nftUpdateTxn")
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    /**
     * <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-850.md">HIP-850</a>
     * Tests that the transaction fails if it is signed by the supply key (Token no metadata key) while serials are outside the treasury.
     * @return the dynamic test
     */
    @HapiTest
    final Stream<DynamicTest> noMetadataKeySignedOutsideTreasury() {
        return defaultHapiSpec("noMetadataKeySignedOutsideTreasury")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(4),
                        cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(4),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(12L)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(0L),
                        tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                        mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("a"), copyFromUtf8("b"))))
                .when(cryptoTransfer(
                        TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, RECEIVER)))
                .then(atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L))
                                .signedBy(SUPPLY_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via("nftUpdateTxn")
                                .hasKnownStatus(TOKEN_HAS_NO_METADATA_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-850.md">HIP-850</a>
     * Tests that the transaction will fail if the Token does not have a metadata key or supply key after minting.
     * @return the dynamic test
     */
    @HapiTest
    final Stream<DynamicTest> noTokenMetadataKeyOrSupplyKeyAfterMinting() {
        return defaultHapiSpec("noTokenMetadataKeyOrSupplyKeyAfterMinting")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        newKeyNamed(ADMIN_KEY),
                        cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(4),
                        cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(4),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(12L)
                                .supplyKey(SUPPLY_KEY)
                                .metadataKey(METADATA_KEY)
                                .adminKey(ADMIN_KEY)
                                .initialSupply(0L),
                        tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                        mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("a"), copyFromUtf8("b"))))
                .when(tokenUpdate(NON_FUNGIBLE_TOKEN)
                        .signedBy(ADMIN_KEY)
                        .properlyEmptyingSupplyKey()
                        .properlyEmptyingMetadataKey()
                        .payingWith(TOKEN_TREASURY)
                        .fee(10 * ONE_HBAR)
                        .via("tokenUpdateTxn"))
                .then(atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L))
                                .signedBy(SUPPLY_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via("nftUpdateTxn")
                                .hasKnownStatus(TOKEN_HAS_NO_METADATA_OR_SUPPLY_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> updateMetadataOfNfts() {
        return defaultHapiSpec("updateMetadataOfNfts")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(12L)
                                .supplyKey(SUPPLY_KEY)
                                .metadataKey(METADATA_KEY)
                                .initialSupply(0L),
                        mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        copyFromUtf8("a"),
                                        copyFromUtf8("b"),
                                        copyFromUtf8("c"),
                                        copyFromUtf8("d"),
                                        copyFromUtf8("e"),
                                        copyFromUtf8("f"),
                                        copyFromUtf8("g"))))
                .when()
                .then(
                        getTokenNftInfo(NON_FUNGIBLE_TOKEN, 7L)
                                .hasSerialNum(7L)
                                .hasMetadata(ByteString.copyFromUtf8("g")),
                        atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(7L))
                                        .signedBy(GENESIS)
                                        .hasKnownStatus(INVALID_SIGNATURE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(7L))
                                        .signedBy(DEFAULT_PAYER, METADATA_KEY)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        getTokenNftInfo(NON_FUNGIBLE_TOKEN, 7L)
                                .hasSerialNum(7L)
                                .hasMetadata(ByteString.copyFromUtf8(NFT_TEST_METADATA)),
                        atomicBatch(burnToken(NON_FUNGIBLE_TOKEN, List.of(7L)).batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 6L));
    }
}
