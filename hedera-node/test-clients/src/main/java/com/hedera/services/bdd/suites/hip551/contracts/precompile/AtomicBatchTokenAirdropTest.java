// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts.precompile;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.dsl.contracts.TokenRedirectContract.HRC904;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hederahashgraph.api.proto.java.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;

@OrderedInIsolation
@HapiTestLifecycle
public class AtomicBatchTokenAirdropTest {

    private static final String DEFAULT_BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        // enable atomic batch
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled", "true",
                "atomicBatch.maxNumberOfTransactions", "50",
                "contracts.throttle.throttleByGas", "false"));
        // create default batch operator
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @Contract(contract = "Airdrop", creationGas = 5_000_000)
    static SpecContract airdropContract;

    /**
     * AirdropFromContractTest
     */
    @Order(1)
    @HapiTest
    @DisplayName("Atomic Contract Airdrops a token to a receiver who is associated to the token")
    public Stream<DynamicTest> atomicAirdropTokenToAccount(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
        return hapiTest(
                sender.authorizeContract(airdropContract),
                sender.associateTokens(token),
                airdropContract.associateTokens(token),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                token.treasury().transferUnitsTo(sender, 1_000L, token),
                airdropContract
                        .call("tokenAirdrop", token, sender, receiver, 10L)
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .sending(85_000_000L)
                        .gas(1_500_000L)
                        .via("AirdropTxn"),
                getTxnRecord("AirdropTxn").hasPriority(recordWith().pendingAirdropsCount(0)),
                receiver.getBalance()
                        .andAssert(balance -> balance.hasTokenBalance(token.name(), 10L))
                        .andAssert(balance -> balance.hasTinyBars(100L)),
                receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(1)));
    }

    /**
     * AirdropSystemContractTest
     */
    @Order(2)
    @HapiTest
    @DisplayName("Atomic Airdrop token")
    public Stream<DynamicTest> atomicAirdropToken(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 10_000L * ONE_MILLION_HBARS)
                    final SpecAccount sender,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
        return hapiTest(
                sender.authorizeContract(airdropContract),
                sender.transferHBarsTo(airdropContract, 5_000_000_000L),
                sender.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 500_000L, token),
                airdropContract
                        .call("tokenAirdrop", token, sender, receiver, 10L)
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .gas(1500000),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)));
    }

    /**
     * AirdropToContractSystemContractTest
     */
    @Order(3)
    @HapiTest
    @DisplayName("Can atomic airdrop fungible token to a contract that is already associated to it")
    public Stream<DynamicTest> atomicAirdropToContract(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 10_000L * ONE_MILLION_HBARS)
                    final SpecAccount sender,
            @NonNull @Contract(contract = "AssociateContract", isImmutable = true, creationGas = 3_000_000)
                    final SpecContract receiverContract,
            @NonNull @FungibleToken(initialSupply = 1000L) final SpecFungibleToken token) {
        return hapiTest(
                sender.authorizeContract(airdropContract),
                sender.transferHBarsTo(airdropContract, 5_000_000_000L),
                receiverContract.call("associateTokenToThisContract", token).gas(1_000_000L),
                sender.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 1000L, token),
                airdropContract
                        .call("tokenAirdrop", token, sender, receiverContract, 10L)
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .gas(1500000),
                receiverContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)));
    }

    /**
     * HRCSetUnlimitedAutoAssociationsTest
     */
    @Order(4)
    @HapiTest
    public Stream<DynamicTest> atomicHrcSetUnlimitedAutoAssociations() {
        final AtomicReference<AccountID> accountNum = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("account")
                        .balance(100 * ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(0)
                        .exposingCreatedIdTo(accountNum::set),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatch(contractCallWithFunctionAbi(
                                                String.valueOf(accountNum.get().getAccountNum()),
                                                getABIFor(FUNCTION, "setUnlimitedAutomaticAssociations", "HRC904"),
                                                true)
                                        .via("setUnlimitedAutoAssociations")
                                        .payingWith("account")
                                        .gas(1_000_000L)
                                        .batchKey(DEFAULT_BATCH_OPERATOR))
                                .payingWith(DEFAULT_BATCH_OPERATOR),
                        getTxnRecord("setUnlimitedAutoAssociations")
                                .logged()
                                .hasPriority(recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(
                                                                FUNCTION,
                                                                "setUnlimitedAutomaticAssociations",
                                                                "HRC904"),
                                                        isLiteralResult(new Object[] {Long.valueOf(22)})))),
                        getAccountInfo("account").hasMaxAutomaticAssociations(-1))));
    }

    /**
     * HRCTokenCancelTest
     */
    @Order(5)
    @HapiTest
    @DisplayName("Can cancel atomic airdrop of fungible token")
    public Stream<DynamicTest> canCancelAtomicAirdropOfFungibleToken(
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token,
            @NonNull @Account(tinybarBalance = 10_000L * ONE_MILLION_HBARS) final SpecAccount sender,
            @NonNull @Account(maxAutoAssociations = 0) final SpecAccount receiver) {
        return hapiTest(
                sender.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 10L, token),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                tokenAirdrop(moving(10L, token.name()).between(sender.name(), receiver.name()))
                        .payingWith(sender.name()),
                token.call(HRC904, "cancelAirdropFT", receiver)
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .with(call -> call.payingWith(sender.name())));
    }

    /**
     * HRCTokenClaimTest
     */
    @Order(6)
    @HapiTest
    @DisplayName("Can claim atomic airdrop of fungible token")
    public Stream<DynamicTest> canClaimAtomicAirdropOfFungibleToken(
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token,
            @NonNull @Account(tinybarBalance = ONE_HBAR) final SpecAccount sender,
            @NonNull @Account(tinybarBalance = ONE_HBAR) final SpecAccount receiver) {
        return hapiTest(
                sender.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 10L, token),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                tokenAirdrop(moving(10L, token.name()).between(sender.name(), receiver.name()))
                        .payingWith(sender.name()),
                token.call(HRC904, "claimAirdropFT", sender)
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .payingWith(receiver)
                        .with(call -> call.signingWith(receiver.name())),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)));
    }

    /**
     * HRCTokenRejectTest
     */
    @Order(7)
    @HapiTest
    @DisplayName("Atomic HRC rejectTokenFT works")
    public Stream<DynamicTest> atomicHrcFungibleWorks(
            @NonNull @FungibleToken(initialSupply = 1000) SpecFungibleToken token,
            @NonNull @Account(tinybarBalance = ONE_HBAR) final SpecAccount sender) {
        return hapiTest(
                sender.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 10L, token),
                token.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 990L)),
                sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)),
                token.call(HRC904, "rejectTokenFT")
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .with(call -> call.payingWith(sender.name())),
                sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                token.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1000L)));
    }

    /**
     * TokenCancelAirdropSystemContractTest
     */
    @Order(8)
    @HapiTest
    @DisplayName("Atomic Can cancel 1 fungible airdrop")
    public Stream<DynamicTest> atomicCancelAirdrop(
            @NonNull @Contract(contract = "CancelAirdrop", creationGas = 2_000_000L) final SpecContract cancelAirdrop,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token,
            @NonNull @Account(tinybarBalance = ONE_HBAR) final SpecAccount sender,
            @NonNull @Account(tinybarBalance = ONE_HBAR) final SpecAccount receiver) {
        return hapiTest(
                sender.authorizeContract(cancelAirdrop),
                sender.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 1000, token),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0)),
                tokenAirdrop(moving(10, token.name()).between(sender.name(), receiver.name()))
                        .payingWith(sender.name())
                        .via("tokenAirdrop"),
                getTxnRecord("tokenAirdrop")
                        .hasPriority(recordWith()
                                .pendingAirdrops(includingFungiblePendingAirdrop(
                                        moving(10, token.name()).between(sender.name(), receiver.name())))),
                cancelAirdrop
                        .call("cancelAirdrop", sender, receiver, token)
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .payingWith(sender)
                        .via("cancelAirdrop"),
                getTxnRecord("cancelAirdrop").hasPriority(recordWith().pendingAirdropsCount(0)));
    }

    /**
     * TokenClaimAirdropSystemContractTest
     */
    @Order(9)
    @HapiTest
    @DisplayName("Atomic can claim 1 fungible airdrop")
    public Stream<DynamicTest> atomicClaimAirdrop(
            @NonNull @Contract(contract = "ClaimAirdrop", creationGas = 2_000_000L) final SpecContract claimAirdrop,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token,
            @NonNull @Account(tinybarBalance = ONE_HBAR) final SpecAccount sender,
            @NonNull @Account(tinybarBalance = ONE_HBAR) final SpecAccount receiver) {

        return hapiTest(
                sender.authorizeContract(claimAirdrop),
                receiver.authorizeContract(claimAirdrop),
                sender.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 1000, token),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0)),
                tokenAirdrop(moving(10, token.name()).between(sender.name(), receiver.name()))
                        .payingWith(sender.name())
                        .via("tokenAirdrop"),
                getTxnRecord("tokenAirdrop")
                        .hasPriority(recordWith()
                                .pendingAirdrops(includingFungiblePendingAirdrop(
                                        moving(10, token.name()).between(sender.name(), receiver.name())))),
                claimAirdrop
                        .call("claim", sender, receiver, token)
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .payingWith(receiver)
                        .via("claimAirdrop"),
                getTxnRecord("claimAirdrop").hasPriority(recordWith().pendingAirdropsCount(0)),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10)));
    }

    /**
     * TokenRejectSystemContractTest
     */
    @Order(10)
    @HapiTest
    @DisplayName("Atomic reject fungible token")
    public Stream<DynamicTest> atomicTokenRejectSystemContractTest(
            @NonNull @Contract(contract = "TokenReject", creationGas = 1_000_000L) final SpecContract tokenReject,
            @NonNull @FungibleToken(initialSupply = 1000) final SpecFungibleToken token,
            @NonNull @Account(tinybarBalance = ONE_HBAR) final SpecAccount sender) {
        return hapiTest(sender.authorizeContract(tokenReject), withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(token),
                    token.treasury().transferUnitsTo(sender, 100, token),
                    token.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 900L)));
            final var tokenAddress = token.addressOn(spec.targetNetworkOrThrow());
            allRunFor(
                    spec,
                    tokenReject
                            .call("rejectTokens", sender, new Address[] {tokenAddress}, new Address[0])
                            .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                    token.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1000L)));
        }));
    }
}
