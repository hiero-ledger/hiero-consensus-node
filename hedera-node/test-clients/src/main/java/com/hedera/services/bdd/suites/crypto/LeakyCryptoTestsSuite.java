// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.ContextRequirement.FEE_SCHEDULE_OVERRIDES;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createWellKnownFungibleToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createWellKnownNonFungibleToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wellKnownTokenEntities;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFeeNetOfTransfers;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.reduceFeeFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCreateSuite.EMPTY_CONSTRUCTOR_CONTRACT;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_MEMO;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.VALID_ALIAS;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.ANOTHER_SPENDER;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.FUNGIBLE_TOKEN;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.FUNGIBLE_TOKEN_MINT_TXN;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.NFT_TOKEN_MINT_TXN;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.NON_FUNGIBLE_TOKEN;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.OWNER;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.SECOND_SPENDER;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.SPENDER;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.THIRD_SPENDER;
import static com.hedera.services.bdd.suites.file.FileUpdateSuite.CIVILIAN;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.TRANSFER_TXN;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.UNIQUE;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CrsPublication;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HintsKeyPublication;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HintsPartialSignature;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HintsPreprocessingVote;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HistoryAssemblySignature;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HistoryProofKeyPublication;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HistoryProofVote;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HookDispatch;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.NodeStakeUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.StateSignatureTransaction;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_ALLOWANCES_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.hooks.legacy.HookDispatchTransactionBody;
import com.hedera.hapi.services.auxiliary.hints.legacy.CrsPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.hints.legacy.HintsKeyPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.hints.legacy.HintsPartialSignatureTransactionBody;
import com.hedera.hapi.services.auxiliary.hints.legacy.HintsPreprocessingVoteTransactionBody;
import com.hedera.hapi.services.auxiliary.history.legacy.HistoryProofKeyPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.history.legacy.HistoryProofSignatureTransactionBody;
import com.hedera.hapi.services.auxiliary.history.legacy.HistoryProofVoteTransactionBody;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.NodeStakeUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class LeakyCryptoTestsSuite {
    private static final Logger log = LogManager.getLogger(LeakyCryptoTestsSuite.class);

    // Constants referenced by other suites/providers
    public static final String AUTO_ACCOUNT = "autoAccount";
    public static final String PAY_TXN = "payTxn";

    // (isolated/leaky tests moved to crypto.IsolatedSuite)

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> getsInsufficientPayerBalanceIfSendingAccountCanPayEverythingButServiceFee() {
        final var civilian = "civilian";
        final var creation = "creation";
        final var gasToOffer = 128_000L;
        final var civilianStartBalance = ONE_HUNDRED_HBARS;
        final AtomicLong gasFee = new AtomicLong();
        final AtomicLong offeredGasFee = new AtomicLong();
        final AtomicLong nodeAndNetworkFee = new AtomicLong();
        final AtomicLong maxSendable = new AtomicLong();

        return hapiTest(
                cryptoCreate(civilian).balance(civilianStartBalance),
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .gas(gasToOffer)
                        .payingWith(civilian)
                        .balance(0L)
                        .via(creation),
                withOpContext((spec, opLog) -> {
                    final var lookup = getTxnRecord(creation).logged();
                    allRunFor(spec, lookup);
                    final var creationRecord = lookup.getResponseRecord();
                    final var gasUsed = creationRecord.getContractCreateResult().getGasUsed();
                    gasFee.set(tinybarCostOfGas(spec, ContractCreate, gasUsed));
                    offeredGasFee.set(tinybarCostOfGas(spec, ContractCreate, gasToOffer));
                    nodeAndNetworkFee.set(creationRecord.getTransactionFee() - gasFee.get());
                    log.info(
                            "Network + node fees were {}, gas fee was {} (sum to" + " {}, compare with {})",
                            nodeAndNetworkFee::get,
                            gasFee::get,
                            () -> nodeAndNetworkFee.get() + gasFee.get(),
                            creationRecord::getTransactionFee);
                    maxSendable.set(
                            civilianStartBalance - 2 * nodeAndNetworkFee.get() - gasFee.get() - offeredGasFee.get());
                    log.info("Maximum amount send-able in precheck should be {}", maxSendable::get);
                }),
                sourcing(() -> getAccountBalance(civilian)
                        .hasTinyBars(civilianStartBalance - nodeAndNetworkFee.get() - gasFee.get())),
                // Fire-and-forget a txn that will leave the civilian payer with 1 too few
                // tinybars at consensus
                cryptoTransfer(tinyBarsFromTo(civilian, FUNDING, 1)).payingWith(GENESIS),
                sourcing(() -> contractCustomCreate(EMPTY_CONSTRUCTOR_CONTRACT, "Clone")
                        .gas(gasToOffer)
                        .payingWith(civilian)
                        .setNode(4)
                        .balance(maxSendable.get())
                        .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE)));
    }

    // (isolated/leaky tests moved to crypto.IsolatedSuite)

    /**
     * Characterize the current behavior of the network when submitting internal txs via a normal payer account.
     * <p>
     * (FUTURE) Revisit this with superuser payer.
     */
    @Tag(MATS)
    @HapiTest
    final Stream<DynamicTest> internalTxsCannotBeSubmittedByUserAccounts() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER),
                explicit(
                                StateSignatureTransaction,
                                (spec, b) ->
                                        b.setStateSignatureTransaction(com.hedera.hapi.platform.event.legacy
                                                .StateSignatureTransaction.getDefaultInstance()))
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY),
                explicit(
                                HintsPreprocessingVote,
                                (spec, b) -> b.setHintsPreprocessingVote(
                                        HintsPreprocessingVoteTransactionBody.getDefaultInstance()))
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY),
                explicit(
                                HintsKeyPublication,
                                (spec, b) -> b.setHintsKeyPublication(
                                        HintsKeyPublicationTransactionBody.getDefaultInstance()))
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY),
                explicit(
                                HintsPartialSignature,
                                (spec, b) -> b.setHintsPartialSignature(
                                        HintsPartialSignatureTransactionBody.getDefaultInstance()))
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY),
                explicit(
                                HistoryProofKeyPublication,
                                (spec, b) -> b.setHistoryProofKeyPublication(
                                        HistoryProofKeyPublicationTransactionBody.getDefaultInstance()))
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY),
                explicit(
                                HistoryAssemblySignature,
                                (spec, b) -> b.setHistoryProofSignature(
                                        HistoryProofSignatureTransactionBody.getDefaultInstance()))
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY),
                explicit(
                                HistoryProofVote,
                                (spec, b) ->
                                        b.setHistoryProofVote(HistoryProofVoteTransactionBody.getDefaultInstance()))
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY),
                explicit(
                                CrsPublication,
                                (spec, b) -> b.setCrsPublication(CrsPublicationTransactionBody.getDefaultInstance()))
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY),
                explicit(HookDispatch, (spec, b) -> b.setHookDispatch(HookDispatchTransactionBody.getDefaultInstance()))
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY),
                explicit(
                                NodeStakeUpdate,
                                (spec, b) -> b.setNodeStakeUpdate(NodeStakeUpdateTransactionBody.getDefaultInstance()))
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY));
    }

    // (isolated/leaky tests moved to crypto.IsolatedSuite)

    @HapiTest
    @Order(17)
    @Tag(MATS)
    final Stream<DynamicTest> autoAssociationWorksForContracts() {
        final var theContract = "CreateDonor";
        final String tokenA = "tokenA";
        final String tokenB = "tokenB";
        final String uniqueToken = UNIQUE;
        final String tokenAcreateTxn = "tokenACreate";
        final String tokenBcreateTxn = "tokenBCreate";
        final String transferToFU = "transferToFU";

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                uploadInitCode(theContract),
                contractCreate(theContract).maxAutomaticTokenAssociations(2),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(tokenA)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(TOKEN_TREASURY)
                        .via(tokenAcreateTxn),
                tokenCreate(tokenB)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(TOKEN_TREASURY)
                        .via(tokenBcreateTxn),
                tokenCreate(uniqueToken)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                mintToken(uniqueToken, List.of(copyFromUtf8("ONE"), copyFromUtf8("TWO"))),
                getTxnRecord(tokenAcreateTxn)
                        .hasNewTokenAssociation(tokenA, TOKEN_TREASURY)
                        .logged(),
                getTxnRecord(tokenBcreateTxn)
                        .hasNewTokenAssociation(tokenB, TOKEN_TREASURY)
                        .logged(),
                cryptoTransfer(moving(1, tokenA).between(TOKEN_TREASURY, theContract))
                        .via(transferToFU)
                        .logged(),
                getTxnRecord(transferToFU)
                        .hasNewTokenAssociation(tokenA, theContract)
                        .logged(),
                getContractInfo(theContract)
                        .has(ContractInfoAsserts.contractWith()
                                .hasAlreadyUsedAutomaticAssociations(1)
                                .maxAutoAssociations(2)),
                cryptoTransfer(movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, theContract)),
                getContractInfo(theContract)
                        .has(ContractInfoAsserts.contractWith()
                                .hasAlreadyUsedAutomaticAssociations(2)
                                .maxAutoAssociations(2)),
                cryptoTransfer(moving(1, tokenB).between(TOKEN_TREASURY, theContract))
                        .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS)
                        .via("failedTransfer"),
                getContractInfo(theContract)
                        .has(ContractInfoAsserts.contractWith()
                                .hasAlreadyUsedAutomaticAssociations(2)
                                .maxAutoAssociations(2)));
    }

    @HapiTest
    @Order(18)
    final Stream<DynamicTest> customFeesHaveExpectedAutoCreateInteractions() {
        final var nftWithRoyaltyNoFallback = "nftWithRoyaltyNoFallback";
        final var nftWithRoyaltyPlusHtsFallback = "nftWithRoyaltyPlusFallback";
        final var nftWithRoyaltyPlusHbarFallback = "nftWithRoyaltyPlusHbarFallback";
        final var ftWithNetOfTransfersFractional = "ftWithNetOfTransfersFractional";
        final var ftWithNonNetOfTransfersFractional = "ftWithNonNetOfTransfersFractional";
        final var finalReceiverKey = "finalReceiverKey";
        final var otherCollector = "otherCollector";
        final var finalTxn = "finalTxn";

        return hapiTest(
                wellKnownTokenEntities(),
                cryptoCreate(otherCollector),
                cryptoCreate(CIVILIAN).maxAutomaticTokenAssociations(42),
                inParallel(
                        createWellKnownFungibleToken(
                                ftWithNetOfTransfersFractional,
                                creation -> creation.withCustom(fractionalFeeNetOfTransfers(
                                        1L, 100L, 1L, OptionalLong.of(5L), TOKEN_TREASURY))),
                        createWellKnownFungibleToken(
                                ftWithNonNetOfTransfersFractional,
                                creation -> creation.withCustom(
                                        fractionalFee(1L, 100L, 1L, OptionalLong.of(5L), TOKEN_TREASURY))),
                        createWellKnownNonFungibleToken(
                                nftWithRoyaltyNoFallback,
                                1,
                                creation -> creation.withCustom(royaltyFeeNoFallback(1L, 100L, TOKEN_TREASURY))),
                        createWellKnownNonFungibleToken(
                                nftWithRoyaltyPlusHbarFallback,
                                1,
                                creation -> creation.withCustom(royaltyFeeWithFallback(
                                        1L, 100L, fixedHbarFeeInheritingRoyaltyCollector(ONE_HBAR), TOKEN_TREASURY)))),
                tokenAssociate(otherCollector, ftWithNonNetOfTransfersFractional),
                createWellKnownNonFungibleToken(
                        nftWithRoyaltyPlusHtsFallback,
                        1,
                        creation -> creation.withCustom(royaltyFeeWithFallback(
                                1L,
                                100L,
                                fixedHtsFeeInheritingRoyaltyCollector(666, ftWithNonNetOfTransfersFractional),
                                otherCollector))),
                autoCreateWithFungible(ftWithNetOfTransfersFractional),
                autoCreateWithFungible(ftWithNonNetOfTransfersFractional),
                autoCreateWithNonFungible(nftWithRoyaltyNoFallback, SUCCESS),
                autoCreateWithNonFungible(
                        nftWithRoyaltyPlusHbarFallback, INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                newKeyNamed(finalReceiverKey),
                cryptoTransfer(
                        moving(100_000, ftWithNonNetOfTransfersFractional).between(TOKEN_TREASURY, CIVILIAN),
                        movingUnique(nftWithRoyaltyPlusHtsFallback, 1L).between(TOKEN_TREASURY, CIVILIAN)),
                cryptoTransfer(
                                moving(10_000, ftWithNonNetOfTransfersFractional)
                                        .between(CIVILIAN, finalReceiverKey),
                                movingUnique(nftWithRoyaltyPlusHtsFallback, 1L).between(CIVILIAN, finalReceiverKey))
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE)
                        .via(finalTxn));
    }

    private long tinybarCostOfGas(final HapiSpec spec, final HederaFunctionality function, final long gasAmount) {
        final var gasThousandthsOfTinycentPrice = spec.fees()
                .getCurrentOpFeeData()
                .get(function)
                .get(DEFAULT)
                .getServicedata()
                .getGas();
        final var rates = spec.ratesProvider().rates();
        return (gasThousandthsOfTinycentPrice / 1000 * rates.getHbarEquiv()) / rates.getCentEquiv() * gasAmount;
    }

    private HapiSpecOperation autoCreateWithFungible(final String token) {
        final var keyName = VALID_ALIAS + "-" + token;
        final var txn = "autoCreationVia" + token;
        return blockingOrder(
                newKeyNamed(keyName),
                cryptoTransfer(moving(100_000, token).between(TOKEN_TREASURY, CIVILIAN)),
                cryptoTransfer(moving(10_000, token).between(CIVILIAN, keyName)).via(txn),
                getTxnRecord(txn).assertingKnownEffectivePayers());
    }

    private HapiSpecOperation autoCreateWithNonFungible(final String token, final ResponseCodeEnum expectedStatus) {
        final var keyName = VALID_ALIAS + "-" + token;
        final var txn = "autoCreationVia" + token;
        return blockingOrder(
                newKeyNamed(keyName),
                cryptoTransfer(movingUnique(token, 1L).between(TOKEN_TREASURY, CIVILIAN)),
                cryptoTransfer(movingUnique(token, 1L).between(CIVILIAN, keyName))
                        .via(txn)
                        .hasKnownStatus(expectedStatus),
                getTxnRecord(txn).assertingKnownEffectivePayers());
    }
}
