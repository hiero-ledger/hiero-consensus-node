// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip904;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.spec.HapiPropertySource.asHexedSolidityAddress;
import static com.hedera.services.bdd.spec.HapiPropertySource.contractIdFromHexedMirrorAddress;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AutoAssocAsserts.accountTokenPairsInAnyOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.captureChildCreate2MetaFor;
import static com.hedera.services.bdd.suites.contract.Utils.captureOneChildCreate2MetaFor;
import static com.hedera.services.bdd.suites.contract.hapi.ContractUpdateSuite.ADMIN_KEY;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.CONTRACT_REPORTED_LOG_MESSAGE;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.CREATE_2_TXN;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.CREATION;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.DEPLOY;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.ENTITY_MEMO;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.GET_BYTECODE;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.assertCreate2Address;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.lazyCreateAccount;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.setExpectedCreate2Address;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.setIdentifiers;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.A_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_MEMO;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.NFT_CREATE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.NFT_INFINITE_SUPPLY_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.PARTY;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.TOKEN_A_CREATE;
import static com.hedera.services.bdd.suites.crypto.CryptoTransferSuite.COUNTERPARTY;
import static com.hedera.services.bdd.suites.crypto.CryptoTransferSuite.HODL_XFER;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.MULTI_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * Tests expected behavior when the {@code entities.unlimitedAutoAssociationsEnabled} feature flag is off for
 * <a href="https://hips.hedera.com/hip/hip-904">HIP-904, "Frictionless Airdrops"</a>.
 */
@HapiTestLifecycle
public class AirdropsDisabledTest {
    private static final Logger LOG = LogManager.getLogger(AirdropsDisabledTest.class);

    private static final String owner = "owner";
    private static final String receiver = "receiver";
    private static final String fungibleToken = "fungibleToken";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "tokens.airdrops.enabled", "false",
                "entities.unlimitedAutoAssociationsEnabled", "false",
                "tokens.airdrops.claim.enabled", "false"));
        testLifecycle.doAdhoc(
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(receiver),
                tokenCreate(fungibleToken).tokenType(TokenType.FUNGIBLE_COMMON).treasury(owner));
    }

    @HapiTest
    @DisplayName("airdrop feature flag is disabled")
    final Stream<DynamicTest> airdropNotSupported() {
        return hapiTest(tokenAirdrop(moving(10, fungibleToken).between(owner, receiver))
                .payingWith(owner)
                .hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    @DisplayName("airdrop claim feature flag is disabled")
    final Stream<DynamicTest> airdropClaimNotSupported() {
        return hapiTest(tokenClaimAirdrop(pendingAirdrop(owner, receiver, fungibleToken))
                .hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    @SuppressWarnings("java:S5960")
    final Stream<DynamicTest> canMergeCreate2MultipleCreatesWithHollowAccount() {
        final var tcValue = 1_234L;
        final var contract = "Create2MultipleCreates";
        final var creation = CREATION;
        final var salt = BigInteger.valueOf(42);
        final var adminKey = ADMIN_KEY;
        final AtomicReference<String> factoryEvmAddress = new AtomicReference<>();
        final AtomicReference<String> expectedCreate2Address = new AtomicReference<>();
        final AtomicReference<String> hollowCreationAddress = new AtomicReference<>();
        final AtomicReference<String> mergedAliasAddr = new AtomicReference<>();
        final AtomicReference<String> mergedMirrorAddr = new AtomicReference<>();
        final AtomicReference<byte[]> testContractInitcode = new AtomicReference<>();

        final var initialTokenSupply = 1000;
        final AtomicReference<TokenID> ftId = new AtomicReference<>();
        final AtomicReference<TokenID> nftId = new AtomicReference<>();
        final AtomicReference<AccountID> partyId = new AtomicReference<>();
        final AtomicReference<byte[]> partyAlias = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(adminKey),
                newKeyNamed(MULTI_KEY),
                uploadInitCode(contract),
                contractCreate(contract)
                        .payingWith(GENESIS)
                        .adminKey(adminKey)
                        .entityMemo(ENTITY_MEMO)
                        .gas(10_000_000L)
                        .via(CREATE_2_TXN)
                        .exposingContractIdTo(id -> factoryEvmAddress.set(asHexedSolidityAddress(id))),
                cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                tokenCreate(A_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(FINITE)
                        .initialSupply(initialTokenSupply)
                        .maxSupply(10L * initialTokenSupply)
                        .treasury(PARTY)
                        .via(TOKEN_A_CREATE),
                tokenCreate(NFT_INFINITE_SUPPLY_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(PARTY)
                        .via(NFT_CREATE),
                mintToken(
                        NFT_INFINITE_SUPPLY_TOKEN, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                setIdentifiers(Optional.of(ftId), Optional.of(nftId), Optional.of(partyId), Optional.of(partyAlias)),
                sourcing(() -> contractCallLocal(
                                contract, GET_BYTECODE, asHeadlongAddress(factoryEvmAddress.get()), salt)
                        .exposingTypedResultsTo(results -> {
                            final var tcInitcode = (byte[]) results[0];
                            testContractInitcode.set(tcInitcode);
                            LOG.info(CONTRACT_REPORTED_LOG_MESSAGE, tcInitcode.length);
                        })
                        .payingWith(GENESIS)
                        .nodePayment(ONE_HBAR)),
                sourcing(() -> setExpectedCreate2Address(contract, salt, expectedCreate2Address, testContractInitcode)),
                // Now create a hollow account at the desired address
                lazyCreateAccount(creation, expectedCreate2Address, Optional.of(ftId), Optional.of(nftId), partyAlias),
                getTxnRecord(creation)
                        .andAllChildRecords()
                        .exposingCreationsTo(l -> hollowCreationAddress.set(l.getFirst())),
                sourcing(() -> getAccountInfo(hollowCreationAddress.get()).logged()),
                sourcing(() -> contractCall(contract, DEPLOY, testContractInitcode.get(), salt)
                        .payingWith(GENESIS)
                        .gas(10_000_000L)
                        .sending(tcValue)
                        .via(CREATE_2_TXN)),
                // mod-service externalizes internal creations in order of their initiation,
                // while mono-service externalizes them in order of their completion
                captureChildCreate2MetaFor(
                        3,
                        0,
                        "Merged deployed contract with hollow account",
                        CREATE_2_TXN,
                        mergedMirrorAddr,
                        mergedAliasAddr),
                withOpContext((spec, opLog) -> {
                    final var opExpectedMergedNonce = getTxnRecord(CREATE_2_TXN)
                            .andAllChildRecords()
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .contractWithNonce(
                                                    contractIdFromHexedMirrorAddress(mergedMirrorAddr.get()), 3L)));
                    allRunFor(spec, opExpectedMergedNonce);
                }),
                sourcing(() -> getContractInfo(mergedAliasAddr.get())
                        .has(contractWith()
                                .numKvPairs(4)
                                .defaultAdminKey()
                                .maxAutoAssociations(2)
                                .hasAlreadyUsedAutomaticAssociations(2)
                                .memo(LAZY_MEMO)
                                .balance(ONE_HBAR + tcValue))
                        .hasToken(relationshipWith(A_TOKEN).balance(500))
                        .hasToken(relationshipWith(NFT_INFINITE_SUPPLY_TOKEN).balance(1))
                        .logged()),
                sourcing(() -> getContractBytecode(mergedAliasAddr.get()).isNonEmpty()),
                sourcing(() -> assertCreate2Address(contract, salt, expectedCreate2Address, testContractInitcode)),
                cryptoCreate("confirmingNoEntityIdCollision"));
    }

    @HapiTest
    final Stream<DynamicTest> canMergeCreate2ChildWithHollowAccountFungibleTransfersUnlimitedAssociations() {
        final var tcValue = 1_234L;
        final var contract = "Create2Factory";

        final var creation = CREATION;
        final var salt = BigInteger.valueOf(42);
        final var adminKey = ADMIN_KEY;
        final AtomicReference<String> factoryEvmAddress = new AtomicReference<>();
        final AtomicReference<String> expectedCreate2Address = new AtomicReference<>();
        final AtomicReference<String> hollowCreationAddress = new AtomicReference<>();
        final AtomicReference<String> mergedAliasAddr = new AtomicReference<>();
        final AtomicReference<String> mergedMirrorAddr = new AtomicReference<>();
        final AtomicReference<byte[]> testContractInitcode = new AtomicReference<>();

        final var initialTokenSupply = 1000;

        final int fungibleTransfersSize = 5;
        final AtomicReference<TokenID>[] ftIds = new AtomicReference[fungibleTransfersSize];
        for (int i = 0; i < ftIds.length; i++) {
            ftIds[i] = new AtomicReference<>();
        }

        final AtomicReference<AccountID> partyId = new AtomicReference<>();
        final AtomicReference<byte[]> partyAlias = new AtomicReference<>();

        final int givenOpsSize = 6;
        HapiSpecOperation[] givenOps = new HapiSpecOperation[givenOpsSize + (fungibleTransfersSize * 2)];
        givenOps[0] = newKeyNamed(adminKey);
        givenOps[1] = newKeyNamed(MULTI_KEY);
        givenOps[2] = uploadInitCode(contract);
        givenOps[3] = contractCreate(contract)
                .payingWith(GENESIS)
                .adminKey(adminKey)
                .entityMemo(ENTITY_MEMO)
                .via(CREATE_2_TXN)
                .exposingContractIdTo(id -> factoryEvmAddress.set(asHexedSolidityAddress(id)));
        givenOps[4] = cryptoCreate(PARTY).maxAutomaticTokenAssociations(2);
        givenOps[5] = setIdentifiers(Optional.empty(), Optional.empty(), Optional.of(partyId), Optional.of(partyAlias));

        int j = 0;
        for (int i = givenOpsSize; i < fungibleTransfersSize + givenOpsSize; i++) {
            givenOps[i] = tokenCreate(A_TOKEN + j)
                    .tokenType(FUNGIBLE_COMMON)
                    .supplyType(FINITE)
                    .initialSupply(initialTokenSupply)
                    .maxSupply(10L * initialTokenSupply)
                    .treasury(PARTY)
                    .via(TOKEN_A_CREATE + j);
            j++;
        }

        int j1 = 0;
        for (int i = fungibleTransfersSize + givenOpsSize; i < (fungibleTransfersSize * 2) + givenOpsSize; i++) {
            givenOps[i] = setIdentifierToken(Optional.of(ftIds[j1]), A_TOKEN + j1);
            j1++;
        }

        return hapiTest(flattened(
                givenOps,
                // GET BYTECODE OF THE CREATE2 CONTRACT
                sourcing(() -> contractCallLocal(
                                contract, GET_BYTECODE, asHeadlongAddress(factoryEvmAddress.get()), salt)
                        .exposingTypedResultsTo(results -> {
                            final var tcInitcode = (byte[]) results[0];
                            testContractInitcode.set(tcInitcode);
                            LOG.info(CONTRACT_REPORTED_LOG_MESSAGE, tcInitcode.length);
                        })
                        .payingWith(GENESIS)
                        .nodePayment(ONE_HBAR)),
                // GET THE ADDRESS WHERE THE CONTRACT WILL BE DEPLOYED
                sourcing(() -> setExpectedCreate2Address(contract, salt, expectedCreate2Address, testContractInitcode)),

                // Now create a hollow account at the desired address
                lazyCreateAccountWithFungibleTransfers(creation, expectedCreate2Address, ftIds, partyAlias),
                getTxnRecord(creation)
                        .andAllChildRecords()
                        .logged()
                        .exposingCreationsTo(l -> hollowCreationAddress.set(l.get(0))),
                sourcing(() -> getAccountInfo(hollowCreationAddress.get())
                        .hasAlreadyUsedAutomaticAssociations(fungibleTransfersSize)
                        .logged()),
                // deploy create2
                sourcing(() -> contractCall(contract, DEPLOY, testContractInitcode.get(), salt)
                        .payingWith(GENESIS)
                        .gas(4_000_000L)
                        .sending(tcValue)
                        .via("TEST2")),
                getTxnRecord("TEST2").andAllChildRecords().logged(),
                captureOneChildCreate2MetaFor(
                        "Merged deployed contract with hollow account", "TEST2", mergedMirrorAddr, mergedAliasAddr),

                // check failure when trying to deploy again
                sourcing(() -> contractCall(contract, DEPLOY, testContractInitcode.get(), salt)
                        .payingWith(GENESIS)
                        .gas(4_000_000L)
                        /* Cannot repeat CREATE2
                        with same args without destroying the existing contract */
                        .hasKnownStatusFrom(INVALID_SOLIDITY_ADDRESS, CONTRACT_REVERT_EXECUTED)),

                // check created contract
                sourcing(() -> getContractInfo(mergedAliasAddr.get())
                        .has(contractWith()
                                .defaultAdminKey()
                                .maxAutoAssociations(fungibleTransfersSize)
                                .hasAlreadyUsedAutomaticAssociations(fungibleTransfersSize)
                                .memo(LAZY_MEMO)
                                .balance(tcValue))
                        .logged()),
                sourcing(() -> getContractBytecode(mergedAliasAddr.get()).isNonEmpty()),
                sourcing(() -> assertCreate2Address(contract, salt, expectedCreate2Address, testContractInitcode))));
    }

    @HapiTest
    final Stream<DynamicTest> royaltyCollectorsCanUseAutoAssociation() {
        final var uniqueWithRoyalty = "uniqueWithRoyalty";
        final var firstFungible = "firstFungible";
        final var secondFungible = "secondFungible";
        final var firstRoyaltyCollector = "firstRoyaltyCollector";
        final var secondRoyaltyCollector = "secondRoyaltyCollector";
        final var plentyOfSlots = 10;
        final var exchangeAmount = 12 * 15;
        final var firstRoyaltyAmount = exchangeAmount / 12;
        final var secondRoyaltyAmount = exchangeAmount / 15;
        final var netExchangeAmount = exchangeAmount - firstRoyaltyAmount - secondRoyaltyAmount;

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(firstRoyaltyCollector).maxAutomaticTokenAssociations(plentyOfSlots),
                cryptoCreate(secondRoyaltyCollector).maxAutomaticTokenAssociations(plentyOfSlots),
                cryptoCreate(PARTY).maxAutomaticTokenAssociations(plentyOfSlots),
                cryptoCreate(COUNTERPARTY).maxAutomaticTokenAssociations(plentyOfSlots),
                newKeyNamed(MULTI_KEY),
                getAccountInfo(PARTY).savingSnapshot(PARTY),
                getAccountInfo(COUNTERPARTY).savingSnapshot(COUNTERPARTY),
                getAccountInfo(firstRoyaltyCollector).savingSnapshot(firstRoyaltyCollector),
                getAccountInfo(secondRoyaltyCollector).savingSnapshot(secondRoyaltyCollector),
                tokenCreate(firstFungible)
                        .treasury(TOKEN_TREASURY)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(123456789),
                tokenCreate(secondFungible)
                        .treasury(TOKEN_TREASURY)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(123456789),
                cryptoTransfer(
                        moving(1000, firstFungible).between(TOKEN_TREASURY, COUNTERPARTY),
                        moving(1000, secondFungible).between(TOKEN_TREASURY, COUNTERPARTY)),
                tokenCreate(uniqueWithRoyalty)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(MULTI_KEY)
                        .withCustom(royaltyFeeNoFallback(1, 12, firstRoyaltyCollector))
                        .withCustom(royaltyFeeNoFallback(1, 15, secondRoyaltyCollector))
                        .initialSupply(0L),
                mintToken(uniqueWithRoyalty, List.of(copyFromUtf8("HODL"))),
                cryptoTransfer(movingUnique(uniqueWithRoyalty, 1L).between(TOKEN_TREASURY, PARTY)),
                cryptoTransfer(
                                movingUnique(uniqueWithRoyalty, 1L).between(PARTY, COUNTERPARTY),
                                moving(12 * 15L, firstFungible).between(COUNTERPARTY, PARTY),
                                moving(12 * 15L, secondFungible).between(COUNTERPARTY, PARTY))
                        .fee(ONE_HBAR)
                        .via(HODL_XFER),
                getTxnRecord(HODL_XFER)
                        .hasPriority(recordWith()
                                .autoAssociated(accountTokenPairsInAnyOrder(List.of(
                                        /* The counterparty auto-associates to the non-fungible type */
                                        Pair.of(COUNTERPARTY, uniqueWithRoyalty),
                                        /* The sending party auto-associates to both fungibles */
                                        Pair.of(PARTY, firstFungible),
                                        Pair.of(PARTY, secondFungible),
                                        /* Both royalty collectors auto-associate to both fungibles */
                                        Pair.of(firstRoyaltyCollector, firstFungible),
                                        Pair.of(secondRoyaltyCollector, firstFungible),
                                        Pair.of(firstRoyaltyCollector, secondFungible),
                                        Pair.of(secondRoyaltyCollector, secondFungible))))),
                getAccountInfo(PARTY)
                        .has(accountWith()
                                .newAssociationsFromSnapshot(
                                        PARTY,
                                        List.of(
                                                relationshipWith(uniqueWithRoyalty)
                                                        .balance(0),
                                                relationshipWith(firstFungible).balance(netExchangeAmount),
                                                relationshipWith(secondFungible).balance(netExchangeAmount)))),
                getAccountInfo(COUNTERPARTY)
                        .has(accountWith()
                                .newAssociationsFromSnapshot(
                                        PARTY,
                                        List.of(
                                                relationshipWith(uniqueWithRoyalty)
                                                        .balance(1),
                                                relationshipWith(firstFungible).balance(1000L - exchangeAmount),
                                                relationshipWith(secondFungible).balance(1000L - exchangeAmount)))),
                getAccountInfo(firstRoyaltyCollector)
                        .has(accountWith()
                                .newAssociationsFromSnapshot(
                                        PARTY,
                                        List.of(
                                                relationshipWith(firstFungible).balance(exchangeAmount / 12),
                                                relationshipWith(secondFungible).balance(exchangeAmount / 12)))),
                getAccountInfo(secondRoyaltyCollector)
                        .has(accountWith()
                                .newAssociationsFromSnapshot(
                                        PARTY,
                                        List.of(
                                                relationshipWith(firstFungible).balance(exchangeAmount / 15),
                                                relationshipWith(secondFungible).balance(exchangeAmount / 15)))));
    }

    @HapiTest
    @SuppressWarnings("java:S5960")
    final Stream<DynamicTest> canMergeCreate2ChildWithHollowAccount() {
        final var tcValue = 1_234L;
        final var contract = "Create2Factory";
        final var creation = CREATION;
        final var salt = BigInteger.valueOf(42);
        final var adminKey = ADMIN_KEY;
        final AtomicReference<String> factoryEvmAddress = new AtomicReference<>();
        final AtomicReference<String> expectedCreate2Address = new AtomicReference<>();
        final AtomicReference<String> hollowCreationAddress = new AtomicReference<>();
        final AtomicReference<String> mergedAliasAddr = new AtomicReference<>();
        final AtomicReference<String> mergedMirrorAddr = new AtomicReference<>();
        final AtomicReference<byte[]> testContractInitcode = new AtomicReference<>();

        final var initialTokenSupply = 1000;
        final AtomicReference<TokenID> ftId = new AtomicReference<>();
        final AtomicReference<TokenID> nftId = new AtomicReference<>();
        final AtomicReference<AccountID> partyId = new AtomicReference<>();
        final AtomicReference<byte[]> partyAlias = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(adminKey),
                newKeyNamed(MULTI_KEY),
                uploadInitCode(contract),
                contractCreate(contract)
                        .payingWith(GENESIS)
                        .adminKey(adminKey)
                        .entityMemo(ENTITY_MEMO)
                        .via(CREATE_2_TXN)
                        .exposingContractIdTo(id -> factoryEvmAddress.set(asHexedSolidityAddress(id))),
                cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                tokenCreate(A_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(FINITE)
                        .initialSupply(initialTokenSupply)
                        .maxSupply(10L * initialTokenSupply)
                        .treasury(PARTY)
                        .via(TOKEN_A_CREATE),
                tokenCreate(NFT_INFINITE_SUPPLY_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(PARTY)
                        .via(NFT_CREATE),
                mintToken(
                        NFT_INFINITE_SUPPLY_TOKEN, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                setIdentifiers(Optional.of(ftId), Optional.of(nftId), Optional.of(partyId), Optional.of(partyAlias)),
                sourcing(() -> contractCallLocal(
                                contract, GET_BYTECODE, asHeadlongAddress(factoryEvmAddress.get()), salt)
                        .exposingTypedResultsTo(results -> {
                            final var tcInitcode = (byte[]) results[0];
                            testContractInitcode.set(tcInitcode);
                            LOG.info(CONTRACT_REPORTED_LOG_MESSAGE, tcInitcode.length);
                        })
                        .payingWith(GENESIS)
                        .nodePayment(ONE_HBAR)),
                sourcing(() -> setExpectedCreate2Address(contract, salt, expectedCreate2Address, testContractInitcode)),
                // Now create a hollow account at the desired address
                lazyCreateAccount(creation, expectedCreate2Address, Optional.of(ftId), Optional.of(nftId), partyAlias),
                getTxnRecord(creation)
                        .andAllChildRecords()
                        .logged()
                        .exposingCreationsTo(l -> hollowCreationAddress.set(l.get(0))),
                sourcing(() -> getAccountInfo(hollowCreationAddress.get()).logged()),
                sourcing(() -> contractCall(contract, "wronglyDeployTwice", testContractInitcode.get(), salt)
                        .payingWith(GENESIS)
                        .gas(4_000_000L)
                        .sending(tcValue)
                        .hasKnownStatusFrom(INVALID_SOLIDITY_ADDRESS, CONTRACT_REVERT_EXECUTED)),
                sourcing(() -> contractCall(contract, DEPLOY, testContractInitcode.get(), salt)
                        .payingWith(GENESIS)
                        .gas(4_000_000L)
                        .sending(tcValue)
                        .via("TEST2")),
                getTxnRecord("TEST2").andAllChildRecords().logged(),
                captureOneChildCreate2MetaFor(
                        "Merged deployed contract with hollow account", "TEST2", mergedMirrorAddr, mergedAliasAddr),
                sourcing(() -> contractCall(contract, DEPLOY, testContractInitcode.get(), salt)
                        .payingWith(GENESIS)
                        .gas(4_000_000L)
                        /* Cannot repeat CREATE2
                        with same args without destroying the existing contract */

                        .hasKnownStatusFrom(INVALID_SOLIDITY_ADDRESS, CONTRACT_REVERT_EXECUTED)),
                sourcing(() -> getContractInfo(mergedAliasAddr.get())
                        .has(contractWith()
                                .numKvPairs(2)
                                .defaultAdminKey()
                                .maxAutoAssociations(2)
                                .hasAlreadyUsedAutomaticAssociations(2)
                                .memo(LAZY_MEMO)
                                .balance(ONE_HBAR + tcValue))
                        .hasToken(relationshipWith(A_TOKEN).balance(500))
                        .hasToken(relationshipWith(NFT_INFINITE_SUPPLY_TOKEN).balance(1))
                        .logged()),
                sourcing(() -> getContractBytecode(mergedAliasAddr.get()).isNonEmpty()),
                sourcing(() -> assertCreate2Address(contract, salt, expectedCreate2Address, testContractInitcode)));
    }

    private HapiCryptoTransfer lazyCreateAccountWithFungibleTransfers(
            String creation,
            AtomicReference<String> expectedCreate2Address,
            AtomicReference<TokenID> ftIds[],
            AtomicReference<byte[]> partyAlias) {
        return cryptoTransfer((spec, b) -> {
                    for (AtomicReference<TokenID> ftId : ftIds) {
                        b.addTokenTransfers(TokenTransferList.newBuilder()
                                .setToken(ftId.get())
                                .addTransfers(Utils.aaWith(spec, partyAlias.get(), -500))
                                .addTransfers(Utils.aaWith(spec, expectedCreate2Address.get(), +500)));
                    }
                })
                .signedBy(DEFAULT_PAYER, PARTY)
                .fee(ONE_HBAR)
                .via(creation);
    }

    private CustomSpecAssert setIdentifierToken(final Optional<AtomicReference<TokenID>> ftId, final String token) {
        return withOpContext((spec, opLog) -> {
            final var registry = spec.registry();
            ftId.ifPresent(id -> id.set(registry.getTokenID(token)));
        });
    }
}
