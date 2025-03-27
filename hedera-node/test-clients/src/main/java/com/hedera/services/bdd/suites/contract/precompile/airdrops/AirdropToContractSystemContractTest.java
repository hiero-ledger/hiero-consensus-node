// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.airdrops;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asHexedSolidityAddress;
import static com.hedera.services.bdd.spec.HapiPropertySource.realm;
import static com.hedera.services.bdd.spec.HapiPropertySource.shard;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.*;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNftPendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FEE_SCHEDULE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FREEZE_KEY;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFeeNetOfTransfers;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithChild;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.CREATE_2_TXN;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.CREATION;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.DEPLOY;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.GET_BYTECODE;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.lazyCreateAccount;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.setExpectedCreate2Address;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.checkForBalances;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.checkForEmptyBalance;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareAccountAddresses;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareContractAddresses;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareFTAirdrops;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareNFTAirdrops;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareTokenAddresses;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareTokensAndBalances;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.PARTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableReason;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.assertions.AccountInfoAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.dsl.operations.queries.GetBalanceOperation;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.commons.lang3.function.TriFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
@Tag(SMART_CONTRACT)
public class AirdropToContractSystemContractTest {

    @Contract(contract = "Airdrop", maxAutoAssociations = -1)
    static SpecContract airdropContract;

    @Account(tinybarBalance = 100_000_000_000_000_000L)
    static SpecAccount sender;

    @BeforeAll
    public static void setup(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(sender.authorizeContract(airdropContract));
    }

    @Nested
    @DisplayName("With the Receiver already associated")
    class AirdropToAssociatedReceiverContract {

        @HapiTest
        @DisplayName("Can airdrop fungible token to a contract that is already associated to it")
        public Stream<DynamicTest> airdropToContract(
                @Contract(contract = "AssociateContract", isImmutable = true) SpecContract receiverContract,
                @FungibleToken(initialSupply = 1000L) SpecFungibleToken token) {
            return hapiTest(
                    receiverContract.call("associateTokenToThisContract", token).gas(1_000_000L),
                    sender.associateTokens(token),
                    token.treasury().transferUnitsTo(sender, 1000L, token),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiverContract, 10L)
                            .gas(1500000),
                    receiverContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Can airdrop multiple tokens to contract that is already associated with them")
        public Stream<DynamicTest> airdropTokensToContract(
                @Contract(contract = "AssociateContract", isImmutable = true) SpecContract receiverContract,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token3,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft3) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token1, token2, token3, nft1, nft2, nft3),
                        token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                        token2.treasury().transferUnitsTo(sender, 1_000L, token2),
                        token3.treasury().transferUnitsTo(sender, 1_000L, token3));
                allRunFor(
                        spec,
                        nft1.treasury().transferNFTsTo(sender, nft1, 1L),
                        nft2.treasury().transferNFTsTo(sender, nft2, 1L),
                        nft3.treasury().transferNFTsTo(sender, nft3, 1L),
                        receiverContract
                                .call("associateTokensToThisContract", (Object)
                                        prepareTokenAddresses(spec, token1, token2, token3, nft1, nft2, nft3))
                                .gas(5_000_000L));
                allRunFor(
                        spec,
                        checkForEmptyBalance(
                                receiverContract, List.of(token1, token2, token3), List.of(nft1, nft2, nft3)));
                final var serials = new long[] {1L, 1L, 1L};
                allRunFor(
                        spec,
                        airdropContract
                                .call(
                                        "mixedAirdrop",
                                        prepareTokenAddresses(spec, token1, token2, token3),
                                        prepareTokenAddresses(spec, nft1, nft2, nft3),
                                        prepareAccountAddresses(spec, sender, sender, sender),
                                        prepareContractAddresses(
                                                spec, receiverContract, receiverContract, receiverContract),
                                        prepareAccountAddresses(spec, sender, sender, sender),
                                        prepareContractAddresses(
                                                spec, receiverContract, receiverContract, receiverContract),
                                        10L,
                                        serials)
                                .gas(1750000),
                        checkForBalances(receiverContract, List.of(token1, token2, token3), List.of(nft1, nft2, nft3)));
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Can airdrop multiple tokens to a contract that is already associated to some of them")
        public Stream<DynamicTest> canAirdropTokensToContractWithSomeAssociations(
                @Contract(contract = "AssociateContract", isImmutable = true, maxAutoAssociations = 2)
                        SpecContract receiverContract,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token1, token2, nft1, nft2),
                        token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                        token2.treasury().transferUnitsTo(sender, 1_000L, token2));
                allRunFor(
                        spec,
                        nft1.treasury().transferNFTsTo(sender, nft1, 1L),
                        nft2.treasury().transferNFTsTo(sender, nft2, 1L),
                        receiverContract
                                .call("associateTokensToThisContract", (Object)
                                        prepareTokenAddresses(spec, token1, nft1))
                                .gas(1_500_000L));
                allRunFor(spec, checkForEmptyBalance(receiverContract, List.of(token1, token2), List.of(nft1, nft2)));
                final var serials = new long[] {1L, 1L};
                allRunFor(
                        spec,
                        airdropContract
                                .call(
                                        "mixedAirdrop",
                                        prepareTokenAddresses(spec, token1, token2),
                                        prepareTokenAddresses(spec, nft1, nft2),
                                        prepareAccountAddresses(spec, sender, sender),
                                        prepareContractAddresses(spec, receiverContract, receiverContract),
                                        prepareAccountAddresses(spec, sender, sender),
                                        prepareContractAddresses(spec, receiverContract, receiverContract),
                                        10L,
                                        serials)
                                .gas(1_500_000L)
                                .sending(85_000_000L),
                        checkForBalances(receiverContract, List.of(token1, token2), List.of(nft1, nft2)));
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName(
                "Can airdrop two tokens to contract with no remaining auto assoc slot and already associated to one of the tokens")
        public Stream<DynamicTest> canAirdropTwoTokensToContractWithNoAutoAssocSlots(
                @Contract(contract = "AssociateContract", isImmutable = true, maxAutoAssociations = 0)
                        SpecContract receiverContract,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token1, token2),
                        token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                        token2.treasury().transferUnitsTo(sender, 1_000L, token2));
                allRunFor(
                        spec,
                        receiverContract
                                .call("associateTokenToThisContract", token1)
                                .gas(1_000_000L));
                checkForEmptyBalance(receiverContract, List.of(token1, token2), List.of());
                allRunFor(
                        spec,
                        airdropContract
                                .call(
                                        "tokenNAmountAirdrops",
                                        prepareTokenAddresses(spec, token1, token2),
                                        prepareAccountAddresses(spec, sender, sender),
                                        prepareContractAddresses(spec, receiverContract, receiverContract),
                                        10L)
                                .gas(1_500_000L)
                                .sending(85_000_000L)
                                .via("pendingAirdrop"),
                        checkForBalances(receiverContract, List.of(token1), List.of()),
                        checkForEmptyBalance(receiverContract, List.of(token2), List.of()),
                        validateChargedUsd("pendingAirdrop", 0.124),
                        getTxnRecord("pendingAirdrop")
                                .logged()
                                .hasChildRecords(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(
                                                prepareFTAirdrops(sender, receiverContract, List.of(token2))
                                                        .toArray(TokenMovement[]::new)))));
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName(
                "Airdropped token with custom fees to be paid by the contract receiver should be paid by the sender")
        public Stream<DynamicTest> airdropWithCustomFees(
                @Contract(contract = "AssociateContract", isImmutable = true) SpecContract receiverContract,
                @NonNull
                        @FungibleToken(
                                initialSupply = 1_000_000L,
                                keys = {ADMIN_KEY, FEE_SCHEDULE_KEY})
                        final SpecFungibleToken token) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token),
                        airdropContract.associateTokens(token),
                        receiverContract
                                .call("associateTokenToThisContract", token)
                                .gas(1_000_000L),
                        // Initial check for receiver balance
                        receiverContract.getBalance().andAssert(balance -> balance.hasTinyBars(0L)),
                        token.treasury().transferUnitsTo(sender, 1_000L, token),
                        tokenFeeScheduleUpdate(token.name())
                                .withCustom(fractionalFee(1L, 10L, 1L, OptionalLong.of(100L), airdropContract.name())));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiverContract, 10L)
                                .gas(1_500_000L),
                        // Fractional fee is paid by the transferred value, so 10 tokens should be transferred
                        sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 990L)),
                        receiverContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 9)),
                        airdropContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1L)));
            }));
        }

        @HapiTest
        @DisplayName(
                "Airdropped token with custom fees (net of transfers = true) to be paid by the contract receiver should be paid by the sender")
        public Stream<DynamicTest> airdropWithCustomFeesNetOfTransfersTrue(
                @Contract(contract = "AssociateContract", isImmutable = true) SpecContract receiverContract,
                @NonNull
                        @FungibleToken(
                                initialSupply = 1_000_000L,
                                keys = {ADMIN_KEY, FEE_SCHEDULE_KEY})
                        final SpecFungibleToken token) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token),
                        airdropContract.associateTokens(token),
                        receiverContract
                                .call("associateTokenToThisContract", token)
                                .gas(1_000_000L),
                        // Initial check for receiver balance
                        receiverContract.getBalance().andAssert(balance -> balance.hasTinyBars(0L)),
                        token.treasury().transferUnitsTo(sender, 1_000L, token),
                        tokenFeeScheduleUpdate(token.name())
                                .withCustom(fractionalFeeNetOfTransfers(
                                        1L, 10L, 1L, OptionalLong.of(100L), airdropContract.name())));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiverContract, 10L)
                                .gas(1_500_000L),
                        // Fractional fee with net of transfers is paid by the sender, so 11 tokens should be
                        // transferred
                        sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 989L)),
                        receiverContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10)),
                        airdropContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1L)));
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName(
                "Airdropped token with custom fees to be paid by the contract receiver that is a fee collector for another fee would not be paid")
        public Stream<DynamicTest> airdropWithCustomFeeWhereReceiverIsCollectorForAnotherFee(
                @Contract(contract = "AssociateContract", isImmutable = true) SpecContract receiverContract,
                @NonNull
                        @FungibleToken(
                                initialSupply = 1_000_000L,
                                keys = {ADMIN_KEY, FEE_SCHEDULE_KEY})
                        final SpecFungibleToken token) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token),
                        airdropContract.associateTokens(token),
                        receiverContract
                                .call("associateTokenToThisContract", token)
                                .gas(1_000_000L),
                        // Initial check for receiver balance
                        receiverContract.getBalance().andAssert(balance -> balance.hasTinyBars(0L)),
                        token.treasury().transferUnitsTo(sender, 1_000L, token),
                        tokenFeeScheduleUpdate(token.name())
                                .withCustom(
                                        fractionalFee(1L, 10L, 1L, OptionalLong.of(100L), airdropContract.name(), true))
                                .withCustom(fixedHbarFee(10L, receiverContract.name())));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiverContract, 10L)
                                .gas(1_500_000L),
                        // The custom fee is not paid as the receiver is also the collector of another fee
                        // and the allCollectorsExempt option is enabled for the Fractional fee
                        receiverContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10)),
                        airdropContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)));
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName(
                "Airdropped token with custom fees to be paid by the contract receiver when the collector is contract should not be paid")
        public Stream<DynamicTest> airdropWithCustomFeesToContractCollector() {
            return hapiTest(withOpContext((spec, opLog) -> {
                final var token = "token";
                final var receiverContract = "receiverContract";
                final var tokenAddress = new AtomicReference<Address>();
                final var receiverContractAddress = new AtomicReference<String>();
                allRunFor(
                        spec,
                        uploadInitCode("AssociateContract"),
                        contractCreate(receiverContract)
                                .bytecode("AssociateContract")
                                .gas(5_000_000L)
                                .exposingNumTo(
                                        num -> receiverContractAddress.set(asHexedSolidityAddress(shard, realm, num))),
                        newKeyNamed("adminKey"),
                        newKeyNamed("feeScheduleKey"),
                        tokenCreate(token)
                                .initialSupply(1_000_000L)
                                .treasury(receiverContract)
                                .adminKey("adminKey")
                                .feeScheduleKey("feeScheduleKey")
                                .exposingAddressTo(tokenAddress::set),
                        tokenAssociate(sender.name(), token),
                        // Initial check for receiver balance
                        getAccountBalance(receiverContract).hasTokenBalance(token, 1_000_000L),
                        cryptoTransfer(moving(1_000L, token).between(receiverContract, sender.name())),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(fractionalFeeNetOfTransfers(
                                        1L, 10L, 1L, OptionalLong.of(100L), receiverContract)));
                allRunFor(
                        spec,
                        airdropContract
                                .call(
                                        "tokenAirdrop",
                                        tokenAddress.get(),
                                        sender,
                                        asHeadlongAddress(receiverContractAddress.get()),
                                        10L)
                                .gas(1_500_000L),
                        // New balance should be:
                        // 1_000_000(initial balance) - 1_000(transfer to sender) + 10 (airdropped amount) = 999_010
                        // with no fees paid as the receiver is also the treasury of the token
                        // and token treasuries are exempt of custom fees.
                        getAccountBalance(receiverContract).hasTokenBalance(token, 999_010L));
            }));
        }
    }

    @Nested
    @DisplayName("With the Receiver not associated")
    class AirdropToContract {

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Can airdrop token to a contract that is not associated to it with free auto association slots")
        public Stream<DynamicTest> airdropToContractWithFreeAutoAssocSlots(
                @Contract(contract = "EmptyOne", isImmutable = true, maxAutoAssociations = 10)
                        SpecContract receiverContract,
                @FungibleToken(initialSupply = 1_000_000L) SpecFungibleToken token,
                @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken nft) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token, nft),
                        airdropContract.associateTokens(token, nft),
                        // Initial check for receiver balance
                        receiverContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                        receiverContract.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                        token.treasury().transferUnitsTo(sender, 1_000L, token),
                        nft.treasury().transferNFTsTo(sender, nft, 1L));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiverContract, 10L)
                                .sending(85_000_000L)
                                .gas(1_500_000L),
                        airdropContract
                                .call("nftAirdrop", nft, sender, receiverContract, 1L)
                                .sending(85_000_000L)
                                .gas(1_500_000L),
                        receiverContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)),
                        receiverContract.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1L)));
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Can airdrop token to a contract deployed with CREATE2 on hollow account address")
        public Stream<DynamicTest> canAirdropToContractDeployedWithCreate2(
                @FungibleToken(initialSupply = 1_000_000L) SpecFungibleToken token,
                @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken nft) {
            final AtomicReference<String> factoryEvmAddress = new AtomicReference<>();
            final AtomicReference<byte[]> testContractInitcode = new AtomicReference<>();
            final AtomicReference<String> expectedCreate2Address = new AtomicReference<>();
            final AtomicReference<ByteString> partyAlias = new AtomicReference<>();
            final AtomicReference<String> hollowCreationAddress = new AtomicReference<>();

            final var salt = BigInteger.valueOf(42);
            final var contract = "Create2Factory";
            final var creation = CREATION;
            final var tcValue = 1_234L;

            return hapiTest(withOpContext((spec, opLog) -> allRunFor(
                    spec,
                    sender.associateTokens(token, nft),
                    airdropContract.associateTokens(token, nft),
                    uploadInitCode(contract),
                    sourcing(() -> contractCreate(contract)
                            .payingWith(GENESIS)
                            .via(CREATE_2_TXN)
                            .exposingNumTo(num -> factoryEvmAddress.set(asHexedSolidityAddress(0, 0, num)))),
                    // GET BYTECODE OF THE CREATE2 CONTRACT
                    sourcing(() -> contractCallLocal(
                                    contract, GET_BYTECODE, asHeadlongAddress(factoryEvmAddress.get()), salt)
                            .exposingTypedResultsTo(results -> {
                                final var tcInitcode = (byte[]) results[0];
                                testContractInitcode.set(tcInitcode);
                            })
                            .payingWith(GENESIS)
                            .nodePayment(ONE_HBAR)),
                    // GET THE ADDRESS WHERE THE CONTRACT WILL BE DEPLOYED
                    sourcing(() ->
                            setExpectedCreate2Address(contract, salt, expectedCreate2Address, testContractInitcode)),
                    cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                    // Now create a hollow account at the desired address
                    lazyCreateAccount(creation, expectedCreate2Address, Optional.empty(), Optional.empty(), partyAlias),
                    sourcing(() -> getTxnRecord(creation)
                            .andAllChildRecords()
                            .logged()
                            .exposingCreationsTo(l -> hollowCreationAddress.set(l.get(0)))),
                    sourcing(() -> contractCall(contract, DEPLOY, testContractInitcode.get(), salt)
                            .payingWith(GENESIS)
                            .gas(10_000_000L)
                            .sending(tcValue)
                            .via(CREATE_2_TXN)),
                    // Initial check for receiver balance
                    sourcing(
                            () -> getAccountBalance(hollowCreationAddress.get()).hasTokenBalance(token.name(), 0L)),
                    token.treasury().transferUnitsTo(sender, 1_000L, token),
                    nft.treasury().transferNFTsTo(sender, nft, 1L),
                    // Airdrop token and nft to the contract's alias
                    sourcing(() -> contractCall(
                                    airdropContract.name(),
                                    "tokenAirdrop",
                                    token.addressOn(spec.targetNetworkOrThrow()),
                                    sender.addressOn(spec.targetNetworkOrThrow()),
                                    asHeadlongAddress(expectedCreate2Address.get()),
                                    10L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("pendingFTAirdrop")),
                    sourcing(() -> contractCall(
                                    airdropContract.name(),
                                    "nftAirdrop",
                                    nft.addressOn(spec.targetNetworkOrThrow()),
                                    sender.addressOn(spec.targetNetworkOrThrow()),
                                    asHeadlongAddress(expectedCreate2Address.get()),
                                    1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("pendingNFTAirdrop")),

                    // Check for the pending airdrop records
                    sourcing(() -> getTxnRecord("pendingFTAirdrop")
                            .logged()
                            .hasChildRecords(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(moving(10, token.name())
                                            .between(sender.name(), hollowCreationAddress.get()))))),
                    sourcing(() -> getTxnRecord("pendingNFTAirdrop")
                            .logged()
                            .hasChildRecords(recordWith()
                                    .pendingAirdrops(includingNftPendingAirdrop(
                                            TokenMovement.movingUnique(nft.name(), 1L)
                                                    .between(sender.name(), hollowCreationAddress.get()))))))));
        }

        @HapiTest
        @DisplayName("Airdrop to Contract with maxAutoAssociations = 0")
        public Stream<DynamicTest> airdropToContractWithMaxAutoAssocZero(
                @Contract(contract = "EmptyOne", isImmutable = true, maxAutoAssociations = 0)
                        SpecContract receiverContract,
                @FungibleToken(initialSupply = 1_000_000L) SpecFungibleToken token,
                @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken nft) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token, nft),
                        airdropContract.associateTokens(token, nft),
                        token.treasury().transferUnitsTo(sender, 1_000L, token),
                        nft.treasury().transferNFTsTo(sender, nft, 1L));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiverContract, 10L)
                                .gas(1_500_000L)
                                .sending(85_000_000L)
                                .via("pendingFTAirdrop"),
                        airdropContract
                                .call("nftAirdrop", nft, sender, receiverContract, 1L)
                                .gas(1_500_000L)
                                .sending(85_000_000L)
                                .via("pendingNFTAirdrop"),
                        getTxnRecord("pendingFTAirdrop")
                                .logged()
                                .hasChildRecords(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(
                                                prepareFTAirdrops(sender, receiverContract, List.of(token))
                                                        .toArray(TokenMovement[]::new)))),
                        getTxnRecord("pendingNFTAirdrop")
                                .logged()
                                .hasChildRecords(recordWith()
                                        .pendingAirdrops(includingNftPendingAirdrop(
                                                prepareNFTAirdrops(sender, receiverContract, List.of(nft))
                                                        .toArray(TokenMovement[]::new)))));
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Airdrop to Contract that has filled all its maxAutoAssociation slots")
        public Stream<DynamicTest> airdropToContractWithFilledMaxAutoAssoc(
                @Contract(contract = "EmptyOne", isImmutable = true, maxAutoAssociations = 1)
                        SpecContract receiverContract,
                @FungibleToken(initialSupply = 1_000_000L) SpecFungibleToken tokenFillingTheSlot,
                @FungibleToken(initialSupply = 1_000_000L) SpecFungibleToken token,
                @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken nft) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token, nft),
                        airdropContract.associateTokens(token, nft),
                        token.treasury().transferUnitsTo(sender, 1_000L, token),
                        nft.treasury().transferNFTsTo(sender, nft, 1L));
                allRunFor(
                        spec,
                        tokenFillingTheSlot.treasury().transferUnitsTo(receiverContract, 1_000L, tokenFillingTheSlot),
                        receiverContract
                                .getInfo()
                                .andAssert(
                                        contract -> contract.has(contractWith().maxAutoAssociations(1))));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiverContract, 10L)
                                .gas(1_500_000L)
                                .sending(85_000_000L)
                                .via("pendingFTAirdrop"),
                        airdropContract
                                .call("nftAirdrop", nft, sender, receiverContract, 1L)
                                .gas(1_500_000L)
                                .sending(85_000_000L)
                                .via("pendingNFTAirdrop"),
                        getTxnRecord("pendingFTAirdrop")
                                .logged()
                                .hasChildRecords(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(
                                                prepareFTAirdrops(sender, receiverContract, List.of(token))
                                                        .toArray(TokenMovement[]::new)))),
                        getTxnRecord("pendingNFTAirdrop")
                                .logged()
                                .hasChildRecords(recordWith()
                                        .pendingAirdrops(includingNftPendingAirdrop(
                                                prepareNFTAirdrops(sender, receiverContract, List.of(nft))
                                                        .toArray(TokenMovement[]::new)))));
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Can airdrop multiple tokens to contract that has free auto association slots")
        public Stream<DynamicTest> airdropTokensToContractWithFreeSlots(
                @Contract(contract = "AssociateContract", isImmutable = true, maxAutoAssociations = -1)
                        SpecContract receiverContract,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token3,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft3) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token1, token2, token3, nft1, nft2, nft3),
                        token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                        token2.treasury().transferUnitsTo(sender, 1_000L, token2),
                        token3.treasury().transferUnitsTo(sender, 1_000L, token3));
                allRunFor(
                        spec,
                        nft1.treasury().transferNFTsTo(sender, nft1, 1L),
                        nft2.treasury().transferNFTsTo(sender, nft2, 1L),
                        nft3.treasury().transferNFTsTo(sender, nft3, 1L));
                allRunFor(
                        spec,
                        checkForEmptyBalance(
                                receiverContract, List.of(token1, token2, token3), List.of(nft1, nft2, nft3)));
                final var serials = new long[] {1L, 1L, 1L};
                allRunFor(
                        spec,
                        airdropContract
                                .call(
                                        "mixedAirdrop",
                                        prepareTokenAddresses(spec, token1, token2, token3),
                                        prepareTokenAddresses(spec, nft1, nft2, nft3),
                                        prepareAccountAddresses(spec, sender, sender, sender),
                                        prepareContractAddresses(
                                                spec, receiverContract, receiverContract, receiverContract),
                                        prepareAccountAddresses(spec, sender, sender, sender),
                                        prepareContractAddresses(
                                                spec, receiverContract, receiverContract, receiverContract),
                                        10L,
                                        serials)
                                .sending(500_000_000L)
                                .gas(1750000),
                        checkForBalances(receiverContract, List.of(token1, token2, token3), List.of(nft1, nft2, nft3)));
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Can airdrop multiple tokens to contract that has no free auto association slots")
        public Stream<DynamicTest> airdropTokensToContractWithoutFreeSlots(
                @Contract(contract = "AssociateContract", isImmutable = true, maxAutoAssociations = 0)
                        SpecContract receiverContract,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token3,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2,
                @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft3) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        sender.associateTokens(token1, token2, token3, nft1, nft2, nft3),
                        token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                        token2.treasury().transferUnitsTo(sender, 1_000L, token2),
                        token3.treasury().transferUnitsTo(sender, 1_000L, token3));
                allRunFor(
                        spec,
                        nft1.treasury().transferNFTsTo(sender, nft1, 1L),
                        nft2.treasury().transferNFTsTo(sender, nft2, 1L),
                        nft3.treasury().transferNFTsTo(sender, nft3, 1L));
                allRunFor(
                        spec,
                        checkForEmptyBalance(
                                receiverContract, List.of(token1, token2, token3), List.of(nft1, nft2, nft3)));
                final var serials = new long[] {1L, 1L, 1L};
                allRunFor(
                        spec,
                        airdropContract
                                .call(
                                        "mixedAirdrop",
                                        prepareTokenAddresses(spec, token1, token2, token3),
                                        prepareTokenAddresses(spec, nft1, nft2, nft3),
                                        prepareAccountAddresses(spec, sender, sender, sender),
                                        prepareContractAddresses(
                                                spec, receiverContract, receiverContract, receiverContract),
                                        prepareAccountAddresses(spec, sender, sender, sender),
                                        prepareContractAddresses(
                                                spec, receiverContract, receiverContract, receiverContract),
                                        10L,
                                        serials)
                                .sending(500_000_000L)
                                .gas(1750000)
                                .via("pendingAirdrops"),
                        getTxnRecord("pendingAirdrops")
                                .logged()
                                .hasChildRecords(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(prepareFTAirdrops(
                                                        sender, receiverContract, List.of(token1, token2, token3))
                                                .toArray(TokenMovement[]::new)))
                                        .pendingAirdrops(includingNftPendingAirdrop(
                                                prepareNFTAirdrops(sender, receiverContract, List.of(nft1, nft2, nft3))
                                                        .toArray(TokenMovement[]::new)))));
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Multiple airdrops with single/multiple senders and single/multiple contract receivers")
        public Stream<DynamicTest> multipleAirdrops(
                @NonNull @Account final SpecAccount sender1,
                @NonNull @Account final SpecAccount sender2,
                @NonNull @Account final SpecAccount sender3,
                @NonNull @Account final SpecAccount sender4,
                @NonNull @Account final SpecAccount sender5,
                @NonNull
                        @Contract(
                                contract = "EmptyOne",
                                name = "receiver1",
                                isImmutable = true,
                                maxAutoAssociations = -1)
                        SpecContract receiverContract1,
                @NonNull
                        @Contract(
                                contract = "EmptyOne",
                                name = "receiver2",
                                isImmutable = true,
                                maxAutoAssociations = -1)
                        SpecContract receiverContract2,
                @NonNull
                        @Contract(
                                contract = "EmptyOne",
                                name = "receiver3",
                                isImmutable = true,
                                maxAutoAssociations = -1)
                        SpecContract receiverContract3,
                @NonNull
                        @Contract(
                                contract = "EmptyOne",
                                name = "receiver4",
                                isImmutable = true,
                                maxAutoAssociations = -1)
                        SpecContract receiverContract4,
                @NonNull
                        @Contract(
                                contract = "EmptyOne",
                                name = "receiver5",
                                isImmutable = true,
                                maxAutoAssociations = -1)
                        SpecContract receiverContract5,
                @NonNull
                        @Contract(
                                contract = "EmptyOne",
                                name = "receiver6",
                                isImmutable = true,
                                maxAutoAssociations = -1)
                        SpecContract receiverContract6,
                @NonNull @FungibleToken(initialSupply = 1_000_000L, name = "token1") final SpecFungibleToken token1,
                @NonNull @FungibleToken(initialSupply = 1_000_000L, name = "token2") final SpecFungibleToken token2,
                @NonNull @FungibleToken(initialSupply = 1_000_000L, name = "token3") final SpecFungibleToken token3,
                @NonNull @FungibleToken(initialSupply = 1_000_000L, name = "token4") final SpecFungibleToken token4,
                @NonNull @FungibleToken(initialSupply = 1_000_000L, name = "token5") final SpecFungibleToken token5) {
            return hapiTest(withOpContext((spec, opLog) -> {
                final var senders = List.of(sender1, sender2, sender3, sender4, sender5);
                final var tokens = List.of(token1, token2, token3, token4, token5);
                final var receiverContracts = List.of(
                        receiverContract2, receiverContract3, receiverContract4, receiverContract5, receiverContract6);
                allRunFor(spec, prepareTokensAndBalances(sender1, receiverContract1, List.of(token1), List.of()));
                allRunFor(spec, prepareTokensAndBalances(sender2, receiverContract1, List.of(token2), List.of()));
                allRunFor(spec, prepareTokensAndBalances(sender3, receiverContract1, List.of(token3), List.of()));
                allRunFor(spec, prepareTokensAndBalances(sender4, receiverContract1, List.of(token4), List.of()));
                allRunFor(spec, prepareTokensAndBalances(sender5, receiverContract1, List.of(token5), List.of()));
                allRunFor(
                        spec,
                        senders.stream()
                                .map(s -> s.authorizeContract(airdropContract))
                                .toArray(SpecOperation[]::new));
                multiToOneAirdrop(senders, receiverContract1, tokens, spec);
                multiToMultiAirdrop(senders, receiverContracts, tokens, spec);
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Multiple airdrops with single sender and single/multiple contract receivers")
        public Stream<DynamicTest> multipleAirdropsSingleSender(
                @NonNull
                        @Contract(
                                contract = "EmptyOne",
                                name = "receiver1",
                                isImmutable = true,
                                maxAutoAssociations = -1)
                        SpecContract receiverContract1,
                @NonNull
                        @Contract(
                                contract = "EmptyOne",
                                name = "receiver2",
                                isImmutable = true,
                                maxAutoAssociations = -1)
                        SpecContract receiverContract2,
                @NonNull
                        @Contract(
                                contract = "EmptyOne",
                                name = "receiver3",
                                isImmutable = true,
                                maxAutoAssociations = -1)
                        SpecContract receiverContract3,
                @NonNull @FungibleToken(initialSupply = 1_000_000L, name = "token1") final SpecFungibleToken token1,
                @NonNull @FungibleToken(initialSupply = 1_000_000L, name = "token2") final SpecFungibleToken token2,
                @NonNull @FungibleToken(initialSupply = 1_000_000L, name = "token3") final SpecFungibleToken token3,
                @NonNull @FungibleToken(initialSupply = 1_000_000L, name = "token4") final SpecFungibleToken token4,
                @NonNull @FungibleToken(initialSupply = 1_000_000L, name = "token5") final SpecFungibleToken token5) {
            return hapiTest(withOpContext((spec, opLog) -> {
                final var tokens = List.of(token1, token2, token3, token4, token5);
                allRunFor(spec, prepareTokensAndBalances(sender, airdropContract, tokens, List.of()));
                allRunFor(
                        spec,
                        checkForEmptyBalance(receiverContract1, tokens, List.of()),
                        checkForEmptyBalance(receiverContract2, tokens, List.of()),
                        checkForEmptyBalance(receiverContract3, tokens, List.of()));
                // reusing but for single sending account
                multiToOneAirdrop(List.of(sender, sender, sender, sender, sender), airdropContract, tokens, spec);
                oneToMultiAirdrop(
                        sender,
                        List.of(receiverContract1, receiverContract2, receiverContract3),
                        List.of(token1, token2),
                        spec);
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Airdrop token to a contract that is not associated to it with free auto association slots")
        public Stream<DynamicTest> airdropTokenToNotAssociatedContract(
                @NonNull
                        @Contract(
                                contract = "EmptyOne",
                                name = "receiver",
                                isImmutable = true,
                                maxAutoAssociations = -1)
                        SpecContract receiverContract,
                @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(spec, sender.associateTokens(token), token.treasury().transferUnitsTo(sender, 1_000L, token));
                allRunFor(spec, checkForEmptyBalance(receiverContract, List.of(token), List.of()));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiverContract, 10L)
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .via("airdropTxn"));
                allRunFor(
                        spec,
                        receiverContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)),
                        getTxnRecord("airdropTxn").hasPriority(recordWith().pendingAirdropsCount(0)),
                        receiverContract
                                .getInfo()
                                .andAssert(info -> info.has(contractWith().hasAlreadyUsedAutomaticAssociations(1))),
                        validateChargedUsdWithChild("airdropTxn", (0.123 + 0.05), 1.0));
            }));
        }

        private void oneToMultiAirdrop(
                @NonNull final SpecAccount sender,
                @NonNull final List<SpecContract> receiverContracts,
                @NonNull final List<SpecFungibleToken> tokens,
                @NonNull final HapiSpec spec) {
            allRunFor(
                    spec,
                    // Single sender to multiple contracts
                    airdropContract
                            .call(
                                    "distributeMultipleTokens",
                                    prepareTokenAddresses(spec, tokens),
                                    sender,
                                    prepareContractAddresses(spec, receiverContracts),
                                    10L)
                            .sending(450_000_000L)
                            .via("pendingAirdrops")
                            .gas(1_750_000L),
                    // airdrop fee + association fees
                    validateChargedUsdWithChild("pendingAirdrops", (0.125 + (6 * 0.05)), 1.0));
            allRunFor(
                    spec,
                    receiverContracts.stream()
                            .map(receiverContract -> checkForBalances(receiverContract, tokens, List.of()))
                            .toArray(SpecOperation[]::new));
        }

        private void multiToOneAirdrop(
                @NonNull final List<SpecAccount> senders,
                @NonNull final SpecContract receiverContract,
                @NonNull final List<SpecFungibleToken> tokens,
                @NonNull final HapiSpec spec) {
            allRunFor(
                    spec,
                    // Multiple senders to single contract
                    airdropContract
                            .call(
                                    "tokenNAmountAirdrops",
                                    prepareTokenAddresses(spec, tokens),
                                    prepareAccountAddresses(spec, senders),
                                    prepareContractAddresses(
                                            spec,
                                            receiverContract,
                                            receiverContract,
                                            receiverContract,
                                            receiverContract,
                                            receiverContract),
                                    10L)
                            .sending(450_000_000L)
                            .gas(1_750_000L)
                            .via("pendingAirdrops"),
                    // airdrop fee + association fees
                    validateChargedUsdWithChild("pendingAirdrops", (0.125 + (5 * 0.05)), 1.0),
                    checkForBalances(receiverContract, tokens, List.of()));
        }

        private void multiToMultiAirdrop(
                @NonNull final List<SpecAccount> senders,
                @NonNull final List<SpecContract> receiverContracts,
                @NonNull final List<SpecFungibleToken> tokens,
                @NonNull final HapiSpec spec) {
            allRunFor(
                    spec,
                    getContractsTokenBalance(
                            receiverContracts, tokens, SystemContractAirdropHelper::checkForEmptyBalance));
            allRunFor(
                    spec,
                    // Multiple senders to multiple contracts
                    airdropContract
                            .call(
                                    "tokenNAmountAirdrops",
                                    prepareTokenAddresses(spec, tokens),
                                    prepareAccountAddresses(spec, senders),
                                    prepareContractAddresses(spec, receiverContracts),
                                    10L)
                            .sending(450_000_000L)
                            .gas(1_750_000L)
                            .via("pendingAirdropsMulti"),
                    // airdrop fee + association fees
                    validateChargedUsdWithChild("pendingAirdropsMulti", (0.125 + (5 * 0.05)), 1.0));
            allRunFor(
                    spec,
                    getContractsTokenBalance(receiverContracts, tokens, SystemContractAirdropHelper::checkForBalances));
        }

        private SpecOperation[] getContractsTokenBalance(
                @NonNull final List<SpecContract> receiverContracts,
                @NonNull final List<SpecFungibleToken> tokens,
                @NonNull
                        final TriFunction<
                                        SpecContract,
                                        List<SpecFungibleToken>,
                                        List<SpecNonFungibleToken>,
                                        GetBalanceOperation>
                                methodChecker) {
            return IntStream.range(0, receiverContracts.size())
                    .mapToObj(i -> methodChecker.apply(receiverContracts.get(i), List.of(tokens.get(i)), List.of()))
                    .toArray(SpecOperation[]::new);
        }
    }

    @Nested
    @DisplayName("Negative test cases")
    class AirdropToContractNegativeCases {

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName(
                "Airdrop frozen token that is already associated to the receiving contract should result in failed airdrop")
        public Stream<DynamicTest> airdropFrozenToken(
                @Contract(contract = "AssociateContract", isImmutable = true) SpecContract receiverContract,
                @FungibleToken(
                                initialSupply = 1_000_000L,
                                keys = {ADMIN_KEY, FREEZE_KEY})
                        SpecFungibleToken token) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        receiverContract.getBalance().andAssert(balance -> balance.hasTinyBars(0L)),
                        sender.associateTokens(token),
                        receiverContract
                                .call("associateTokenToThisContract", token)
                                .gas(1_000_000L),
                        token.treasury().transferUnitsTo(sender, 1_000L, token),
                        tokenFreeze(token.name(), receiverContract.name()));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiverContract, 10L)
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .andAssert(txn ->
                                        txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, ACCOUNT_FROZEN_FOR_TOKEN)));
            }));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName(
                "Airdrop token to a contract that results in a pending state then transfer same token to the same contract should fail")
        public Stream<DynamicTest> airdropToContractWithPendingAirdrop(
                @Contract(contract = "EmptyOne", isImmutable = true, maxAutoAssociations = 0)
                        SpecContract receiverContract,
                @FungibleToken(initialSupply = 1_000_000L) SpecFungibleToken token) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        receiverContract.getBalance().andAssert(balance -> balance.hasTinyBars(0L)),
                        sender.associateTokens(token),
                        token.treasury().transferUnitsTo(sender, 1_000L, token));
                allRunFor(
                        spec,
                        airdropContract
                                .call("tokenAirdrop", token, sender, receiverContract, 10L)
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .via("pendingAirdrop"));
                allRunFor(
                        spec,
                        getTxnRecord("pendingAirdrop")
                                .hasChildRecords(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(
                                                prepareFTAirdrops(sender, receiverContract, List.of(token))
                                                        .toArray(TokenMovement[]::new)))));
                allRunFor(
                        spec,
                        cryptoTransfer(moving(10, token.name()).between(sender.name(), receiverContract.name()))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
            }));
        }

        @HapiTest
        @DisplayName(
                "Transfer token to a contract not associated to it with no available auto association slots should fail")
        public Stream<DynamicTest> transferToContractWithNoFreeSlotsShouldFail(
                @Contract(contract = "EmptyOne", isImmutable = true, maxAutoAssociations = 0)
                        SpecContract receiverContract,
                @FungibleToken(initialSupply = 1_000_000L) SpecFungibleToken token) {
            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        receiverContract.getBalance().andAssert(balance -> balance.hasTinyBars(0L)),
                        sender.associateTokens(token),
                        token.treasury().transferUnitsTo(sender, 1_000L, token));
                allRunFor(
                        spec,
                        cryptoTransfer(moving(10, token.name()).between(sender.name(), receiverContract.name()))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
            }));
        }

        @LeakyHapiTest(overrides = {"entities.unlimitedAutoAssociationsEnabled"})
        @DisplayName(
                "Airdrop token to a hollow account that would create pending airdrop then deploy a contract on the same address")
        public Stream<DynamicTest> airdropToHollowAccThenCreate2OnSameAddress(
                @FungibleToken(initialSupply = 1_000_000L) SpecFungibleToken token) {
            final var create2Contract = "Create2Factory";
            final AtomicReference<String> factoryEvmAddress = new AtomicReference<>();
            final AtomicReference<byte[]> testContractInitcode = new AtomicReference<>();
            final AtomicReference<String> expectedCreate2Address = new AtomicReference<>();
            final AtomicReference<String> hollowCreationAddress = new AtomicReference<>();

            final var salt = BigInteger.valueOf(42);
            final var creation = CREATION;
            final var tcValue = 1_234L;

            return hapiTest(withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        // We need to disable the unlimited auto associations in order to create hollow account with
                        // maxAutoAssociations != -1
                        overriding("entities.unlimitedAutoAssociationsEnabled", "false"),
                        sender.associateTokens(token),
                        uploadInitCode(create2Contract),
                        token.treasury().transferUnitsTo(sender, 1_000L, token),
                        sourcing(() -> contractCreate(create2Contract)
                                .payingWith(GENESIS)
                                .via(CREATE_2_TXN)
                                .exposingNumTo(num -> factoryEvmAddress.set(asHexedSolidityAddress(0, 0, num)))),
                        // GET BYTECODE OF THE CREATE2 CONTRACT
                        sourcing(() -> contractCallLocal(
                                        create2Contract, GET_BYTECODE, asHeadlongAddress(factoryEvmAddress.get()), salt)
                                .exposingTypedResultsTo(results -> {
                                    final var tcInitcode = (byte[]) results[0];
                                    testContractInitcode.set(tcInitcode);
                                })
                                .payingWith(GENESIS)
                                .nodePayment(ONE_HBAR)),
                        // GET THE ADDRESS WHERE THE CONTRACT WILL BE DEPLOYED
                        sourcing(() -> setExpectedCreate2Address(
                                create2Contract, salt, expectedCreate2Address, testContractInitcode)),
                        cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                        // Now create a hollow account at the desired address
                        lazyCreateAccount(creation, expectedCreate2Address, Optional.empty(), Optional.empty(), null),
                        sourcing(() -> getTxnRecord(creation)
                                .andAllChildRecords()
                                .logged()
                                .exposingCreationsTo(l -> hollowCreationAddress.set(l.get(0)))),
                        // Initial check for receiver balance
                        sourcing(() ->
                                getAccountBalance(hollowCreationAddress.get()).hasTokenBalance(token.name(), 0L)),
                        sourcing(() -> getAccountInfo(hollowCreationAddress.get())
                                .logged()
                                .has(AccountInfoAsserts.accountWith().maxAutoAssociations(0))),
                        // Airdrop token to the contract's alias
                        sourcing(() -> contractCall(
                                        airdropContract.name(),
                                        "tokenAirdrop",
                                        token.addressOn(spec.targetNetworkOrThrow()),
                                        sender.addressOn(spec.targetNetworkOrThrow()),
                                        asHeadlongAddress(expectedCreate2Address.get()),
                                        10L)
                                .sending(85_000_000L)
                                .gas(1_500_000L)
                                .via("pendingFTAirdrop")),
                        // Check for the pending airdrop records
                        sourcing(() -> getTxnRecord("pendingFTAirdrop")
                                .logged()
                                .hasChildRecords(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(moving(10, token.name())
                                                .between(sender.name(), hollowCreationAddress.get()))))),
                        sourcing(() -> contractCall(create2Contract, DEPLOY, testContractInitcode.get(), salt)
                                .payingWith(GENESIS)
                                .gas(10_000_000L)
                                .sending(tcValue)
                                .via(CREATE_2_TXN)),
                        // Check for receiver balance again
                        sourcing(() ->
                                getAccountBalance(hollowCreationAddress.get()).hasTokenBalance(token.name(), 0L)));
                overriding("entities.unlimitedAutoAssociationsEnabled", "true");
            }));
        }
    }
}
