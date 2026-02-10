// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.NonFungibleTransfers.changingNFTBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.FULL_PREFIXES;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.handleAnyRepeatableQueryPayment;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateSingleton;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getEcdsaPrivateKeyFromSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getEd25519PrivateKeyFromSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.SignatureGenerator;
import com.hedera.node.app.service.contract.impl.utils.SystemContractUtils;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.TrieSigMapGenerator;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.utils.Signing;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(1)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableIntegrationTests {
    @Contract(contract = "HRC755Contract", creationGas = 1_000_000, isImmutable = true)
    static SpecContract SIGNING_CONTRACT;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(SIGNING_CONTRACT.getInfo());
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    Stream<DynamicTest> burnAtStakePeriodBoundaryHasExpectedRecord(
            @NonFungibleToken(numPreMints = 2) SpecNonFungibleToken nft) {
        return hapiTest(
                nft.getInfo(),
                doWithStartupConfig(
                        "staking.periodMins", value -> waitUntilStartOfNextStakingPeriod(Long.parseLong(value))),
                burnToken(nft.name(), List.of(1L)).via("burn"),
                getTxnRecord("burn")
                        .hasPriority(recordWith()
                                .tokenTransfers(changingNFTBalances()
                                        .including(nft.name(), nft.treasury().name(), "0.0.0", 1L))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> senderSignatureValidatedInQueries() {
        final var PAYER = "payer";
        final var SENDER = "sender";
        final var SENDER_BALANCE = ONE_MILLION_HBARS;
        final var badPayment = cryptoTransfer(tinyBarsFromTo(SENDER, "3", ONE_HUNDRED_HBARS))
                .payingWith(PAYER)
                .signedBy(PAYER);
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(SENDER).balance(SENDER_BALANCE),
                getAccountInfo(SENDER)
                        .withPayment(badPayment)
                        .hasAnswerOnlyPrecheck(INVALID_SIGNATURE)
                        .logged(),
                handleAnyRepeatableQueryPayment(),
                getAccountBalance(SENDER).hasTinyBars(SENDER_BALANCE));
    }

    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    Stream<DynamicTest> gasThrottleMimicsThroughputThrottleCongestionStatus() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER),
                sourcingContextual(spec -> mutateSingleton(
                        CongestionThrottleService.NAME,
                        SingletonType.CONGESTIONTHROTTLESERVICE_I_THROTTLE_USAGE_SNAPSHOTS.protoOrdinal(),
                        (ThrottleUsageSnapshots usageSnapshots) -> usageSnapshots
                                .copyBuilder()
                                .gasThrottle(ThrottleUsageSnapshot.newBuilder()
                                        .lastDecisionTime(asTimestamp(spec.consensusTime()))
                                        .used(1L)
                                        .build())
                                .build())),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN_PAYER, FUNDING, 1)).payingWith(CIVILIAN_PAYER),
                viewSingleton(
                        CongestionThrottleService.NAME,
                        SingletonType.CONGESTIONTHROTTLESERVICE_I_THROTTLE_USAGE_SNAPSHOTS.protoOrdinal(),
                        (ThrottleUsageSnapshots usageSnapshots) -> assertEquals(
                                0, usageSnapshots.gasThrottleOrThrow().used())));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> classifiableTakesPriorityOverUnclassifiable() {
        return hapiTest(
                cryptoCreate("civilian").balance(100 * 100_000_000L),
                usableTxnIdNamed("txnId").payerId("civilian"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 100_000_000L)),
                uncheckedSubmit(cryptoCreate("nope")
                        .txnId("txnId")
                        .payingWith("civilian")
                        .setNode("4")),
                uncheckedSubmit(cryptoCreate("sure")
                        .txnId("txnId")
                        .payingWith("civilian")
                        .setNode("3")),
                getReceipt("txnId")
                        .andAnyDuplicates()
                        .hasPriorityStatus(SUCCESS)
                        .hasDuplicateStatuses(INVALID_NODE_ACCOUNT),
                getTxnRecord("txnId")
                        .assertingNothingAboutHashes()
                        .andAnyDuplicates()
                        .hasPriority(recordWith().status(SUCCESS))
                        .hasDuplicates(inOrder(recordWith().status(INVALID_NODE_ACCOUNT))));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> controllingContractCanTriggerExecutionViaSystemContract() {
        final AtomicReference<ScheduleID> scheduleIdRef = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("contractAccount")
                        .balance(ONE_HUNDRED_HBARS)
                        .keyShape(KeyShape.CONTRACT.signedWith(SIGNING_CONTRACT.name())),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo("contractAccount", FUNDING, 1)))
                        .exposingCreatedIdTo(scheduleIdRef::set),
                getScheduleInfo("schedule").isNotExecuted(),
                sourcingContextual(spec -> SIGNING_CONTRACT
                        .call(
                                "authorizeScheduleCall",
                                mirrorAddrWith(spec, scheduleIdRef.get().getScheduleNum()))
                        .gas(1_000_000L)),
                getScheduleInfo("schedule").isExecuted());
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> contractCanTriggerExecutionForItsOwnAssets() {
        final AtomicReference<ScheduleID> scheduleIdRef = new AtomicReference<>();
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, SIGNING_CONTRACT.name(), ONE_HUNDRED_HBARS)),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo(SIGNING_CONTRACT.name(), FUNDING, 1)))
                        .exposingCreatedIdTo(scheduleIdRef::set),
                getScheduleInfo("schedule").isNotExecuted(),
                sourcingContextual(spec -> SIGNING_CONTRACT
                        .call(
                                "authorizeScheduleCall",
                                mirrorAddrWith(spec, scheduleIdRef.get().getScheduleNum()))
                        .gas(1_000_000L)),
                getScheduleInfo("schedule").isExecuted());
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> contractSignatureIsNoopIfNotControlling() {
        final AtomicReference<ScheduleID> scheduleIdRef = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("account"),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo("account", FUNDING, 1)))
                        .exposingCreatedIdTo(scheduleIdRef::set),
                getScheduleInfo("schedule").isNotExecuted(),
                sourcingContextual(spec -> SIGNING_CONTRACT
                        .call(
                                "authorizeScheduleCall",
                                mirrorAddrWith(spec, scheduleIdRef.get().getScheduleNum()))
                        .gas(1_000_000L)),
                getScheduleInfo("schedule").isNotExecuted());
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> signSystemContractAppendsFromAddressSignature() {
        final AtomicReference<ScheduleID> scheduleIdRef = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("account"),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo("account", FUNDING, 1)))
                        .exposingCreatedIdTo(scheduleIdRef::set),
                getScheduleInfo("schedule").isNotExecuted(),
                sourcingContextual(spec -> contractCallWithFunctionAbi(
                                String.valueOf(scheduleIdRef.get().getScheduleNum()),
                                getABIFor(FUNCTION, "signSchedule", "IHRC755"))
                        .payingWith("account")
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(FULL_PREFIXES))
                        .gas(1_000_000)),
                getScheduleInfo("schedule").isExecuted());
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> unrelatedCallToSignSystemContractIsNoop() {
        final AtomicReference<ScheduleID> scheduleIdRef = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("account"),
                cryptoCreate("nobody"),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo("account", FUNDING, 1)))
                        .exposingCreatedIdTo(scheduleIdRef::set),
                getScheduleInfo("schedule").isNotExecuted(),
                sourcingContextual(spec -> contractCallWithFunctionAbi(
                                String.valueOf(scheduleIdRef.get().getScheduleNum()),
                                getABIFor(FUNCTION, "signSchedule", "IHRC755"))
                        .payingWith("nobody")
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(FULL_PREFIXES))
                        .gas(1_000_000)),
                getScheduleInfo("schedule").isNotExecuted());
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> signScheduleRevertsOnUnrelatedEd25519SigAndAppendsRelatedEcdsaSig() {
        final AtomicReference<ScheduleID> scheduleIdRef = new AtomicReference<>();
        return hapiTest(
                newKeyNamed("ecdsa").shape(SECP_256K1_SHAPE),
                newKeyNamed("ed25519").shape(ED25519),
                cryptoCreate("account").key("ecdsa"),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo("account", FUNDING, 1)))
                        .exposingCreatedIdTo(scheduleIdRef::set),
                getScheduleInfo("schedule").isNotExecuted(),
                addEd25519SigViaContractAndExpect(scheduleIdRef, "ed25519", CONTRACT_REVERT_EXECUTED),
                // Unrelated key isn't added to the schedule's signatories
                getScheduleInfo("schedule").isNotExecuted().hasSignatories(DEFAULT_PAYER),
                addEcdsaSigViaContractAndExpect(scheduleIdRef, "ecdsa", SUCCESS),
                getScheduleInfo("schedule").isExecuted().hasSignatories(DEFAULT_PAYER, "ecdsa"));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> signScheduleRevertsOnUnrelatedEcdsaSigAndAppendsRelatedEd25519Sig() {
        final AtomicReference<ScheduleID> scheduleIdRef = new AtomicReference<>();
        return hapiTest(
                newKeyNamed("ecdsa").shape(SECP_256K1_SHAPE),
                newKeyNamed("ed25519").shape(ED25519),
                cryptoCreate("account").key("ed25519"),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo("account", FUNDING, 1)))
                        .exposingCreatedIdTo(scheduleIdRef::set),
                getScheduleInfo("schedule").isNotExecuted(),
                addEcdsaSigViaContractAndExpect(scheduleIdRef, "ecdsa", CONTRACT_REVERT_EXECUTED),
                // Unrelated key isn't added to the schedule's signatories
                getScheduleInfo("schedule").isNotExecuted().hasSignatories(DEFAULT_PAYER),
                addEd25519SigViaContractAndExpect(scheduleIdRef, "ed25519", SUCCESS),
                getScheduleInfo("schedule").isExecuted().hasSignatories(DEFAULT_PAYER, "ed25519"));
    }

    private static @NonNull CustomSpecAssert addEd25519SigViaContractAndExpect(
            @NonNull final AtomicReference<ScheduleID> scheduleIdRef,
            @NonNull final String keyName,
            @NonNull final ResponseCodeEnum status) {
        return withOpContext((spec, opLog) -> {
            final var message =
                    SystemContractUtils.messageFromScheduleId(CommonPbjConverters.toPbj(scheduleIdRef.get()));
            final var privateKey = getEd25519PrivateKeyFromSpec(spec, keyName);
            final var publicKey = spec.registry().getKey(keyName).getEd25519();
            final var signedBytes = SignatureGenerator.signBytes(message.toByteArray(), privateKey);
            final var signatureMap = SignatureMap.newBuilder()
                    .sigPair(SignaturePair.newBuilder()
                            .ed25519(Bytes.wrap(signedBytes))
                            .pubKeyPrefix(Bytes.wrap(publicKey.toByteArray()))
                            .build())
                    .build();
            allRunFor(
                    spec,
                    contractCall(
                                    SIGNING_CONTRACT.name(),
                                    "signScheduleCall",
                                    mirrorAddrWith(spec, scheduleIdRef.get().getScheduleNum()),
                                    SignatureMap.PROTOBUF.toBytes(signatureMap).toByteArray())
                            .gas(2_000_000L)
                            .hasKnownStatus(status));
        });
    }

    private static @NonNull CustomSpecAssert addEcdsaSigViaContractAndExpect(
            @NonNull final AtomicReference<ScheduleID> scheduleIdRef,
            @NonNull final String keyName,
            @NonNull final ResponseCodeEnum status) {
        return withOpContext((spec, opLog) -> {
            final var message =
                    SystemContractUtils.messageFromScheduleId(CommonPbjConverters.toPbj(scheduleIdRef.get()));
            final var messageHash = new Keccak.Digest256().digest(message.toByteArray());
            final var privateKey = getEcdsaPrivateKeyFromSpec(spec, keyName);
            final var publicKey = spec.registry().getKey(keyName).getECDSASecp256K1();
            final var signedBytes = Signing.signMessage(messageHash, privateKey);
            final var signatureMap = SignatureMap.newBuilder()
                    .sigPair(SignaturePair.newBuilder()
                            .ecdsaSecp256k1(Bytes.wrap(signedBytes))
                            .pubKeyPrefix(Bytes.wrap(publicKey.toByteArray()))
                            .build())
                    .build();
            allRunFor(
                    spec,
                    contractCall(
                                    SIGNING_CONTRACT.name(),
                                    "signScheduleCall",
                                    mirrorAddrWith(spec, scheduleIdRef.get().getScheduleNum()),
                                    SignatureMap.PROTOBUF.toBytes(signatureMap).toByteArray())
                            .gas(2_000_000L)
                            .hasKnownStatus(status));
        });
    }
}
