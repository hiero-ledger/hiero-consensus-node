// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.including;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.nftTransfer;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.tokenTransferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.tokenTransferLists;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.transferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.wrapIntoTupleArray;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.assertions.NonFungibleTransfers;
import com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class AtomicCryptoTransferHTSSuite {
    private static final long GAS_FOR_AUTO_ASSOCIATING_CALLS = 2_000_000;
    private static final Tuple[] EMPTY_TUPLE_ARRAY = new Tuple[] {};
    private static final long GAS_TO_OFFER = 5_000_000L;
    private static final long TOTAL_SUPPLY = 1_000;
    private static final String NFT_TOKEN = "AToken_NFT";
    private static final String TOKEN_TREASURY = "the_treasury";
    private static final String FUNGIBLE_TOKEN = "AToken";
    private static final String RECEIVER = "the_receiver";
    private static final String RECEIVER2 = "the_receiver2";
    private static final String SENDER = "the_sender";
    private static final String SENDER2 = "the_sender2";
    private static final String DELEGATE_KEY = "contractKey";
    private static final String MULTI_KEY = "purpose";
    private static final String OWNER = "Owner";
    private static final KeyShape DELEGATE_CONTRACT_KEY_SHAPE =
            KeyShape.threshOf(1, KeyShape.SIMPLE, DELEGATE_CONTRACT);
    private static final String CONTRACT = "AtomicCryptoTransfer";
    private static final String TRANSFER_MULTIPLE_TOKENS = "transferMultipleTokens";
    private static final String TRANSFER_MULTIPLE_TOKENS_DELEGATE_CALL = "transferMultipleTokensDelegateCall";
    private static final String BASE_APPROVAL_TXN = "baseApproveTxn";

    public static final String SECP_256K1_SOURCE_KEY = "secp256k1Alias";

    @HapiTest
    final Stream<DynamicTest> cryptoTransferForHbarOnly() {
        final var cryptoTransferTxn = "cryptoTransferTxn";
        final var cryptoTransferMultiTxn = "cryptoTransferMultiTxn";
        final var cryptoTransferRevertTxn = "cryptoTransferRevertTxn";
        final var cryptoTransferRevertNoKeyTxn = "cryptoTransferRevertNoKeyTxn";
        final var cryptoTransferRevertBalanceTooLowTxn = "cryptoTransferRevertBalanceTooLowTxn";

        return hapiTest(
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(SENDER2).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(2 * ONE_HUNDRED_HBARS).receiverSigRequired(true),
                cryptoCreate(RECEIVER2).balance(2 * ONE_HUNDRED_HBARS).receiverSigRequired(true),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var sender2 = spec.registry().getAccountID(SENDER2);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var receiver2 = spec.registry().getAccountID(RECEIVER2);
                    final var amountToBeSent = 50 * ONE_HBAR;

                    /*
                     We will be covering the following test cases
                     1. Simple hbar transfer between 2 parties
                     2. When sender does not have the required key
                     3. When sender's balance is too low
                     4. Transfer among 3 parties
                     5. When transfer balances do not add to 0
                    */
                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER2).key(DELEGATE_KEY),
                            // Simple transfer between sender and receiver for 50 *
                            // ONE_HBAR
                            // should succeed
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(sender, -amountToBeSent, false),
                                                            accountAmount(receiver, amountToBeSent, false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxn)
                                    .gas(GAS_TO_OFFER),
                            // Simple transfer between sender2 and receiver for 50 *
                            // ONE_HBAR
                            // should fail because sender2 does not have the right
                            // key
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(sender2, -amountToBeSent, false),
                                                            accountAmount(receiver, amountToBeSent, false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferRevertNoKeyTxn)
                                    .gas(GAS_TO_OFFER)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // Simple transfer between sender2 and receiver for 1000
                            // * ONE_HUNDRED_HBAR
                            // should fail because sender does not have enough hbars
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(sender, -1000 * ONE_HUNDRED_HBARS, false),
                                                            accountAmount(receiver, 1000 * ONE_HUNDRED_HBARS, false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferRevertBalanceTooLowTxn)
                                    .gas(GAS_TO_OFFER)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // Simple transfer between sender, receiver and
                            // receiver2 for 50 * ONE_HBAR
                            // sender sends 50, receiver get 10 and receiver2 gets
                            // 40
                            // should succeed
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(sender, -amountToBeSent, false),
                                                            accountAmount(
                                                                    receiver, amountToBeSent - (10 * ONE_HBAR), false),
                                                            accountAmount(
                                                                    receiver2, amountToBeSent - (40 * ONE_HBAR), false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferMultiTxn)
                                    .gas(GAS_TO_OFFER),
                            // Simple transfer between sender, receiver and
                            // receiver2 for 50 * ONE_HBAR
                            // sender sends 50, receiver get 5 and receiver2 gets 40
                            // should fail because total does not add to 0
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(sender, -amountToBeSent, false),
                                                            accountAmount(
                                                                    receiver, amountToBeSent - (5 * ONE_HBAR), false),
                                                            accountAmount(
                                                                    receiver2, amountToBeSent - (40 * ONE_HBAR), false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferRevertTxn)
                                    .gas(GAS_TO_OFFER)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
                }),
                getTxnRecord(cryptoTransferTxn).andAllChildRecords().logged(),
                getAccountBalance(SENDER).hasTinyBars(900 * ONE_HBAR),
                getAccountBalance(RECEIVER).hasTinyBars(290 * ONE_HBAR),
                getAccountBalance(RECEIVER2).hasTinyBars(210 * ONE_HBAR),
                childRecordsCheck(
                        cryptoTransferTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .transfers(including(tinyBarsFromTo(SENDER, RECEIVER, 50 * ONE_HBAR)))),
                childRecordsCheck(
                        cryptoTransferRevertNoKeyTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE)))),
                childRecordsCheck(
                        cryptoTransferRevertBalanceTooLowTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INSUFFICIENT_ACCOUNT_BALANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(INSUFFICIENT_ACCOUNT_BALANCE)))),
                childRecordsCheck(
                        cryptoTransferMultiTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                childRecordsCheck(
                        cryptoTransferRevertTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_ACCOUNT_AMOUNTS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(INVALID_ACCOUNT_AMOUNTS)))));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferForFungibleTokenOnly() {
        final var cryptoTransferTxnForFungible = "cryptoTransferTxnForFungible";
        final var cryptoTransferRevertNoKeyTxn = "cryptoTransferRevertNoKeyTxn";

        return hapiTest(
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(SENDER2).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(2 * ONE_HUNDRED_HBARS).receiverSigRequired(true),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(SENDER, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(SENDER2, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(RECEIVER, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(200, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SENDER)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).maxAutomaticTokenAssociations(1),
                getContractInfo(CONTRACT)
                        .has(ContractInfoAsserts.contractWith().maxAutoAssociations(1))
                        .logged(),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var sender2 = spec.registry().getAccountID(SENDER2);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var amountToBeSent = 50L;

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withAccountAmounts(
                                                            accountAmount(sender, -amountToBeSent, false),
                                                            accountAmount(receiver, amountToBeSent, false))
                                                    .build()))
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxnForFungible)
                                    .gas(GAS_TO_OFFER),
                            // Ensure that the transfer fails when the sender does not have the correct key
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withAccountAmounts(
                                                            accountAmount(sender2, -amountToBeSent, false),
                                                            accountAmount(receiver, amountToBeSent, false))
                                                    .build()))
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferRevertNoKeyTxn)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(cryptoTransferTxnForFungible).andAllChildRecords(),
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(TOTAL_SUPPLY),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 50),
                getAccountBalance(SENDER).hasTokenBalance(FUNGIBLE_TOKEN, 150),
                getTokenInfo(FUNGIBLE_TOKEN).logged(),
                childRecordsCheck(
                        cryptoTransferTxnForFungible,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FUNGIBLE_TOKEN, SENDER, -50)
                                        .including(FUNGIBLE_TOKEN, RECEIVER, 50))),
                childRecordsCheck(
                        cryptoTransferRevertNoKeyTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE)))));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferForFungibleTokenWithFees() {
        final var cryptoTransferTxnForFungible = "cryptoTransferTxnForFungible";
        final var FEE_TOKEN = "FeeToken";

        return hapiTest(
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(2 * ONE_HUNDRED_HBARS).receiverSigRequired(true),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FEE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY)
                        .withCustom(fractionalFee(1, 10L, 1L, OptionalLong.empty(), TOKEN_TREASURY))
                        .withCustom(fixedHtsFee(1L, FEE_TOKEN, TOKEN_TREASURY)),
                tokenAssociate(SENDER, List.of(FUNGIBLE_TOKEN, FEE_TOKEN)),
                tokenAssociate(RECEIVER, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(10, FEE_TOKEN).between(TOKEN_TREASURY, SENDER)),
                cryptoTransfer(moving(200, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SENDER)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).maxAutomaticTokenAssociations(1),
                getContractInfo(CONTRACT).has(ContractInfoAsserts.contractWith().maxAutoAssociations(1)),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var amountToBeSent = 50L;

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withAccountAmounts(
                                                            accountAmount(sender, -amountToBeSent, false),
                                                            accountAmount(receiver, amountToBeSent, false))
                                                    .build()))
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxnForFungible)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(cryptoTransferTxnForFungible).andAllChildRecords(),
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(TOTAL_SUPPLY),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 45),
                getAccountBalance(SENDER).hasTokenBalance(FUNGIBLE_TOKEN, 150),
                getTokenInfo(FUNGIBLE_TOKEN).logged(),
                childRecordsCheck(
                        cryptoTransferTxnForFungible,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FUNGIBLE_TOKEN, SENDER, -50)
                                        .including(FUNGIBLE_TOKEN, RECEIVER, 45)
                                        .including(FUNGIBLE_TOKEN, TOKEN_TREASURY, 5))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FEE_TOKEN, SENDER, -1)
                                        .including(FEE_TOKEN, TOKEN_TREASURY, 1))
                                .assessedCustomFeeCount(2)));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> cryptoTransferForNFTWithFees() {
        final var cryptoTransferTxnForNonFungible = "cryptoTransferTxnForNonFungible";
        final var FEE_TOKEN = "FeeToken";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(2 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FEE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .withCustom(royaltyFeeWithFallback(
                                1L, 10L, fixedHtsFeeInheritingRoyaltyCollector(1, FEE_TOKEN), TOKEN_TREASURY)),
                mintToken(NFT_TOKEN, List.of(metadata("firstMemo"), metadata("secondMemo"))),
                tokenAssociate(SENDER, List.of(NFT_TOKEN, FEE_TOKEN)),
                tokenAssociate(RECEIVER, List.of(NFT_TOKEN, FEE_TOKEN)),
                cryptoTransfer(moving(10, FEE_TOKEN).between(TOKEN_TREASURY, RECEIVER)),
                cryptoTransfer(TokenMovement.movingUnique(NFT_TOKEN, 1).between(TOKEN_TREASURY, SENDER))
                        .payingWith(SENDER),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).maxAutomaticTokenAssociations(1),
                getContractInfo(CONTRACT)
                        .has(ContractInfoAsserts.contractWith().maxAutoAssociations(1))
                        .logged(),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(NFT_TOKEN);
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var amountToBeSent = 50L;

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withNftTransfers(nftTransfer(sender, receiver, 1L, false))
                                                    .build()))
                                    .payingWith(SENDER)
                                    .via(cryptoTransferTxnForNonFungible)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(cryptoTransferTxnForNonFungible)
                        .andAllChildRecords()
                        .logged(),
                getTokenInfo(NFT_TOKEN).hasTotalSupply(2),
                getAccountInfo(RECEIVER).hasOwnedNfts(1),
                getAccountBalance(RECEIVER).hasTokenBalance(NFT_TOKEN, 1),
                getAccountInfo(SENDER).hasOwnedNfts(0),
                getAccountBalance(SENDER).hasTokenBalance(NFT_TOKEN, 0),
                getTokenInfo(NFT_TOKEN).logged(),
                childRecordsCheck(
                        cryptoTransferTxnForNonFungible,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(NonFungibleTransfers.changingNFTBalances()
                                        .including(NFT_TOKEN, SENDER, RECEIVER, 1L))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FEE_TOKEN, RECEIVER, -1)
                                        .including(FEE_TOKEN, TOKEN_TREASURY, 1))
                                .assessedCustomFeeCount(1)));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferForNonFungibleTokenOnly() {
        final var cryptoTransferTxnForNft = "cryptoTransferTxnForNft";
        final var cryptoTransferRevertNoKeyTxn = "cryptoTransferRevertNoKeyTxn";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(SENDER2).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).receiverSigRequired(true),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(SENDER, List.of(NFT_TOKEN)),
                tokenAssociate(SENDER2, List.of(NFT_TOKEN)),
                mintToken(NFT_TOKEN, List.of(metadata("firstMemo"), metadata("secondMemo"))),
                tokenAssociate(RECEIVER, List.of(NFT_TOKEN)),
                cryptoTransfer(TokenMovement.movingUnique(NFT_TOKEN, 1).between(TOKEN_TREASURY, SENDER))
                        .payingWith(SENDER),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(NFT_TOKEN);
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var sender2 = spec.registry().getAccountID(SENDER2);
                    final var receiver = spec.registry().getAccountID(RECEIVER);

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withNftTransfers(nftTransfer(sender, receiver, 1L, false))
                                                    .build()))
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxnForNft)
                                    .gas(GAS_TO_OFFER),
                            // Ensure that the transfer fails when the sender does not have the correct key
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withNftTransfers(nftTransfer(sender2, receiver, 1L, false))
                                                    .build()))
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferRevertNoKeyTxn)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(cryptoTransferTxnForNft).andAllChildRecords().logged(),
                getTokenInfo(NFT_TOKEN).hasTotalSupply(2),
                getAccountInfo(RECEIVER).hasOwnedNfts(1),
                getAccountBalance(RECEIVER).hasTokenBalance(NFT_TOKEN, 1),
                getAccountInfo(SENDER).hasOwnedNfts(0),
                getAccountBalance(SENDER).hasTokenBalance(NFT_TOKEN, 0),
                getTokenInfo(NFT_TOKEN).logged(),
                childRecordsCheck(
                        cryptoTransferTxnForNft,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(NonFungibleTransfers.changingNFTBalances()
                                        .including(NFT_TOKEN, SENDER, RECEIVER, 1L))),
                childRecordsCheck(
                        cryptoTransferRevertNoKeyTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE)))));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferHBarFungibleNft() {
        final var cryptoTransferTxnForAll = "cryptoTransferTxnForAll";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(2 * ONE_HUNDRED_HBARS).receiverSigRequired(true),
                cryptoCreate(SENDER2).balance(5 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER2).receiverSigRequired(true),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(SENDER, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(SENDER2, List.of(NFT_TOKEN)),
                mintToken(NFT_TOKEN, List.of(metadata("firstMemo"), metadata("secondMemo"))),
                tokenAssociate(RECEIVER, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(RECEIVER2, List.of(NFT_TOKEN)),
                cryptoTransfer(moving(200, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SENDER))
                        .payingWith(GENESIS),
                cryptoTransfer(TokenMovement.movingUnique(NFT_TOKEN, 1).between(TOKEN_TREASURY, SENDER2))
                        .payingWith(SENDER2),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var fungibleToken = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                    final var nonFungibleToken = spec.registry().getTokenID(NFT_TOKEN);
                    final var fungibleTokenSender = spec.registry().getAccountID(SENDER);
                    final var fungibleTokenReceiver = spec.registry().getAccountID(RECEIVER);
                    final var nonFungibleTokenSender = spec.registry().getAccountID(SENDER2);
                    final var nonFungibleTokenReceiver = spec.registry().getAccountID(RECEIVER2);
                    final var amountToBeSent = 50 * ONE_HBAR;

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(SENDER2).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER2).key(DELEGATE_KEY),
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(fungibleTokenSender, -amountToBeSent, false),
                                                            accountAmount(fungibleTokenReceiver, amountToBeSent, false))
                                                    .build(),
                                            tokenTransferLists()
                                                    .withTokenTransferList(
                                                            tokenTransferList()
                                                                    .forToken(fungibleToken)
                                                                    .withAccountAmounts(
                                                                            accountAmount(
                                                                                    fungibleTokenSender, -45L, false),
                                                                            accountAmount(
                                                                                    fungibleTokenReceiver, 45L, false))
                                                                    .build(),
                                                            tokenTransferList()
                                                                    .forToken(nonFungibleToken)
                                                                    .withNftTransfers(
                                                                            nftTransfer(
                                                                                    nonFungibleTokenSender,
                                                                                    nonFungibleTokenReceiver,
                                                                                    1L,
                                                                                    false))
                                                                    .build())
                                                    .build())
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxnForAll)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(cryptoTransferTxnForAll).andAllChildRecords().logged(),
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(TOTAL_SUPPLY),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 45),
                getAccountBalance(SENDER).hasTokenBalance(FUNGIBLE_TOKEN, 155),
                getTokenInfo(FUNGIBLE_TOKEN).logged(),
                getTokenInfo(NFT_TOKEN).hasTotalSupply(2),
                getAccountInfo(RECEIVER2).hasOwnedNfts(1),
                getAccountBalance(RECEIVER2).hasTokenBalance(NFT_TOKEN, 1),
                getAccountInfo(SENDER2).hasOwnedNfts(0),
                getAccountBalance(SENDER2).hasTokenBalance(NFT_TOKEN, 0),
                getTokenInfo(NFT_TOKEN).logged(),
                getAccountBalance(SENDER).hasTinyBars(950 * ONE_HBAR),
                getAccountBalance(RECEIVER).hasTinyBars(250 * ONE_HBAR),
                childRecordsCheck(
                        cryptoTransferTxnForAll,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FUNGIBLE_TOKEN, SENDER, -45L)
                                        .including(FUNGIBLE_TOKEN, RECEIVER, 45L))
                                .tokenTransfers(NonFungibleTransfers.changingNFTBalances()
                                        .including(NFT_TOKEN, SENDER2, RECEIVER2, 1L))
                                .transfers(including(tinyBarsFromTo(SENDER, RECEIVER, 50 * ONE_HBAR)))));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferAllowanceToContractHbar() {
        final var allowance = 11L;
        final var successfulTransferFromTxn = "txn";
        final var successfulTransferFromTxn2 = "txn2";
        final var successfulTransferFromTxn3 = "txn3";
        final var revertingTransferFromTxn = "revertWhenMoreThanAllowance";
        final var revertingTransferFromTxn2 = "revertingTxn";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addCryptoAllowance(OWNER, CONTRACT, allowance)
                        .via(BASE_APPROVAL_TXN)
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .logged(),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().cryptoAllowancesContaining(CONTRACT, allowance))
                        .logged(),
                withOpContext((spec, opLog) -> {
                    final var owner = spec.registry().getAccountID(OWNER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);

                    /*
                     We will be covering the following test cases.  These test cover hbar transfers
                     1. Transfer more than allowance amount
                     2. Transfer when there is no approval - this now succeeds as the transaction is retried with the allowance
                     3. Transfer 1/2 the allowance amount
                     4. Transfer the other 1/2 of the allowance amount
                     5. Transfer after allowance is spent
                    */

                    allRunFor(
                            spec,
                            // Try to send 1 more than the allowance amount from
                            // owner to receiver
                            // should fail
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(owner, -(allowance + 1), true),
                                                            accountAmount(receiver, allowance + 1, true))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .via(revertingTransferFromTxn)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // Try to send allowance amount but turn off isApproval
                            // flag
                            // This used to fail but now succeeds as the transaction is
                            // automatically retried as with the allowance
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(owner, -1L, false),
                                                            accountAmount(receiver, 1L, false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .via(successfulTransferFromTxn)
                                    .hasKnownStatus(SUCCESS),
                            // Try to send 1/2 of the allowance amount from owner to
                            // receiver
                            // should succeed as isApproval is true.
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(owner, -(allowance - 1) / 2, true),
                                                            accountAmount(receiver, (allowance - 1) / 2, true))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .via(successfulTransferFromTxn2)
                                    .hasKnownStatus(SUCCESS),
                            // Try to send second 1/2 of the allowance amount from
                            // owner to receiver
                            // should succeed as isApproval is true.
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(owner, -(allowance - 1) / 2, true),
                                                            accountAmount(receiver, (allowance - 1) / 2, true))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .via(successfulTransferFromTxn3)
                                    .hasKnownStatus(SUCCESS),
                            getAccountDetails(OWNER)
                                    .payingWith(GENESIS)
                                    .has(accountDetailsWith().noAllowances()),
                            // Try to send 1 hbar from owner to receiver
                            // should fail as all allowance has been spent
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(owner, -1L, true),
                                                            accountAmount(receiver, 1L, true))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .via(revertingTransferFromTxn2)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
                }),
                childRecordsCheck(
                        revertingTransferFromTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(AMOUNT_EXCEEDS_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(AMOUNT_EXCEEDS_ALLOWANCE)))),
                childRecordsCheck(
                        successfulTransferFromTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .transfers(including(tinyBarsFromTo(OWNER, RECEIVER, 1)))),
                childRecordsCheck(
                        successfulTransferFromTxn2,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .transfers(including(tinyBarsFromTo(OWNER, RECEIVER, (allowance - 1) / 2)))),
                childRecordsCheck(
                        successfulTransferFromTxn3,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .transfers(including(tinyBarsFromTo(OWNER, RECEIVER, (allowance - 1) / 2)))),
                childRecordsCheck(
                        revertingTransferFromTxn2,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE)))));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferAllowanceToContractFT() {
        final var allowance = 11L;
        final var successfulTransferFromTxn = "txn";
        final var successfulTransferFromTxn2 = "txn2";
        final var successfulTransferFromTxn3 = "txn3";
        final var revertingTransferFromTxnFungible = "revertWhenMoreThanAllowanceFungible";
        final var revertingTransferFromTxn2 = "revertingTxn";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(1),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(allowance)
                        .maxSupply(1000L)
                        .supplyKey(MULTI_KEY)
                        .treasury(OWNER),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, CONTRACT, allowance)
                        .via(BASE_APPROVAL_TXN)
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().tokenAllowancesContaining(FUNGIBLE_TOKEN, CONTRACT, allowance)),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                    final var owner = spec.registry().getAccountID(OWNER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);

                    /*
                     We will be covering the following test cases.  These test cover fungible token
                     transfers
                     1. Transfer more than allowance amount
                     2. Transfer when there is no approval.  This now succeeds as the transaction is retried with allowance
                     3. Transfer 1/2 the allowance amount
                     4. Transfer the other 1/2 of the allowance amount
                     5. Transfer after allowance is spent
                    */
                    allRunFor(
                            spec,
                            // Try to send 1 more than the allowance amount from
                            // owner to receiver
                            // should fail
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withAccountAmounts(
                                                            accountAmount(owner, -(allowance + 1), true),
                                                            accountAmount(receiver, allowance + 1, true))
                                                    .build()))
                                    .via(revertingTransferFromTxnFungible)
                                    .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // Try to send allowance amount but turn off isApproval
                            // flag
                            // Used to fail as but code has been changed to automatically
                            // retry with a possible allowance
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withAccountAmounts(
                                                            accountAmount(owner, -1L, false),
                                                            accountAmount(receiver, 1L, false))
                                                    .build()))
                                    .via(successfulTransferFromTxn)
                                    .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                    .hasKnownStatus(SUCCESS),
                            // Try to send 1/2 of the allowance amount from owner to
                            // receiver
                            // should succeed as isApproval is true.
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withAccountAmounts(
                                                            accountAmount(owner, -(allowance - 1) / 2, true),
                                                            accountAmount(receiver, (allowance - 1) / 2, true))
                                                    .build()))
                                    .via(successfulTransferFromTxn2)
                                    .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                    .hasKnownStatus(SUCCESS),
                            // Try to send second 1/2 of the allowance amount from
                            // owner to receiver
                            // should succeed as isApproval is true.
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withAccountAmounts(
                                                            accountAmount(owner, -(allowance - 1) / 2, true),
                                                            accountAmount(receiver, (allowance - 1) / 2, true))
                                                    .build()))
                                    .via(successfulTransferFromTxn3)
                                    .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                    .hasKnownStatus(SUCCESS),
                            getAccountDetails(OWNER)
                                    .payingWith(GENESIS)
                                    .has(accountDetailsWith().noAllowances()),
                            // Try to send 1 token from owner to receiver
                            // should fail as all allowance has been spent
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withAccountAmounts(
                                                            accountAmount(owner, -1L, true),
                                                            accountAmount(receiver, 1L, true))
                                                    .build()))
                                    .via(revertingTransferFromTxn2)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
                }),
                childRecordsCheck(
                        revertingTransferFromTxnFungible,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(AMOUNT_EXCEEDS_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(AMOUNT_EXCEEDS_ALLOWANCE)))),
                childRecordsCheck(
                        successfulTransferFromTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FUNGIBLE_TOKEN, OWNER, -1L)
                                        .including(FUNGIBLE_TOKEN, RECEIVER, 1L))),
                childRecordsCheck(
                        successfulTransferFromTxn2,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FUNGIBLE_TOKEN, OWNER, -(allowance - 1) / 2)
                                        .including(FUNGIBLE_TOKEN, RECEIVER, (allowance - 1) / 2))),
                childRecordsCheck(
                        successfulTransferFromTxn3,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FUNGIBLE_TOKEN, OWNER, -(allowance - 1) / 2)
                                        .including(FUNGIBLE_TOKEN, RECEIVER, (allowance - 1) / 2))),
                childRecordsCheck(
                        revertingTransferFromTxn2,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE)))));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferAllowanceToContractNFT() {
        final var successfulTransferFromTxn = "txn";
        final var revertingTransferFromTxnNft = "revertWhenMoreThanAllowanceNft";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(5),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(5),
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .treasury(OWNER)
                        .initialSupply(0L)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                mintToken(
                        NFT_TOKEN,
                        List.of(
                                ByteStringUtils.wrapUnsafely("meta1".getBytes()),
                                ByteStringUtils.wrapUnsafely("meta2".getBytes()))),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NFT_TOKEN, CONTRACT, false, List.of(2L))
                        .via(BASE_APPROVAL_TXN)
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(NFT_TOKEN);
                    final var owner = spec.registry().getAccountID(OWNER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);

                    allRunFor(
                            spec,
                            // trying to transfer NFT that is not approved
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withNftTransfers(nftTransfer(owner, receiver, 1L, true))
                                                    .build()))
                                    .via(revertingTransferFromTxnNft)
                                    .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // transfer allowed NFT
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withNftTransfers(nftTransfer(owner, receiver, 2L, true))
                                                    .build()))
                                    .via(successfulTransferFromTxn)
                                    .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                    .hasKnownStatus(SUCCESS));
                }),
                childRecordsCheck(
                        revertingTransferFromTxnNft,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE)))),
                childRecordsCheck(
                        successfulTransferFromTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(NonFungibleTransfers.changingNFTBalances()
                                        .including(NFT_TOKEN, OWNER, RECEIVER, 2L))));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferAllowanceToContractFromContract() {
        final var successfulTransferFromTxn = "txn";
        final var simpleStorageContract = "SimpleStorage";
        final long allowance = 10L;

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(5),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(5),
                uploadInitCode(simpleStorageContract),
                contractCreate(simpleStorageContract),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(1000L)
                        .maxSupply(1000L)
                        .supplyKey(MULTI_KEY)
                        .treasury(OWNER),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                tokenAssociate(simpleStorageContract, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(100L, FUNGIBLE_TOKEN).between(OWNER, simpleStorageContract))
                        .payingWith(OWNER),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(simpleStorageContract, FUNGIBLE_TOKEN, CONTRACT, allowance)
                        .via(BASE_APPROVAL_TXN)
                        .signedBy(DEFAULT_PAYER, simpleStorageContract)
                        .fee(ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                    final var owner = spec.registry().getAccountID(simpleStorageContract);
                    final var receiver = spec.registry().getAccountID(RECEIVER);

                    allRunFor(
                            spec,
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withAccountAmounts(
                                                            accountAmount(owner, -allowance, true),
                                                            accountAmount(receiver, allowance, true))
                                                    .build()))
                                    .via(successfulTransferFromTxn)
                                    .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                    .hasKnownStatus(SUCCESS));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> receiverSigRequiredButNotProvided() {
        final var failedTransferFromTxn = "failed_txn";
        final long allowance = 20L;

        return hapiTest(
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(2 * ONE_HUNDRED_HBARS).receiverSigRequired(true),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var amountToBeSent = 50 * ONE_HBAR;

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(sender, -amountToBeSent, false),
                                                            accountAmount(receiver, amountToBeSent, false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .payingWith(GENESIS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .via(failedTransferFromTxn)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(failedTransferFromTxn).andAllChildRecords().logged(),
                childRecordsCheck(
                        failedTransferFromTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))));
    }

    @HapiTest
    final Stream<DynamicTest> delegateCallShouldFail() {
        final var failedTransferFromTxn = "failed_txn";

        return hapiTest(
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(2 * ONE_HUNDRED_HBARS),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var amountToBeSent = 50 * ONE_HBAR;

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS_DELEGATE_CALL,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(sender, -amountToBeSent, false),
                                                            accountAmount(receiver, amountToBeSent, false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .payingWith(GENESIS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .via(failedTransferFromTxn)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(failedTransferFromTxn).andAllChildRecords().logged(),
                childRecordsCheck(failedTransferFromTxn, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> cryptoTransferSpecialAccounts() {
        final var cryptoTransferTxn = "cryptoTransferTxn";
        return hapiTest(
                cryptoCreate(RECEIVER).balance(1 * ONE_HUNDRED_HBARS).receiverSigRequired(true),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).maxAutomaticTokenAssociations(1),
                getContractInfo(CONTRACT)
                        .has(ContractInfoAsserts.contractWith().maxAutoAssociations(1))
                        .logged(),
                withOpContext((spec, opLog) -> {
                    final var senderStaking = spec.setup().stakingRewardAccount();
                    final var senderReward = spec.setup().nodeRewardAccount();
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var amountToBeSent = 50 * ONE_HBAR;

                    allRunFor(
                            spec,
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(senderStaking, -amountToBeSent, false),
                                                            accountAmount(receiver, amountToBeSent, false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxn)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(senderReward, -amountToBeSent, false),
                                                            accountAmount(receiver, amountToBeSent, false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxn)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
                }),
                getTxnRecord(cryptoTransferTxn).andAllChildRecords().logged(),
                getAccountBalance(RECEIVER).hasTinyBars(1 * ONE_HUNDRED_HBARS),
                childRecordsCheck(
                        cryptoTransferTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                childRecordsCheck(
                        cryptoTransferTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))));
    }

    @LeakyHapiTest(overrides = {"contracts.permittedDelegateCallers"})
    final Stream<DynamicTest> blockCryptoTransferForPermittedDelegates() {
        final var blockCryptoTransferForPermittedDelegates = "blockCryptoTransferForPermittedDelegates";
        final AtomicLong whitelistedCalleeMirrorNum = new AtomicLong();
        final AtomicReference<String> whitelistedCalleeMirrorAddr = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(2 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(SENDER, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(RECEIVER, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(200, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SENDER)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(DEFAULT_PAYER).exposingContractIdTo(id -> {
                    whitelistedCalleeMirrorNum.set(id.getContractNum());
                    whitelistedCalleeMirrorAddr.set(asHexedSolidityAddress(id));
                }),
                sourcing(() -> overriding(
                        "contracts.permittedDelegateCallers", String.valueOf(whitelistedCalleeMirrorNum.get()))),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var amountToBeSent = 50L;

                    allRunFor(
                            spec,
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(EMPTY_TUPLE_ARRAY)
                                                    .build(),
                                            wrapIntoTupleArray(tokenTransferList()
                                                    .forToken(token)
                                                    .withAccountAmounts(
                                                            accountAmount(sender, -amountToBeSent, false),
                                                            accountAmount(receiver, amountToBeSent, false))
                                                    .build()))
                                    .payingWith(DEFAULT_PAYER)
                                    .via(blockCryptoTransferForPermittedDelegates)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(blockCryptoTransferForPermittedDelegates)
                        .andAllChildRecords()
                        .logged(),
                childRecordsCheck(
                        blockCryptoTransferForPermittedDelegates,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE)))));
    }

    @HapiTest
    final Stream<DynamicTest> nullContractAdminKeyTransfer() {
        final var nullAdminKeyXferTxn = "nullAdminKeyXferTxn";
        return hapiTest(
                cryptoCreate(RECEIVER),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).omitAdminKey(),
                cryptoTransfer(tinyBarsFromTo(GENESIS, CONTRACT, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> {
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var contract = spec.registry().getAccountID(CONTRACT);
                    final var amountToBeSent = 50 * ONE_HBAR;
                    allRunFor(
                            spec,
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            transferList()
                                                    .withAccountAmounts(
                                                            accountAmount(contract, -amountToBeSent, false),
                                                            accountAmount(receiver, amountToBeSent, false))
                                                    .build(),
                                            EMPTY_TUPLE_ARRAY)
                                    .payingWith(GENESIS)
                                    .via(nullAdminKeyXferTxn)
                                    .gas(GAS_TO_OFFER));
                }),
                childRecordsCheck(
                        nullAdminKeyXferTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .transfers(including(tinyBarsFromTo(CONTRACT, RECEIVER, 50 * ONE_HBAR)))));
    }
}
