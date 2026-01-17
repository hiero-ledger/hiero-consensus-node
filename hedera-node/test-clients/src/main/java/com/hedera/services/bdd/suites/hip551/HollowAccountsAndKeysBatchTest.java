// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingWithAllowance;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.THROTTLE_DEFS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.ThrottleDefsLoader.protoDefsFromResource;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_KEY_SET_ON_NON_INNER_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_LIST_CONTAINS_DUPLICATES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_ID_FIELD_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

public class HollowAccountsAndKeysBatchTest {
    private static final String OWNER = "owner";
    private static final String SPENDER = "spender";
    private static final String RECEIVER = "receiver";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungible";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String NFT_TOKEN_MINT_TXN = "nftTokenMint";

    @HapiTest
    @DisplayName("Finalized Hollow w/ batch tx, should fail")
    public Stream<DynamicTest> cannotFinalizeHollowViaInnerTx() {
        final var batchOperator = "batchOperator";
        final var sender = "sender";
        final var alias = "alias";

        return hapiTest(flattened(
                // Batch Operator Account
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                // Sender Account
                cryptoCreate(sender).balance(ONE_HUNDRED_HBARS),
                // Hollow Account
                newKeyNamed(alias).shape(SECP_256K1_SHAPE),
                createHollowAccountFrom(alias),
                getAliasedAccountInfo(alias).isHollow(),
                getAccountBalance(alias).hasTinyBars(ONE_HUNDRED_HBARS),

                // Atomic Batch to finalize Hollow
                atomicBatch(
                                // Finalize Hollow by paying Inner Tx
                                cryptoTransfer(movingHbar(ONE_HBAR).between(sender, alias))
                                        .via("finalizeHollow")
                                        .batchKey(batchOperator)
                                        .payingWith(alias)
                                        .signedBy(sender, alias)
                                        .sigMapPrefixes(uniqueWithFullPrefixesFor(alias))
                                        .hasKnownStatus(SUCCESS))
                        .payingWith(batchOperator)
                        .hasKnownStatus(SUCCESS),
                getAccountBalance(sender).hasTinyBars(ONE_HUNDRED_HBARS - ONE_HBAR),
                getAliasedAccountInfo(alias).isHollow()));
    }

    @HapiTest
    @DisplayName("Hollow account tries to send funds to receiver after sigRequireUpdate inside batch, should fail")
    public Stream<DynamicTest> hollowSendFundsAfterSigRequireUpdateWithoutReceiverSig() {
        final var batchOperator = "batchOperator";
        final var sender = "sender";
        final var receiver = "receiver";
        final var alias = "alias"; // will be auto-created as hollow

        return hapiTest(flattened(
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(sender).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(alias).shape(SECP_256K1_SHAPE),
                createHollowAccountFrom(alias),
                getAliasedAccountInfo(alias)
                        .has(accountWith().maxAutoAssociations(-1).hasEmptyKey()),
                getAccountBalance(alias).hasTinyBars(ONE_HUNDRED_HBARS),
                newKeyNamed("recvKey"),
                cryptoCreate(receiver)
                        .key("recvKey")
                        .signedByPayerAnd("recvKey")
                        .balance(ONE_HUNDRED_HBARS),
                atomicBatch(
                                cryptoUpdate(receiver)
                                        .receiverSigRequired(true)
                                        .signedBy("recvKey")
                                        .payingWith(receiver)
                                        .batchKey(batchOperator),
                                cryptoTransfer(movingHbar(ONE_HBAR).between(alias, receiver))
                                        .via("toReceiverNoSig")
                                        .payingWith(alias)
                                        .batchKey(batchOperator)
                                        .signedBy(alias)
                                        .sigMapPrefixes(uniqueWithFullPrefixesFor(alias))
                                        .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith(batchOperator)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                // Check Hollow finalized
                getAliasedAccountInfo(alias).isHollow()));
    }

    @HapiTest
    @DisplayName("Privileged Batch does not grant bypass fees to inner unprivileged tx, should fail")
    public Stream<DynamicTest> mixedBatchNoPrivilegeFeeBypass() {
        final var batchOperator = "batchOperator";
        final var sender = "sender";
        final var receiver = "receiver";

        return hapiTest(
                // Setup accounts
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(sender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(receiver).balance(0L),

                // Create a mixed batch with a privileged inner txn and a normal crypto transfer
                atomicBatch(
                                fileUpdate(THROTTLE_DEFS)
                                        .batchKey(GENESIS)
                                        .noLogging()
                                        .payingWith(GENESIS)
                                        .via("privTransfer")
                                        .contents(protoDefsFromResource("testSystemFiles/mainnet-throttles.json")
                                                .toByteArray()),
                                cryptoTransfer(movingHbar(1).between(sender, receiver))
                                        .via("nonPrivTransfer")
                                        .batchKey(GENESIS)
                                        .payingWith(sender))
                        .payingWith(GENESIS)
                        .via("mixedBatch"),

                // Assert the privileged inner transfer is exempt
                validateInnerTxnChargedUsd("privTransfer", "mixedBatch", 0.0, 0),
                // Assert the non-privileged inner transfer is charged normal fees (not exempt)
                validateInnerTxnChargedUsd("nonPrivTransfer", "mixedBatch", 0.0001, 5));
    }

    @HapiTest
    @DisplayName("Transfer NFT after Approved Allowance with Deleted Spender in Batch, should fail")
    public final Stream<DynamicTest> transferViaApprovalAfterSpenderDeletedFailsInSameBatch() {
        final var batchOperator = "batchOperator";
        return hapiTest(
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                atomicBatch(
                                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN).batchKey(batchOperator),
                                tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN).batchKey(batchOperator),
                                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a")))
                                        .via(NFT_TOKEN_MINT_TXN)
                                        .batchKey(batchOperator),
                                // Approve all NFTs for SPENDER
                                cryptoApproveAllowance()
                                        .payingWith(OWNER)
                                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of())
                                        .batchKey(batchOperator),
                                // Delete the spender before attempting the allowance-based transfer
                                cryptoDelete(SPENDER).batchKey(batchOperator),
                                // Attempt to transfer using the (now invalid) spender allowance
                                cryptoTransfer((spec, builder) ->
                                                builder.addTokenTransfers(TokenTransferList.newBuilder()
                                                        .setToken(
                                                                spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))
                                                        .addNftTransfers(NftTransfer.newBuilder()
                                                                .setSerialNumber(1L)
                                                                .setSenderAccountID(spec.registry()
                                                                        .getAccountID(OWNER))
                                                                .setReceiverAccountID(spec.registry()
                                                                        .getAccountID(RECEIVER))
                                                                .setIsApproval(true)
                                                                .build())))
                                        .payingWith(SPENDER)
                                        .signedBy(OWNER, SPENDER)
                                        .batchKey(batchOperator)
                                        .via("transferWithDeletedSpender")
                                        .hasKnownStatus(PAYER_ACCOUNT_DELETED))
                        .payingWith(batchOperator)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    @DisplayName("Approve for All followed by Revoke and Transfer, should fail")
    public final Stream<DynamicTest> approveForAllThenRevokePreventsSubsequentTransferInSameBatch() {
        final var batchOperator = "batchOperator";
        return hapiTest(
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                atomicBatch(
                                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN).batchKey(batchOperator),
                                tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN).batchKey(batchOperator),
                                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a")))
                                        .via(NFT_TOKEN_MINT_TXN)
                                        .batchKey(batchOperator),
                                // Approve-for-all to SPENDER
                                cryptoApproveAllowance()
                                        .payingWith(OWNER)
                                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of())
                                        .batchKey(batchOperator),
                                // Revoke approve-for-all (set explicit empty serial list and false)
                                cryptoApproveAllowance()
                                        .payingWith(OWNER)
                                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of())
                                        .batchKey(batchOperator),
                                // Attempt transfer relying on revoked approval
                                cryptoTransfer((spec, builder) ->
                                                builder.addTokenTransfers(TokenTransferList.newBuilder()
                                                        .setToken(
                                                                spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))
                                                        .addNftTransfers(NftTransfer.newBuilder()
                                                                .setSerialNumber(1L)
                                                                .setSenderAccountID(spec.registry()
                                                                        .getAccountID(OWNER))
                                                                .setReceiverAccountID(spec.registry()
                                                                        .getAccountID(RECEIVER))
                                                                .setIsApproval(true)
                                                                .build())))
                                        .payingWith(SPENDER)
                                        .signedBy(SPENDER, OWNER)
                                        .batchKey(batchOperator)
                                        .via("transferAfterRevoke")
                                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE))
                        .payingWith(batchOperator)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    @DisplayName("Inner transaction with non-zero nonce, should fail")
    Stream<DynamicTest> innerTxnWithNonZeroNonceIsRejected() {
        final var alice = "alice";
        final var batchOperator = "batchOperator";

        return hapiTest(
                cryptoCreate(alice).balance(ONE_HBAR),
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),

                // Create and register TransactionID with non-zero nonce
                doingContextual(spec -> {
                    final var payerId = TxnUtils.asId(alice, spec);
                    final var validStart =
                            TxnUtils.getUniqueTimestampPlusSecs(spec.setup().txnStartOffsetSecs());
                    final var txnId = TransactionID.newBuilder()
                            .setAccountID(payerId)
                            .setTransactionValidStart(validStart)
                            .setNonce(1337) // Set non-zero nonce - this should be rejected per HIP-551
                            .build();
                    spec.registry().saveTxnId("innerTxnWithNonce", txnId);
                }),
                atomicBatch(cryptoTransfer(movingHbar(1).between(alice, batchOperator))
                                .batchKey(batchOperator)
                                .txnId("innerTxnWithNonce")
                                .payingWith(alice))
                        .payingWith(batchOperator)
                        .hasPrecheck(TRANSACTION_ID_FIELD_NOT_ALLOWED));
    }

    @HapiTest
    @DisplayName("Missing signature from all inner txs batch keys, should fail")
    Stream<DynamicTest> multipleInnerBatchKeysMissingSig() {
        final var alice = "alice";
        final var bob = "bob";
        final var batchOperatorOne = "batchOperatorOne";
        final var batchOperatorTwo = "batchOperatorTwo";
        final var batchOperatorThree = "batchOperatorThree";

        return hapiTest(
                cryptoCreate(alice).balance(ONE_HBAR),
                cryptoCreate(bob).balance(ONE_HBAR),
                cryptoCreate(batchOperatorOne).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(batchOperatorTwo).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(batchOperatorThree).balance(ONE_HUNDRED_HBARS),
                atomicBatch(
                                cryptoTransfer(movingHbar(1).between(alice, bob))
                                        .batchKey(batchOperatorOne)
                                        .payingWith(alice),
                                cryptoTransfer(movingHbar(2).between(bob, alice))
                                        .batchKey(batchOperatorTwo)
                                        .payingWith(bob))
                        .payingWith(batchOperatorOne)
                        .signedBy(batchOperatorOne)
                        .hasKnownStatus(INVALID_SIGNATURE),
                atomicBatch(
                                cryptoTransfer(movingHbar(1).between(alice, bob))
                                        .batchKey(batchOperatorOne)
                                        .payingWith(alice),
                                cryptoTransfer(movingHbar(2).between(bob, alice))
                                        .batchKey(batchOperatorTwo)
                                        .payingWith(bob))
                        .payingWith(batchOperatorOne)
                        .signedBy(batchOperatorOne, batchOperatorThree)
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    @DisplayName("Test inner tx duplicates within and cross batch tx, should fail")
    Stream<DynamicTest> dedupOnCrossBatch() {
        final var alice = "alice";
        final var bob = "bob";
        final var batchOperator = "batchOperator";

        return hapiTest(
                cryptoCreate(alice).balance(ONE_HBAR),
                cryptoCreate(bob).balance(ONE_HBAR),
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),

                // Create and register TransactionID
                doingContextual(spec -> {
                    final var payerId = TxnUtils.asId(alice, spec);
                    final var validStart =
                            TxnUtils.getUniqueTimestampPlusSecs(spec.setup().txnStartOffsetSecs());
                    final var txnId = TransactionID.newBuilder()
                            .setAccountID(payerId)
                            .setTransactionValidStart(validStart)
                            .setNonce(0) // Set non-zero nonce - this should be rejected per HIP-551
                            .build();
                    spec.registry().saveTxnId("innerTxId", txnId);
                }),

                // Test Dedup within a batch tx
                atomicBatch(
                                cryptoTransfer(movingHbar(1).between(alice, bob))
                                        .batchKey(batchOperator)
                                        .txnId("innerTxId")
                                        .payingWith(alice),
                                cryptoTransfer(movingHbar(2).between(bob, alice))
                                        .batchKey(batchOperator)
                                        .txnId("innerTxId")
                                        .payingWith(bob))
                        .payingWith(batchOperator)
                        .signedBy(batchOperator)
                        .hasPrecheck(BATCH_LIST_CONTAINS_DUPLICATES),

                // Test Dedup of cross-batch tx
                atomicBatch(cryptoTransfer(movingHbar(1).between(alice, bob))
                                .batchKey(batchOperator)
                                .txnId("innerTxId")
                                .payingWith(alice))
                        .payingWith(batchOperator)
                        .signedBy(batchOperator)
                        .hasKnownStatus(SUCCESS),
                atomicBatch(cryptoTransfer(movingHbar(2).between(bob, alice))
                                .batchKey(batchOperator)
                                .txnId("innerTxId")
                                .payingWith(bob))
                        .payingWith(batchOperator)
                        .signedBy(batchOperator)
                        .hasPrecheck(DUPLICATE_TRANSACTION));
    }

    @HapiTest
    @DisplayName("Submit inner tx as top-level tx, should fail")
    Stream<DynamicTest> innerTxAsTopLevelTx() {
        final var alice = "alice";
        final var bob = "bob";
        final var batchOperator = "batchOperator";

        return hapiTest(
                cryptoCreate(alice).balance(ONE_HBAR),
                cryptoCreate(bob).balance(ONE_HBAR),
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(movingHbar(1).between(alice, bob))
                        .batchKey(batchOperator)
                        .payingWith(alice)
                        .setNode("3")
                        .hasKnownStatus(BATCH_KEY_SET_ON_NON_INNER_TRANSACTION));
    }

    @HapiTest
    @DisplayName("BatchKey rotation inside batch: one inner tx with old batch key, should pass")
    Stream<DynamicTest> batchKeyRotationOneInnerTxWithOldKey() {
        final var oldKey = "oldKey";
        final var newKey = "newKey";
        final var batchOperator = "batchOperator";
        final var alice = "alice";
        final var bob = "bob";

        return hapiTest(
                newKeyNamed(oldKey),
                newKeyNamed(newKey),
                newKeyNamed("anotherKey"),
                cryptoCreate(batchOperator).key(oldKey).balance(ONE_HBAR),
                cryptoCreate(alice).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(bob).balance(ONE_HUNDRED_HBARS),

                // Rotate the batchOperator key: one inner tx w/ oldKey as batch key
                atomicBatch(cryptoUpdate(batchOperator)
                                .key(newKey)
                                .batchKey(oldKey)
                                .signedBy(oldKey, newKey)
                                .payingWith(batchOperator)
                                .hasKnownStatus(SUCCESS))
                        .payingWith(batchOperator)
                        .signedBy(batchOperator)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("BatchKey rotation inside batch: one inner tx with new batch key, should fail")
    Stream<DynamicTest> batchKeyRotationOneInnerTxWithNewKey() {
        final var oldKey = "oldKey";
        final var newKey = "newKey";
        final var batchOperator = "batchOperator";
        final var alice = "alice";
        final var bob = "bob";

        return hapiTest(
                newKeyNamed(oldKey),
                newKeyNamed(newKey),
                newKeyNamed("anotherKey"),
                cryptoCreate(batchOperator).key(oldKey).balance(ONE_HBAR),
                cryptoCreate(alice).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(bob).balance(ONE_HUNDRED_HBARS),

                // Rotate the batchOperator key: one inner tx w/ newKey as batch key
                atomicBatch(cryptoUpdate(batchOperator)
                                .key(newKey)
                                .batchKey(newKey)
                                .signedBy(oldKey, newKey)
                                .payingWith(batchOperator))
                        .payingWith(batchOperator)
                        .signedBy(batchOperator)
                        .hasPrecheck(INVALID_SIGNATURE));
    }

    @HapiTest
    @DisplayName("BatchKey rotation inside batch: followup tx signed w/ old key, should fail")
    Stream<DynamicTest> batchKeyRotationFollowUpTxOldKeySigned() {
        final var oldKey = "oldKey";
        final var newKey = "newKey";
        final var batchOperator = "batchOperator";
        final var alice = "alice";
        final var bob = "bob";

        return hapiTest(
                newKeyNamed(oldKey),
                newKeyNamed(newKey),
                newKeyNamed("anotherKey"),
                cryptoCreate(batchOperator).key(oldKey).balance(ONE_HBAR),
                cryptoCreate(alice).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(bob).balance(ONE_HUNDRED_HBARS),

                // Rotate key and follow up with tx signed w/ old key
                atomicBatch(
                                cryptoUpdate(batchOperator)
                                        .key(newKey)
                                        .batchKey(oldKey)
                                        .signedBy(oldKey, newKey)
                                        .payingWith(batchOperator),
                                cryptoTransfer(movingHbar(1).between(batchOperator, alice))
                                        .batchKey(oldKey)
                                        .signedBy(oldKey, bob)
                                        .payingWith(bob)
                                        .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith(batchOperator)
                        .signedBy(batchOperator)
                        .hasPrecheck(OK)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    @DisplayName("BatchKey rotation inside batch: followup tx signed w/ new key, should pass")
    Stream<DynamicTest> batchKeyRotationDoesNotAllowStaleSignatureTwoDotTwo() {
        final var oldKey = "oldKey";
        final var newKey = "newKey";
        final var batchOperator = "batchOperator";
        final var alice = "alice";
        final var bob = "bob";

        return hapiTest(
                newKeyNamed(oldKey),
                newKeyNamed(newKey),
                cryptoCreate(batchOperator).key(oldKey).balance(ONE_HBAR),
                cryptoCreate(alice).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(bob).balance(ONE_HUNDRED_HBARS),

                // Rotate key and follow up with tx signed w/ new key
                atomicBatch(
                                cryptoUpdate(batchOperator)
                                        .key(newKey)
                                        .batchKey(oldKey)
                                        .signedBy(oldKey, newKey)
                                        .payingWith(batchOperator),
                                cryptoTransfer(movingHbar(1).between(batchOperator, alice))
                                        .batchKey(oldKey)
                                        .signedBy(newKey, bob)
                                        .payingWith(bob)
                                        .hasKnownStatus(SUCCESS))
                        .payingWith(batchOperator)
                        .signedBy(batchOperator)
                        .hasPrecheck(OK)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("Mixing Asset Allowance does not OverSpend within batch, should fail")
    public final Stream<DynamicTest> mixedAssetAllowanceAggregationDoesNotOverSpend() {
        final var ft = "fungible_token";
        final var nft = "non_fungible_token";
        final var serial1 = 1L;
        final var serial2 = 2L;
        final var batchOperator = "batchOperator";

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                tokenCreate(ft).tokenType(FUNGIBLE_COMMON).initialSupply(1_000L).treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                mintToken(nft, List.of(ByteString.copyFromUtf8("s1"))).via(NFT_TOKEN_MINT_TXN),
                mintToken(nft, List.of(ByteString.copyFromUtf8("s2"))),
                tokenAssociate(OWNER, ft, nft),
                tokenAssociate(SPENDER, ft, nft),
                tokenAssociate(RECEIVER, ft, nft),
                cryptoTransfer(
                                moving(200, ft).between(TOKEN_TREASURY, OWNER),
                                movingUnique(nft, serial1).between(TOKEN_TREASURY, OWNER),
                                movingUnique(nft, serial2).between(TOKEN_TREASURY, OWNER))
                        .payingWith(TOKEN_TREASURY)
                        .hasKnownStatus(SUCCESS),

                // Set allowances and spend them correctly within limits
                atomicBatch(
                                cryptoApproveAllowance()
                                        .batchKey(batchOperator)
                                        .payingWith(OWNER)
                                        .addTokenAllowance(OWNER, ft, SPENDER, 100L)
                                        .addNftAllowance(OWNER, nft, SPENDER, false, List.of(serial1, serial2)),
                                // Spend part of FT allowance (100 out of 150)
                                cryptoTransfer(moving(150, ft).between(OWNER, RECEIVER))
                                        .batchKey(batchOperator)
                                        .payingWith(OWNER)
                                        .signedBy(OWNER)
                                        .via("ftSpend1"),
                                // Spend one NFT serial via approval (serial1)
                                cryptoTransfer(TokenMovement.movingUniqueWithAllowance(nft, serial1)
                                                .between(OWNER, RECEIVER))
                                        .batchKey(batchOperator)
                                        .payingWith(SPENDER)
                                        .signedBy(SPENDER)
                                        .via("nftSpend1"),
                                // Attempt to overspend FT allowance by another 100 (remaining is 50)
                                cryptoTransfer(movingWithAllowance(100, ft).between(OWNER, RECEIVER))
                                        .payingWith(SPENDER)
                                        .signedBy(SPENDER)
                                        .via("ftOverSpend")
                                        .batchKey(batchOperator)
                                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE))
                        .payingWith(batchOperator)
                        .signedBy(batchOperator)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountBalance(RECEIVER).hasTokenBalance(ft, 0),
                getAccountBalance(RECEIVER).hasTokenBalance(nft, 0));
    }

    @HapiTest
    @DisplayName("Ensure Allowance after Account Key Update requires new key, should pass")
    public final Stream<DynamicTest> allowanceRequiresUpdatedSignature() {
        final var ft = "fungible_token";
        final var batchOperator = "batchOperator";
        final var newKey = "newKey";
        final var oldKey = "oldKey";

        return hapiTest(
                newKeyNamed(oldKey),
                newKeyNamed(newKey),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(batchOperator).key(oldKey).balance(ONE_HUNDRED_HBARS),
                tokenCreate(ft).tokenType(FUNGIBLE_COMMON).initialSupply(1_000L).treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, ft),
                tokenAssociate(SPENDER, ft),
                tokenAssociate(RECEIVER, ft),
                cryptoTransfer(moving(200, ft).between(TOKEN_TREASURY, OWNER))
                        .payingWith(TOKEN_TREASURY)
                        .hasKnownStatus(SUCCESS),
                atomicBatch(
                                cryptoApproveAllowance()
                                        .batchKey(batchOperator)
                                        .payingWith(OWNER)
                                        .addTokenAllowance(OWNER, ft, batchOperator, 100L),
                                cryptoUpdate(batchOperator)
                                        .key(newKey)
                                        .batchKey(oldKey)
                                        .signedBy(oldKey, newKey)
                                        .payingWith(batchOperator),

                                // Attempt to spend FT allowance with old key
                                cryptoTransfer(movingWithAllowance(50L, ft).between(OWNER, RECEIVER))
                                        .payingWith(batchOperator)
                                        .signedBy(newKey, oldKey) // Needs both keys due to different tx handling phases
                                        .batchKey(oldKey)
                                        .hasKnownStatus(SUCCESS))
                        .payingWith(batchOperator)
                        .signedBy(batchOperator)
                        .hasKnownStatus(SUCCESS));
    }
}
