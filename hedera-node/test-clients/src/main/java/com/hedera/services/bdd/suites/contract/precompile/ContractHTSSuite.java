// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.RECEIVER_2;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.TOKEN_TRANSFER_CONTRACT;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.TRANSFER_TOKEN_PUBLIC;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.KNOWABLE_TOKEN;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractHTSSuite {
    public static final String VERSATILE_TRANSFERS_CONTRACT = "VersatileTransfers";
    public static final String TOKEN_TRANSFERS_CONTRACT = "TokenTransferContract";
    public static final String FEE_DISTRIBUTOR_CONTRACT = "FeeDistributor";

    public static final String TRANSFER_TOKEN = "transferTokenPublic";
    public static final String TRANSFER_TOKENS = "transferTokensPublic";
    public static final String TRANSFER_NFT = "transferNFTPublic";
    public static final String TRANSFER_NFTS = "transferNFTsPublic";

    private static final long GAS_TO_OFFER = 4_000_000L;
    private static final long TOTAL_SUPPLY = 1_000;
    private static final String TOKEN_TREASURY = "treasury";

    private static final String A_TOKEN = "TokenA";
    private static final String FUNGIBLE_TOKEN = "TokenFungible";
    private static final String NON_FUNGIBLE_TOKEN = "NON_FUNGIBLE_TOKEN";

    private static final String ACCOUNT = "sender";
    private static final String RECEIVER = "receiver";
    private static final String SECOND_RECEIVER = "second_receiver";

    private static final String UNIVERSAL_KEY = "multipurpose";

    @HapiTest
    final Stream<DynamicTest> transferDontWorkWithoutTopLevelSignatures() {
        final var transferTokenTxn = "transferTokenTxn";
        final var transferTokensTxn = "transferTokensTxn";
        final var transferNFTTxn = "transferNFTTxn";
        final var transferNFTsTxn = "transferNFTsTxn";
        final var contract = TOKEN_TRANSFER_CONTRACT;

        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaNftID = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(RECEIVER),
                cryptoCreate(RECEIVER_2),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                tokenCreate(KNOWABLE_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0)
                        .exposingCreatedIdTo(id -> vanillaNftID.set(asToken(id))),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN, KNOWABLE_TOKEN),
                tokenAssociate(RECEIVER, VANILLA_TOKEN, KNOWABLE_TOKEN),
                tokenAssociate(RECEIVER_2, VANILLA_TOKEN, KNOWABLE_TOKEN),
                mintToken(
                        KNOWABLE_TOKEN,
                        List.of(
                                copyFromUtf8("dark"),
                                copyFromUtf8("matter"),
                                copyFromUtf8("dark1"),
                                copyFromUtf8("matter1"))),
                cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                cryptoTransfer(movingUnique(KNOWABLE_TOKEN, 1, 2, 3, 4).between(TOKEN_TREASURY, ACCOUNT)),
                uploadInitCode(contract),
                contractCreate(contract).gas(GAS_TO_OFFER),
                // Do transfers by calling contract from EOA, and should be failing with
                // CONTRACT_REVERT_EXECUTED
                withOpContext((spec, opLog) -> {
                    final var receiver1 =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(RECEIVER)));
                    final var receiver2 =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(RECEIVER_2)));
                    final var sender =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(ACCOUNT)));
                    final var amount = 5L;

                    final var accounts = new Address[] {sender, receiver1, receiver2};
                    final var amounts = new long[] {-10L, 5L, 5L};
                    final var serials = new long[] {2L, 3L};
                    final var serial = 1L;
                    allRunFor(
                            spec,
                            contractCall(
                                            contract,
                                            TRANSFER_TOKEN_PUBLIC,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(VANILLA_TOKEN))),
                                            sender,
                                            receiver1,
                                            amount)
                                    .payingWith(ACCOUNT)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .gas(GAS_TO_OFFER)
                                    .via(transferTokenTxn),
                            contractCall(
                                            contract,
                                            "transferTokensPublic",
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(VANILLA_TOKEN))),
                                            accounts,
                                            amounts)
                                    .payingWith(ACCOUNT)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .gas(GAS_TO_OFFER)
                                    .via(transferTokensTxn),
                            contractCall(
                                            contract,
                                            "transferNFTPublic",
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(KNOWABLE_TOKEN))),
                                            sender,
                                            receiver1,
                                            serial)
                                    .payingWith(ACCOUNT)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .gas(GAS_TO_OFFER)
                                    .via(transferNFTTxn),
                            contractCall(
                                            contract,
                                            "transferNFTsPublic",
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(KNOWABLE_TOKEN))),
                                            new Address[] {sender, sender},
                                            new Address[] {receiver2, receiver2},
                                            serials)
                                    .payingWith(ACCOUNT)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .gas(GAS_TO_OFFER)
                                    .via(transferNFTsTxn));
                }),
                // Confirm the transactions fails with no top level signatures enabled
                childRecordsCheck(
                        transferTokenTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                childRecordsCheck(
                        transferTokensTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                childRecordsCheck(
                        transferNFTTxn, CONTRACT_REVERT_EXECUTED, recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                childRecordsCheck(
                        transferNFTsTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                // Confirm the balances are correct
                getAccountInfo(RECEIVER).hasOwnedNfts(0),
                getAccountBalance(RECEIVER).hasTokenBalance(VANILLA_TOKEN, 0),
                getAccountInfo(RECEIVER_2).hasOwnedNfts(0),
                getAccountBalance(RECEIVER_2).hasTokenBalance(VANILLA_TOKEN, 0),
                getAccountInfo(ACCOUNT).hasOwnedNfts(4),
                getAccountBalance(ACCOUNT).hasTokenBalance(VANILLA_TOKEN, 500L));
    }

    @HapiTest
    final Stream<DynamicTest> transferFailsWithIncorrectAmounts() {
        final var transferTokenWithNegativeAmountTxn = "transferTokenWithNegativeAmountTxn";
        final var contract = TOKEN_TRANSFER_CONTRACT;

        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(RECEIVER),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                tokenAssociate(RECEIVER, VANILLA_TOKEN),
                cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                uploadInitCode(contract),
                contractCreate(contract).gas(GAS_TO_OFFER),
                withOpContext((spec, opLog) -> {
                    final var receiver1 =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(RECEIVER)));
                    final var sender =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(ACCOUNT)));

                    allRunFor(
                            spec,
                            // Call tokenTransfer with a negative amount
                            contractCall(
                                            contract,
                                            TRANSFER_TOKEN_PUBLIC,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(VANILLA_TOKEN))),
                                            sender,
                                            receiver1,
                                            -1L)
                                    .payingWith(ACCOUNT)
                                    .gas(GAS_TO_OFFER)
                                    .via(transferTokenWithNegativeAmountTxn)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
                }),
                childRecordsCheck(transferTokenWithNegativeAmountTxn, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> nonZeroTransfersFail() {
        final var theSecondReceiver = "somebody2";
        return hapiTest(
                newKeyNamed(UNIVERSAL_KEY),
                cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                cryptoCreate(theSecondReceiver),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(A_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(VERSATILE_TRANSFERS_CONTRACT, FEE_DISTRIBUTOR_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(FEE_DISTRIBUTOR_CONTRACT).refusingEthConversion(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                        VERSATILE_TRANSFERS_CONTRACT,
                                        asHeadlongAddress(getNestedContractAddress(FEE_DISTRIBUTOR_CONTRACT, spec)))
                                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                                // tokenAssociate,
                                // since we have CONTRACT_ID key
                                .refusingEthConversion())),
                tokenAssociate(ACCOUNT, List.of(A_TOKEN)),
                tokenAssociate(VERSATILE_TRANSFERS_CONTRACT, List.of(A_TOKEN)),
                tokenAssociate(RECEIVER, List.of(A_TOKEN)),
                tokenAssociate(theSecondReceiver, List.of(A_TOKEN)),
                cryptoTransfer(moving(200, A_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> {
                    final var receiver1 = asAddress(spec.registry().getAccountID(RECEIVER));
                    final var receiver2 = asAddress(spec.registry().getAccountID(theSecondReceiver));

                    final var accounts = new Address[] {
                        HapiParserUtil.asHeadlongAddress(receiver1), HapiParserUtil.asHeadlongAddress(receiver2)
                    };
                    final var amounts = new long[] {5L, 5L};

                    allRunFor(
                            spec,
                            contractCall(
                                            VERSATILE_TRANSFERS_CONTRACT,
                                            "distributeTokens",
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(A_TOKEN))),
                                            accounts,
                                            amounts)
                                    .alsoSigningWithFullPrefix(ACCOUNT)
                                    .gas(GAS_TO_OFFER)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .via("distributeTx"));
                }),
                childRecordsCheck(
                        "distributeTx",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN)))));
    }

    @HapiTest
    final Stream<DynamicTest> shouldFailWhenTransferringTokensWithInvalidParametersAndConditions() {
        final var TXN_WITH_EMPTY_AMOUNTS_ARRAY = "TXN_WITH_EMPTY_AMOUNTS_ARRAY";
        final var TXN_WITH_EMPTY_ACCOUNTS_ARRAY = "TXN_WITH_EMPTY_ACCOUNTS_ARRAY";
        final var TXN_WITH_NOT_LENGTH_MATCHING_ACCOUNTS_AND_AMOUNTS =
                "TXN_WITH_NOT_LENGTH_MATCHING_ACCOUNTS_AND_AMOUNTS";
        final var TXN_WITH_NEGATIVE_AMOUNTS = "TXN_WITH_NEGATIVE_AMOUNTS";
        final var TXN_WITH_INVALID_TOKEN_ADDRESS = "TXN_WITH_INVALID_TOKEN_ADDRESS";
        final var TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE = "TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE";

        return hapiTest(
                newKeyNamed(UNIVERSAL_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                cryptoCreate(SECOND_RECEIVER),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .supplyKey(UNIVERSAL_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(TOKEN_TRANSFERS_CONTRACT),
                contractCreate(TOKEN_TRANSFERS_CONTRACT).gas(GAS_TO_OFFER),
                tokenAssociate(ACCOUNT, FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                tokenAssociate(SECOND_RECEIVER, FUNGIBLE_TOKEN),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(ACCOUNT, FUNGIBLE_TOKEN, TOKEN_TRANSFERS_CONTRACT, 200L)
                        .signedBy(DEFAULT_PAYER, ACCOUNT)
                        .fee(ONE_HBAR),
                cryptoTransfer(moving(200L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> {
                    final var receiver1 = asAddress(spec.registry().getAccountID(RECEIVER));
                    final var receiver2 = asAddress(spec.registry().getAccountID(SECOND_RECEIVER));
                    final var sender = asAddress(spec.registry().getAccountID(ACCOUNT));
                    final var accounts = new Address[] {
                        asHeadlongAddress(sender), asHeadlongAddress(receiver1), asHeadlongAddress(receiver2)
                    };
                    final var amounts = new long[] {-10L, 5L, 5L};
                    allRunFor(
                            spec,
                            // try transferTokens with empty amounts array
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKENS,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                            accounts,
                                            new long[] {})
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_EMPTY_AMOUNTS_ARRAY)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            emptyChildRecordsCheck(TXN_WITH_EMPTY_AMOUNTS_ARRAY, CONTRACT_REVERT_EXECUTED),
                            // try transferTokens with empty accountIds array
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKENS,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                            new Address[] {},
                                            amounts)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_EMPTY_ACCOUNTS_ARRAY)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            emptyChildRecordsCheck(TXN_WITH_EMPTY_ACCOUNTS_ARRAY, CONTRACT_REVERT_EXECUTED),
                            // try transferTokens with not length-matching accountIds and amounts arrays
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKENS,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                            accounts,
                                            new long[] {-10L, 5L})
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_NOT_LENGTH_MATCHING_ACCOUNTS_AND_AMOUNTS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            emptyChildRecordsCheck(
                                    TXN_WITH_NOT_LENGTH_MATCHING_ACCOUNTS_AND_AMOUNTS, CONTRACT_REVERT_EXECUTED),
                            // try transferTokens with negative amount
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKENS,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                            accounts,
                                            new long[] {-10L, -5L, -5L})
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_NEGATIVE_AMOUNTS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // try transferTokens with invalid token address
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKENS,
                                            asHeadlongAddress(new byte[20]),
                                            accounts,
                                            amounts)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_INVALID_TOKEN_ADDRESS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // lowering account balance
                            cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(ACCOUNT, RECEIVER)),
                            // try transferTokens where the account does not have enough balance
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKENS,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                            accounts,
                                            new long[] {-195L, 95L, 100L})
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
                }),
                childRecordsCheck(
                        TXN_WITH_NEGATIVE_AMOUNTS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN)),
                childRecordsCheck(
                        TXN_WITH_INVALID_TOKEN_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_TOKEN_ID)),
                childRecordsCheck(
                        TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INSUFFICIENT_TOKEN_BALANCE)));
    }

    @HapiTest
    final Stream<DynamicTest> shouldFailOnInvalidTokenTransferParametersAndConditions() {
        final var TXN_WITH_INVALID_TOKEN_ADDRESS = "TXN_WITH_INVALID_TOKEN_ADDRESS";
        final var TXN_WITH_INVALID_RECEIVER_ADDRESS = "TXN_WITH_INVALID_RECEIVER_ADDRESS";
        final var TXN_WITH_INVALID_SENDER_ADDRESS = "TXN_WITH_INVALID_SENDER_ADDRESS";
        final var TXN_WITH_NEGATIVE_AMOUNT = "TXN_WITH_NEGATIVE_AMOUNT";
        final var TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE = "TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE";

        return hapiTest(
                newKeyNamed(UNIVERSAL_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .supplyKey(UNIVERSAL_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(TOKEN_TRANSFERS_CONTRACT),
                contractCreate(TOKEN_TRANSFERS_CONTRACT).gas(GAS_TO_OFFER),
                tokenAssociate(ACCOUNT, FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(ACCOUNT, FUNGIBLE_TOKEN, TOKEN_TRANSFERS_CONTRACT, 200L)
                        .signedBy(DEFAULT_PAYER, ACCOUNT)
                        .fee(ONE_HBAR),
                cryptoTransfer(moving(200L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> {
                    final var receiver1 =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(RECEIVER)));
                    final var sender =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(ACCOUNT)));

                    allRunFor(
                            spec,
                            // try transferTokens where receiver is invalid
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKEN,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                            sender,
                                            asHeadlongAddress(new byte[20]),
                                            -5L)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_INVALID_RECEIVER_ADDRESS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            emptyChildRecordsCheck(TXN_WITH_INVALID_RECEIVER_ADDRESS, CONTRACT_REVERT_EXECUTED),
                            // try transferTokens where sender is invalid
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKEN,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                            asHeadlongAddress(new byte[20]),
                                            receiver1,
                                            -5L)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_INVALID_SENDER_ADDRESS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            emptyChildRecordsCheck(TXN_WITH_INVALID_SENDER_ADDRESS, CONTRACT_REVERT_EXECUTED),
                            // try transferTokens with invalid token address
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKEN,
                                            asHeadlongAddress(new byte[20]),
                                            sender,
                                            receiver1,
                                            10L)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_INVALID_TOKEN_ADDRESS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // try transferTokens with negative amount
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKEN,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                            sender,
                                            receiver1,
                                            -5L)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_NEGATIVE_AMOUNT)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            emptyChildRecordsCheck(TXN_WITH_NEGATIVE_AMOUNT, CONTRACT_REVERT_EXECUTED),
                            // lowering account balance
                            cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(ACCOUNT, RECEIVER)),
                            // try transferTokens where the account does not have enough balance
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKEN,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                            sender,
                                            receiver1,
                                            200L)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
                }),
                childRecordsCheck(
                        TXN_WITH_INVALID_TOKEN_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_TOKEN_ID)),
                childRecordsCheck(
                        TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INSUFFICIENT_TOKEN_BALANCE)));
    }

    @HapiTest
    final Stream<DynamicTest> shouldFailWhenTransferringMultipleNFTsWithInvalidParametersAndConditions() {
        final var TXN_WITH_INVALID_TOKEN_ADDRESS = "TXN_WITH_INVALID_TOKEN_ADDRESS";
        final var TXN_WITH_EMPTY_SENDER_ARRAY = "TXN_WITH_EMPTY_SENDER_ARRAY";
        final var TXN_WITH_EMPTY_RECEIVER_ARRAY = "TXN_WITH_EMPTY_RECEIVER_ARRAY";
        final var TXN_WITH_EMPTY_SERIALS_ARRAY = "TXN_WITH_EMPTY_RECEIVER_ARRAY";
        final var TXN_WITH_NOT_MATCHING_ARRAY_LENGTHS = "TXN_WITH_NOT_MATCHING_ARRAY_LENGTHS";
        final var TXN_WITH_INVALID_SERIALS = "TXN_WITH_INVALID_SERIALS";
        final var TXN_WITH_NOT_OWNED_NFT = "TXN_WITH_NOT_OWNED_NFT";

        return hapiTest(
                newKeyNamed(UNIVERSAL_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                cryptoCreate(SECOND_RECEIVER),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(UNIVERSAL_KEY)
                        .initialSupply(0),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("dark"), copyFromUtf8("matter"))),
                tokenAssociate(ACCOUNT, NON_FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L).between(TOKEN_TREASURY, ACCOUNT)),
                uploadInitCode(TOKEN_TRANSFERS_CONTRACT),
                contractCreate(TOKEN_TRANSFERS_CONTRACT).gas(GAS_TO_OFFER),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(ACCOUNT, NON_FUNGIBLE_TOKEN, TOKEN_TRANSFERS_CONTRACT, false, List.of(1L, 2L))
                        .signedBy(DEFAULT_PAYER, ACCOUNT)
                        .fee(ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var receiver1 = asAddress(spec.registry().getAccountID(RECEIVER));
                    final var receiver2 = asAddress(spec.registry().getAccountID(SECOND_RECEIVER));
                    final var sender = asAddress(spec.registry().getAccountID(ACCOUNT));

                    final var senderAccounts = new Address[] {asHeadlongAddress(sender), asHeadlongAddress(sender)};
                    final var receiverAccounts =
                            new Address[] {asHeadlongAddress(receiver1), asHeadlongAddress(receiver2)};

                    final var serials = new long[] {1L, 2L};
                    allRunFor(
                            spec,
                            // try transferNFTs with invalid token address
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFTS,
                                            asHeadlongAddress(new byte[20]),
                                            senderAccounts,
                                            receiverAccounts,
                                            serials)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_INVALID_TOKEN_ADDRESS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // try transferNFTs with empty sender array
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFTS,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            new Address[] {},
                                            receiverAccounts,
                                            serials)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_EMPTY_SENDER_ARRAY)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            emptyChildRecordsCheck(TXN_WITH_EMPTY_SENDER_ARRAY, CONTRACT_REVERT_EXECUTED),
                            // try transferNFTs with empty receiver array
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFTS,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            senderAccounts,
                                            new Address[] {},
                                            serials)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_EMPTY_RECEIVER_ARRAY)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            emptyChildRecordsCheck(TXN_WITH_EMPTY_RECEIVER_ARRAY, CONTRACT_REVERT_EXECUTED),
                            // try transferNFTs with empty serials array
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFTS,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            senderAccounts,
                                            receiverAccounts,
                                            new long[] {})
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_EMPTY_SERIALS_ARRAY)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            emptyChildRecordsCheck(TXN_WITH_EMPTY_SERIALS_ARRAY, CONTRACT_REVERT_EXECUTED),
                            // try transferNFTs with not matching arrays
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFTS,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            senderAccounts,
                                            receiverAccounts,
                                            new long[] {1L})
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_NOT_MATCHING_ARRAY_LENGTHS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            emptyChildRecordsCheck(TXN_WITH_NOT_MATCHING_ARRAY_LENGTHS, CONTRACT_REVERT_EXECUTED),
                            // try transferNFTs with invalid serials
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFTS,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            senderAccounts,
                                            receiverAccounts,
                                            new long[] {1L, -2L})
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_INVALID_SERIALS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // transfer a single NFT with serial 1L out of the account
                            cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(ACCOUNT, RECEIVER)),
                            // try transferNFTs with not owned NFT - serial 1L
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFTS,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            new Address[] {asHeadlongAddress(sender)},
                                            new Address[] {asHeadlongAddress(receiver1)},
                                            new long[] {1L})
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_NOT_OWNED_NFT)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
                }),
                childRecordsCheck(
                        TXN_WITH_INVALID_TOKEN_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_TOKEN_ID)),
                childRecordsCheck(
                        TXN_WITH_INVALID_SERIALS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_TOKEN_NFT_SERIAL_NUMBER)),
                childRecordsCheck(
                        TXN_WITH_NOT_OWNED_NFT,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)));
    }

    @HapiTest
    final Stream<DynamicTest> shouldFailOnInvalidNFTTransferParametersAndConditions() {
        final var TXN_WITH_INVALID_TOKEN_ADDRESS = "TXN_WITH_INVALID_TOKEN_ADDRESS";
        final var TXN_WITH_INVALID_RECEIVER_ADDRESS = "TXN_WITH_INVALID_RECEIVER_ADDRESS";
        final var TXN_WITH_INVALID_SENDER_ADDRESS = "TXN_WITH_INVALID_SENDER_ADDRESS";
        final var TXN_WITH_NEGATIVE_SERIAL = "TXN_WITH_NEGATIVE_SERIAL";
        final var TXN_ACCOUNT_DOES_NOT_OWN_NFT = "TXN_ACCOUNT_DOES_NOT_OWN_NFT";

        return hapiTest(
                newKeyNamed(UNIVERSAL_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(UNIVERSAL_KEY)
                        .initialSupply(0),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("dark"), copyFromUtf8("matter"))),
                tokenAssociate(ACCOUNT, NON_FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L).between(TOKEN_TREASURY, ACCOUNT)),
                uploadInitCode(TOKEN_TRANSFERS_CONTRACT),
                contractCreate(TOKEN_TRANSFERS_CONTRACT).gas(GAS_TO_OFFER),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(ACCOUNT, NON_FUNGIBLE_TOKEN, TOKEN_TRANSFERS_CONTRACT, false, List.of(1L, 2L))
                        .signedBy(DEFAULT_PAYER, ACCOUNT)
                        .fee(ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var receiver1 =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(RECEIVER)));
                    final var sender =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(ACCOUNT)));

                    allRunFor(
                            spec,
                            // try transferNFT where receiver is invalid
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFT,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            sender,
                                            asHeadlongAddress(asSolidityAddress(spec, 0)),
                                            1L)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_INVALID_RECEIVER_ADDRESS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // try transferNFT where sender is invalid
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFT,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            asHeadlongAddress(new byte[20]),
                                            receiver1,
                                            1L)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_INVALID_SENDER_ADDRESS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // try transferNFT with invalid token address
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFT,
                                            asHeadlongAddress(new byte[20]),
                                            sender,
                                            receiver1,
                                            1L)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_INVALID_TOKEN_ADDRESS)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // try transferNFT with negative/invalid serial
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFT,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            sender,
                                            receiver1,
                                            -2L)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_WITH_NEGATIVE_SERIAL)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            // transfer NFT with serial 2 to the receiver
                            cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 2L).between(ACCOUNT, RECEIVER)),
                            // try transferNFT where the account does not own the NFT with serial 2
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_NFT,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            sender,
                                            receiver1,
                                            2L)
                                    .payingWith(GENESIS)
                                    .gas(GAS_TO_OFFER)
                                    .via(TXN_ACCOUNT_DOES_NOT_OWN_NFT)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
                }),
                childRecordsCheck(
                        TXN_WITH_INVALID_RECEIVER_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ALIAS_KEY)),
                childRecordsCheck(
                        TXN_WITH_INVALID_SENDER_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ACCOUNT_ID)),
                childRecordsCheck(
                        TXN_WITH_INVALID_TOKEN_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_TOKEN_ID)),
                childRecordsCheck(
                        TXN_WITH_NEGATIVE_SERIAL,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_TOKEN_NFT_SERIAL_NUMBER)),
                childRecordsCheck(
                        TXN_ACCOUNT_DOES_NOT_OWN_NFT,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)));
    }
}
