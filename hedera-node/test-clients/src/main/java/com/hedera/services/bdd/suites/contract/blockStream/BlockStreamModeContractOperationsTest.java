// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.blockStream;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.*;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.captureOneChildCreate2MetaFor;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.*;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.PARTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
class BlockStreamModeContractOperationsTest {

    private static final String MULTIPURPOSE_CONTRACT = "Multipurpose";
    private static final String REVERTER_CONSTRUCTOR_CONTRACT = "ReverterConstructor";
    private static final String CREATE_TRIVIAL_CONTRACT = "CreateTrivial";
    private static final String ASSOCIATE_CONTRACT = "AssociateContract";
    private static final String ERC20_CONTRACT = "ERC20Contract";
    private static final String PRNG_CONTRACT = "PrngSystemContract";
    private static final String GRACEFULLY_FAILING_PRNG_CONTRACT = "GracefullyFailingPrng";
    private static final String CREATE2_FACTORY_CONTRACT = "Create2Factory";
    private static final String ADMIN_KEY = "adminKey";

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("ContractCreate and ContractCall succeeds when blockStream.streamMode=BLOCKS")
    final Stream<DynamicTest> contractCallSucceedsWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                uploadInitCode(MULTIPURPOSE_CONTRACT),
                contractCreate(MULTIPURPOSE_CONTRACT).hasKnownStatus(SUCCESS),
                contractCall(MULTIPURPOSE_CONTRACT).hasKnownStatus(SUCCESS));
    }

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("ContractCreate that reverts in constructor still settles when blockStream.streamMode=BLOCKS")
    final Stream<DynamicTest> contractCreateRevertsWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                uploadInitCode(REVERTER_CONSTRUCTOR_CONTRACT),
                contractCreate(REVERTER_CONSTRUCTOR_CONTRACT).hasKnownStatus(CONTRACT_REVERT_EXECUTED));
    }

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("ContractCall mutating state succeeds when blockStream.streamMode=BLOCKS")
    final Stream<DynamicTest> contractCallWithStateChangeWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                uploadInitCode(MULTIPURPOSE_CONTRACT),
                contractCreate(MULTIPURPOSE_CONTRACT).hasKnownStatus(SUCCESS),
                contractCall(MULTIPURPOSE_CONTRACT, "believeIn", 7L)
                        .gas(100_000L)
                        .hasKnownStatus(SUCCESS));
    }

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("Nested CREATE from EVM succeeds when blockStream.streamMode=BLOCKS")
    final Stream<DynamicTest> nestedContractCreateWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                uploadInitCode(CREATE_TRIVIAL_CONTRACT),
                contractCreate(CREATE_TRIVIAL_CONTRACT).hasKnownStatus(SUCCESS),
                contractCall(CREATE_TRIVIAL_CONTRACT, "create").gas(400_000L).hasKnownStatus(SUCCESS));
    }

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("HTS system-contract call succeeds when blockStream.streamMode=BLOCKS")
    final Stream<DynamicTest> htsSystemContractCallWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                newKeyNamed("supplyKey"),
                cryptoCreate("treasury"),
                tokenCreate("fungibleToken")
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury("treasury")
                        .supplyKey("supplyKey"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT),
                withOpContext((spec, _) -> allRunFor(
                        spec,
                        contractCall(
                                        ASSOCIATE_CONTRACT,
                                        "associateTokenToThisContract",
                                        asHeadlongAddress(asHexedSolidityAddress(
                                                spec.registry().getTokenID("fungibleToken"))))
                                .gas(1_000_000L)
                                .hasKnownStatus(SUCCESS))));
    }

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("ERC-20 transfer via redirect proxy succeeds when blockStream.streamMode=BLOCKS")
    final Stream<DynamicTest> erc20RedirectTransferWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                newKeyNamed("supplyKey"),
                cryptoCreate("treasury"),
                cryptoCreate("recipient"),
                tokenCreate("fungibleToken")
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury("treasury")
                        .supplyKey("supplyKey"),
                tokenAssociate("recipient", "fungibleToken"),
                uploadInitCode(ERC20_CONTRACT),
                contractCreate(ERC20_CONTRACT).refusingEthConversion(),
                tokenAssociate(ERC20_CONTRACT, "fungibleToken"),
                cryptoTransfer(moving(100L, "fungibleToken").between("treasury", ERC20_CONTRACT)),
                withOpContext((spec, _) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC20_CONTRACT,
                                        "transfer",
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID("fungibleToken"))),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID("recipient"))),
                                        BigInteger.valueOf(10L))
                                .gas(1_000_000L)
                                .hasKnownStatus(SUCCESS))));
    }

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("HTS view call via redirect succeeds when blockStream.streamMode=BLOCKS")
    final Stream<DynamicTest> htsViewCallWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                newKeyNamed("supplyKey"),
                cryptoCreate("treasury"),
                tokenCreate("fungibleToken")
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury("treasury")
                        .supplyKey("supplyKey"),
                uploadInitCode(ERC20_CONTRACT),
                contractCreate(ERC20_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC20_CONTRACT,
                                        "totalSupply",
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID("fungibleToken"))))
                                .gas(1_000_000L)
                                .hasKnownStatus(SUCCESS))));
    }

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("HTS call with insufficient gas still settles when blockStream.streamMode=BLOCKS")
    final Stream<DynamicTest> htsInsufficientGasWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                newKeyNamed("supplyKey"),
                cryptoCreate("treasury"),
                tokenCreate("fungibleToken")
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury("treasury")
                        .supplyKey("supplyKey"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT),
                withOpContext((spec, _) -> allRunFor(
                        spec,
                        contractCall(
                                        ASSOCIATE_CONTRACT,
                                        "associateTokenToThisContract",
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID("fungibleToken"))))
                                .gas(50_000L)
                                .hasKnownStatus(INSUFFICIENT_GAS))));
    }

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("PRNG system-contract call succeeds when blockStream.streamMode=BLOCKS")
    final Stream<DynamicTest> prngSystemContractCallWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                uploadInitCode(PRNG_CONTRACT),
                contractCreate(PRNG_CONTRACT),
                contractCall(PRNG_CONTRACT, "getPseudorandomSeed")
                        .gas(1_000_000L)
                        .hasKnownStatus(SUCCESS));
    }

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("PRNG call with invalid selector still settles when blockStream.streamMode=BLOCKS")
    final Stream<DynamicTest> prngFailedSystemContractCallWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                uploadInitCode(GRACEFULLY_FAILING_PRNG_CONTRACT),
                contractCreate(GRACEFULLY_FAILING_PRNG_CONTRACT),
                contractCall(GRACEFULLY_FAILING_PRNG_CONTRACT, "performNonExistingServiceFunctionCall")
                        .gas(1_000_000L)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
    }

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("CREATE2 merging hollow account into contract succeeds when blockStream.streamMode=BLOCKS")
    final Stream<DynamicTest> hollowAccountMergeWithStreamModeBlocks() {

        final var tcValue = 1_234L;
        final var salt = BigInteger.valueOf(42);
        final AtomicReference<String> factoryEvmAddress = new AtomicReference<>(),
                expectedCreate2Address = new AtomicReference<>(),
                mergedAliasAddr = new AtomicReference<>(),
                mergedMirrorAddr = new AtomicReference<>();

        final AtomicReference<byte[]> testContractInitcode = new AtomicReference<>();

        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CREATE2_FACTORY_CONTRACT),
                contractCreate(CREATE2_FACTORY_CONTRACT)
                        .payingWith(GENESIS)
                        .adminKey(ADMIN_KEY)
                        .entityMemo(ENTITY_MEMO)
                        .via(CREATE_2_TXN)
                        .exposingContractIdTo(id -> factoryEvmAddress.set(asHexedSolidityAddress(id))),
                cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                sourcing(() -> contractCallLocal(
                                CREATE2_FACTORY_CONTRACT,
                                GET_BYTECODE,
                                asHeadlongAddress(factoryEvmAddress.get()),
                                salt)
                        .exposingTypedResultsTo(results -> testContractInitcode.set((byte[]) results[0]))
                        .payingWith(GENESIS)
                        .nodePayment(ONE_HBAR)),
                sourcing(() -> setExpectedCreate2Address(
                        CREATE2_FACTORY_CONTRACT, salt, expectedCreate2Address, testContractInitcode)),
                lazyCreateAccount(CREATION, expectedCreate2Address, Optional.empty(), Optional.empty(), null),
                getTxnRecord(CREATION).andAllChildRecords(),
                sourcing(() -> contractCall(CREATE2_FACTORY_CONTRACT, DEPLOY, testContractInitcode.get(), salt)
                        .payingWith(GENESIS)
                        .gas(4_000_000L)
                        .sending(tcValue)
                        .via("hollowMergeTxn")),
                captureOneChildCreate2MetaFor(
                        "Merged deployed contract with hollow account",
                        "hollowMergeTxn",
                        mergedMirrorAddr,
                        mergedAliasAddr));
    }
}
