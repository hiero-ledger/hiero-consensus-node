// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocalWithFunctionAbi;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedQueryIds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractCallLocalSuite {

    private static final String CONTRACT = "CreateTrivial";
    private static final String OWNERSHIP_CHECK_CONTRACT = "OwnershipCheck";
    private static final String OWNERSHIP_CHECK_CONTRACT_IS_OWNER_FUNCTION = "isOwner";
    private static final String TOKEN = "TestToken";
    private static final String NFT_TOKEN = "NftToken";
    private static final String SUPPLY_KEY = "SupplyKey";
    private static final String FIRST_MEMO = "firstMemo";
    private static final String SECOND_MEMO = "secondMemo";
    private static final String SYMBOL = "ħT";
    private static final int DECIMALS = 13;

    @HapiTest
    final Stream<DynamicTest> htsOwnershipCheckWorksWithAliasAddress() {
        final AtomicReference<AccountID> ecdsaAccountId = new AtomicReference<>();
        final AtomicReference<ByteString> ecdsaAccountIdLongZeroAddress = new AtomicReference<>();
        final AtomicReference<ByteString> ecdsaAccountIdAlias = new AtomicReference<>();
        final AtomicReference<com.esaulpaugh.headlong.abi.Address> nftOwnerAddress = new AtomicReference<>();
        final AtomicReference<com.esaulpaugh.headlong.abi.Address> senderAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                newKeyNamed(SUPPLY_KEY),
                // Create an NFT
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0L)
                        .supplyKey(SUPPLY_KEY),
                mintToken(NFT_TOKEN, List.of(metadata(FIRST_MEMO), metadata(SECOND_MEMO))),
                // Create an account with alias
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(
                        TokenMovement.movingUnique(NFT_TOKEN, 1L).between(TOKEN_TREASURY, SECP_256K1_SOURCE_KEY)),
                // Send some HBAR to the aliased account, it will need it to pay for the query
                cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS).between(GENESIS, SECP_256K1_SOURCE_KEY)),
                // Calculate and log the aliased account addresses
                withOpContext((spec, opLog) -> {
                    updateSpecFor(spec, SECP_256K1_SOURCE_KEY);
                    final var registry = spec.registry();
                    final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    final var evmAddressBytes = ByteString.copyFrom(addressBytes);
                    ecdsaAccountId.set(registry.getAccountID(SECP_256K1_SOURCE_KEY));
                    ecdsaAccountIdLongZeroAddress.set(ByteString.copyFrom(asSolidityAddress(ecdsaAccountId.get())));
                    ecdsaAccountIdAlias.set(evmAddressBytes);
                    var logIt = logIt("\nAccount ID: " + ecdsaAccountId.get() + "\n" + " Long-zero address: "
                            + Address.wrap(
                                    Bytes.of(ecdsaAccountIdLongZeroAddress.get().toByteArray())) + "\n"
                            + " Alias Recovered: "
                            + Address.wrap(Bytes.of(ecdsaAccountIdAlias.get().toByteArray())));
                    allRunFor(spec, logIt);
                }),
                // Deploy the OwnershipCheck contract
                uploadInitCode(OWNERSHIP_CHECK_CONTRACT),
                contractCreate(OWNERSHIP_CHECK_CONTRACT),
                withOpContext((spec, opLog) -> {
                    // Make the contract query with the Aliased account
                    var callLocal = contractCallLocal(
                                    OWNERSHIP_CHECK_CONTRACT,
                                    OWNERSHIP_CHECK_CONTRACT_IS_OWNER_FUNCTION,
                                    HapiParserUtil.asHeadlongAddress(
                                            asAddress(spec.registry().getTokenID(NFT_TOKEN))),
                                    1L)
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .nodePayment(10 * ONE_HBAR)
                            .exposingTypedResultsTo(results -> {
                                nftOwnerAddress.set((com.esaulpaugh.headlong.abi.Address) results[0]);
                                senderAddress.set((com.esaulpaugh.headlong.abi.Address) results[1]);
                            });
                    allRunFor(spec, callLocal);
                }),
                // Assert that the address of the query sender and the address of the nft owner returned by the
                // HTS precompiled contract are the same
                withOpContext((spec, opLog) -> assertEquals(
                        senderAddress.get(), nftOwnerAddress.get(), "Sender address should match the owner address.")));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(THRESHOLD),
                contractCall(CONTRACT, "create").gas(785_000),
                sendModified(withSuccessivelyVariedQueryIds(), () -> contractCallLocal(CONTRACT, "getIndirect")));
    }

    @HapiTest
    final Stream<DynamicTest> vanillaSuccess() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(THRESHOLD),
                contractCall(CONTRACT, "create").gas(785_000),
                sleepFor(3_000L),
                contractCallLocal(CONTRACT, "getIndirect")
                        .has(resultWith().resultViaFunctionName("getIndirect", CONTRACT, isLiteralResult(new Object[] {
                            BigInteger.valueOf(7L)
                        }))));
    }

    @HapiTest
    final Stream<DynamicTest> gasBelowIntrinsicGasFails() {
        return hapiTest(
                cryptoCreate("payer"),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, "create").gas(785_000),
                sleepFor(3_000L),
                contractCallLocal(CONTRACT, "getIndirect")
                        .nodePayment(1_234_567)
                        .gas(2_000L)
                        .hasAnswerOnlyPrecheck(INSUFFICIENT_GAS));
    }

    @HapiTest
    final Stream<DynamicTest> insufficientGasFails() {
        return hapiTest(
                cryptoCreate("payer"),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, "create").gas(785_000),
                contractCallLocal(CONTRACT, "getIndirect").gas(22_000L).hasAnswerOnlyPrecheck(INSUFFICIENT_GAS));
    }

    @HapiTest
    final Stream<DynamicTest> impureCallFails() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(THRESHOLD),
                contractCallLocal(CONTRACT, "create")
                        .nodePayment(1_234_567)
                        .hasAnswerOnlyPrecheck(ResponseCodeEnum.LOCAL_CALL_MODIFICATION_EXCEPTION));
    }

    @HapiTest
    final Stream<DynamicTest> successOnDeletedContract() {
        return hapiTest(
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).refusingEthConversion(),
                contractDelete(CONTRACT),
                contractCallLocal(CONTRACT, "create").nodePayment(1_234_567).hasAnswerOnlyPrecheck(OK));
    }

    @HapiTest
    final Stream<DynamicTest> invalidContractID() {
        final var invalidContract = HapiSpecSetup.getDefaultInstance().invalidContractName();
        final var functionAbi = getABIFor(FUNCTION, "getIndirect", "CreateTrivial");
        return hapiTest(
                contractCallLocalWithFunctionAbi(invalidContract, functionAbi)
                        .nodePayment(1_234_567)
                        .hasAnswerOnlyPrecheck(INVALID_CONTRACT_ID),
                contractCallLocalWithFunctionAbi("0.0.0", functionAbi)
                        .nodePayment(1_234_567)
                        .hasAnswerOnlyPrecheck(INVALID_CONTRACT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> insufficientFeeFails() {
        final long adequateQueryPayment = 500_000L;

        return hapiTest(
                cryptoCreate("payer"),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, "create").gas(785_000),
                contractCallLocal(CONTRACT, "getIndirect")
                        .nodePayment(adequateQueryPayment)
                        .fee(0L)
                        .payingWith("payer")
                        .hasAnswerOnlyPrecheck(INSUFFICIENT_TX_FEE));
    }

    @HapiTest
    final Stream<DynamicTest> lowBalanceFails() {
        final long adequateQueryPayment = 500_000_000L;

        return hapiTest(
                cryptoCreate("payer"),
                cryptoCreate("payer").balance(adequateQueryPayment),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, "create").gas(785_000),
                contractCallLocal(CONTRACT, "getIndirect")
                        .logged()
                        .payingWith("payer")
                        .nodePayment(adequateQueryPayment)
                        .hasAnswerOnlyPrecheck(INSUFFICIENT_PAYER_BALANCE),
                getAccountBalance("payer"));
    }

    @HapiTest
    final Stream<DynamicTest> erc20Query() {
        final var decimalsABI = "{\"constant\": true,\"inputs\": [],\"name\": \"decimals\","
                + "\"outputs\": [{\"name\": \"\",\"type\": \"uint8\"}],\"payable\": false,"
                + "\"type\": \"function\"}";

        return hapiTest(
                tokenCreate(TOKEN).decimals(DECIMALS).symbol(SYMBOL).asCallableContract(),
                contractCallLocalWithFunctionAbi(TOKEN, decimalsABI)
                        .has(resultWith().resultThruAbi(decimalsABI, isLiteralResult(new Object[] {DECIMALS}))));
    }

    // https://github.com/hashgraph/hedera-services/pull/5485
    @HapiTest
    final Stream<DynamicTest> callLocalDoesNotCheckSignaturesNorPayer() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(THRESHOLD),
                contractCall(CONTRACT, "create").gas(785_000),
                withOpContext((spec, opLog) -> IntStream.range(0, 2000).forEach(i -> {
                    final var create = cryptoCreate("account #" + i).deferStatusResolution();
                    final var callLocal = contractCallLocal(CONTRACT, "getIndirect")
                            .nodePayment(ONE_HBAR)
                            .hasAnswerOnlyPrecheckFrom(OK, BUSY);
                    allRunFor(spec, create, callLocal);
                })));
    }
}
