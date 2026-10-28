// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.SECP256K1;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.allowanceTinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUniqueWithAllowance;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingWithAllowance;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/** Allowances dispatched by contracts for a spender named by EVM address. */
@Tag(SMART_CONTRACT)
public class AliasSpenderContractPathTest {

    /** A 20-byte EVM address that is neither long-zero nor an alias of any account. */
    private static final String MISSING_EVM_ADDRESS = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3";

    private static final String OWNER = "aliasContractPathOwner";
    private static final String SPENDER_KEY = "aliasContractPathSpenderKey";
    private static final String RECEIVER = "aliasContractPathReceiver";
    private static final String MULTI_KEY = "aliasContractPathKey";
    private static final String FUNGIBLE = "aliasContractPathFungible";
    private static final String NFT = "aliasContractPathNft";
    private static final String ERC20_CONTRACT = "ERC20Contract";
    private static final String HTS_CONTRACT = "HtsApproveAllowance";
    private static final String HRC632_CONTRACT = "HRC632Contract";
    private static final String IHRC632 = "IHRC632";
    private static final String ERC20_ABI = "ERC20ABI";
    /** ERC-721 {@code setApprovalForAll(address,bool)}; the bundled ERC721ABI resource does not declare it. */
    private static final String ERC721_SET_APPROVAL_FOR_ALL_ABI =
            "{\"inputs\":[{\"name\":\"operator\",\"type\":\"address\"},{\"name\":\"approved\",\"type\":\"bool\"}],"
                    + "\"name\":\"setApprovalForAll\",\"outputs\":[],\"stateMutability\":\"nonpayable\",\"type\":\"function\"}";

    private static final long HBAR_GRANT = 5 * ONE_HBAR;
    private static final long HBAR_SPEND = ONE_HBAR;
    private static final long FT_GRANT = 10L;
    private static final long FT_SPEND = 1L;

    @HapiTest
    final Stream<DynamicTest> ercApproveZeroForMissingAddressSucceeds() {
        final var txn = "ercApproveZeroForMissingAddress";

        return hapiTest(
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(ERC20_CONTRACT),
                contractCreate(ERC20_CONTRACT),
                tokenAssociate(ERC20_CONTRACT, FUNGIBLE),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC20_CONTRACT,
                                        "approve",
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE))),
                                        asHeadlongAddress(MISSING_EVM_ADDRESS),
                                        BigInteger.ZERO)
                                .payingWith(OWNER)
                                .gas(1_000_000L)
                                .via(txn)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(txn, SUCCESS, recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> setApprovalForAllFalseForMissingAddressSucceeds() {
        final var txn = "setApprovalForAllFalseForMissingAddress";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(HTS_CONTRACT),
                contractCreate(HTS_CONTRACT).refusingEthConversion(),
                tokenAssociate(HTS_CONTRACT, NFT),
                mintToken(NFT, List.of(ByteString.copyFromUtf8("a"))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        HTS_CONTRACT,
                                        "htsSetApprovalForAll",
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NFT))),
                                        asHeadlongAddress(MISSING_EVM_ADDRESS),
                                        false)
                                .payingWith(OWNER)
                                .gas(5_000_000L)
                                .via(txn)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(txn, SUCCESS, recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> hbarApproveZeroForMissingAddressSucceeds() {
        final var txn = "hbarApproveZeroForMissingAddress";

        return hapiTest(
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC632_CONTRACT),
                contractCreate(HRC632_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        HRC632_CONTRACT,
                                        "hbarApproveCall",
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(HRC632_CONTRACT))),
                                        asHeadlongAddress(MISSING_EVM_ADDRESS),
                                        BigInteger.ZERO)
                                .payingWith(OWNER)
                                .gas(1_000_000L)
                                .via(txn)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(txn, SUCCESS, recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> ercApproveBySpenderEvmAddressGrantsAndRevokes() {
        final var grantTxn = "ercApproveBySpenderEvmAddress";
        final var revokeTxn = "ercApproveZeroBySpenderEvmAddress";

        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE),
                tokenAssociate(RECEIVER, FUNGIBLE),
                cryptoTransfer(moving(100L, FUNGIBLE).between(TOKEN_TREASURY, OWNER)),
                spenderCreatedFromEcdsaKeyAlias(),
                sourcingContextual(
                        spec -> ercApproveBySpenderEvmAddress(spec, FT_GRANT).via(grantTxn)),
                childRecordsCheck(grantTxn, SUCCESS, recordWith().status(SUCCESS)),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .tokenAllowancesCount(1)
                                .tokenAllowancesContaining(FUNGIBLE, SPENDER_KEY, FT_GRANT)),
                cryptoTransfer(movingWithAllowance(FT_SPEND, FUNGIBLE).between(OWNER, RECEIVER))
                        .payingWith(SPENDER_KEY)
                        .signedBy(SPENDER_KEY),
                sourcingContextual(
                        spec -> ercApproveBySpenderEvmAddress(spec, 0L).via(revokeTxn)),
                childRecordsCheck(revokeTxn, SUCCESS, recordWith().status(SUCCESS)),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().tokenAllowancesCount(0)),
                cryptoTransfer(movingWithAllowance(FT_SPEND, FUNGIBLE).between(OWNER, RECEIVER))
                        .payingWith(SPENDER_KEY)
                        .signedBy(SPENDER_KEY)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE, 100L - FT_SPEND));
    }

    @HapiTest
    final Stream<DynamicTest> setApprovalForAllBySpenderEvmAddressGrantsAndRevokes() {
        final var grantTxn = "setApprovalForAllTrueBySpenderEvmAddress";
        final var revokeTxn = "setApprovalForAllFalseBySpenderEvmAddress";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                mintToken(NFT, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                tokenAssociate(OWNER, NFT),
                tokenAssociate(RECEIVER, NFT),
                cryptoTransfer(movingUnique(NFT, 1L, 2L).between(TOKEN_TREASURY, OWNER)),
                spenderCreatedFromEcdsaKeyAlias(),
                sourcingContextual(
                        spec -> setApprovalForAllBySpenderEvmAddress(spec, true).via(grantTxn)),
                childRecordsCheck(grantTxn, SUCCESS, recordWith().status(SUCCESS)),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .nftApprovedForAllAllowancesCount(1)
                                .nftApprovedAllowancesContaining(NFT, SPENDER_KEY)),
                cryptoTransfer(movingUniqueWithAllowance(NFT, 1L).between(OWNER, RECEIVER))
                        .payingWith(SPENDER_KEY)
                        .signedBy(SPENDER_KEY),
                sourcingContextual(spec ->
                        setApprovalForAllBySpenderEvmAddress(spec, false).via(revokeTxn)),
                childRecordsCheck(revokeTxn, SUCCESS, recordWith().status(SUCCESS)),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(0)),
                cryptoTransfer(movingUniqueWithAllowance(NFT, 2L).between(OWNER, RECEIVER))
                        .payingWith(SPENDER_KEY)
                        .signedBy(SPENDER_KEY)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                getAccountBalance(OWNER).hasTokenBalance(NFT, 1L));
    }

    @HapiTest
    final Stream<DynamicTest> hbarApproveBySpenderEvmAddressGrantsAndRevokes() {
        final var grantTxn = "hbarApproveBySpenderEvmAddress";
        final var revokeTxn = "hbarApproveZeroBySpenderEvmAddress";

        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                spenderCreatedFromEcdsaKeyAlias(),
                sourcingContextual(
                        spec -> hbarApproveBySpenderEvmAddress(spec, HBAR_GRANT).via(grantTxn)),
                childRecordsCheck(grantTxn, SUCCESS, recordWith().status(SUCCESS)),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .cryptoAllowancesContaining(SPENDER_KEY, HBAR_GRANT)),
                cryptoTransfer(allowanceTinyBarsFromTo(OWNER, RECEIVER, HBAR_SPEND))
                        .payingWith(SPENDER_KEY)
                        .signedBy(SPENDER_KEY),
                sourcingContextual(
                        spec -> hbarApproveBySpenderEvmAddress(spec, 0L).via(revokeTxn)),
                childRecordsCheck(revokeTxn, SUCCESS, recordWith().status(SUCCESS)),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().cryptoAllowancesCount(0)),
                balanceSnapshot("ownerBeforeSpend", OWNER),
                cryptoTransfer(allowanceTinyBarsFromTo(OWNER, RECEIVER, HBAR_SPEND))
                        .payingWith(SPENDER_KEY)
                        .signedBy(SPENDER_KEY)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                getAccountBalance(OWNER).hasTinyBars(changeFromSnapshot("ownerBeforeSpend", 0L)));
    }

    /** Auto-creates the spender from an ECDSA key alias, which also maps the EVM address derived from the key. */
    private static SpecOperation spenderCreatedFromEcdsaKeyAlias() {
        return withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    newKeyNamed(SPENDER_KEY).shape(SECP256K1),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SPENDER_KEY, ONE_HUNDRED_HBARS)));
            updateSpecFor(spec, SPENDER_KEY);
        });
    }

    /** Calls ERC-20 {@code approve} on the fungible token, naming the spender by its EVM address. */
    private static HapiContractCall ercApproveBySpenderEvmAddress(final HapiSpec spec, final long amount) {
        final var token = asHexedSolidityAddress(spec.registry().getTokenID(FUNGIBLE));
        return ownerCallNamingSpender(
                spec, token, getABIFor(FUNCTION, "approve", ERC20_ABI), BigInteger.valueOf(amount));
    }

    /** Calls ERC-721 {@code setApprovalForAll} on the NFT, naming the spender by its EVM address. */
    private static HapiContractCall setApprovalForAllBySpenderEvmAddress(final HapiSpec spec, final boolean approved) {
        final var token = asHexedSolidityAddress(spec.registry().getTokenID(NFT));
        return ownerCallNamingSpender(spec, token, ERC721_SET_APPROVAL_FOR_ALL_ABI, approved);
    }

    /** Calls {@code hbarApprove} on the owner's account proxy, naming the spender by its EVM address. */
    private static HapiContractCall hbarApproveBySpenderEvmAddress(final HapiSpec spec, final long amount) {
        final var ownerProxy =
                String.valueOf(spec.registry().getAccountID(OWNER).getAccountNum());
        return ownerCallNamingSpender(
                spec, ownerProxy, getABIFor(FUNCTION, "hbarApprove", IHRC632), BigInteger.valueOf(amount));
    }

    /** Calls {@code target} as the owner, passing the spender's EVM address and {@code value} to {@code abi}. */
    private static HapiContractCall ownerCallNamingSpender(
            final HapiSpec spec, final String target, final String abi, final Object value) {
        final var ecdsaKey =
                spec.registry().getKey(SPENDER_KEY).getECDSASecp256K1().toByteArray();
        return contractCallWithFunctionAbi(target, abi, asHeadlongAddress(recoverAddressFromPubKey(ecdsaKey)), value)
                .payingWith(OWNER)
                .gas(1_000_000L)
                .hasKnownStatus(SUCCESS);
    }
}
