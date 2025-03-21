// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getEcdsaPrivateKeyFromSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getEd25519PrivateKeyFromSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.RECEIVER;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.SENDER;
import static com.hedera.services.bdd.suites.contract.precompile.CreatePrecompileSuite.ECDSA_KEY;
import static com.hedera.services.bdd.suites.contract.precompile.CreatePrecompileSuite.ED25519KEY;

import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.node.app.hapi.utils.SignatureGenerator;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableReason;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.RepeatableKeyGenerator;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.utils.Signing;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("Contract sign schedule")
@HapiTestLifecycle
public class ContractSignScheduleTest {
    private static final String CONTRACT = "HRC755Contract";
    private static final String AUTHORIZE_SCHEDULE_CALL = "authorizeScheduleCall";
    private static final String SIGN_SCHEDULE_CALL = "signScheduleCall";

    @Nested
    @DisplayName("Authorize schedule from EOA controlled by contract")
    class AuthorizeScheduleFromEOATest {
        private static final String SCHEDULE_A = "testScheduleA";
        private static final String SCHEDULE_B = "testScheduleB";
        private static final String CONTRACT_CONTROLLED = "contractControlled";
        private static final AtomicReference<ScheduleID> scheduleID_A = new AtomicReference<>();
        private static final AtomicReference<ScheduleID> scheduleID_B = new AtomicReference<>();

        @BeforeAll
        static void beforeAll(final TestLifecycle testLifecycle) {
            testLifecycle.doAdhoc(
                    overriding("contracts.systemContract.scheduleService.enabled", "true"),
                    overriding("contracts.systemContract.scheduleService.authorizeSchedule.enabled", "true"),
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER),
                    uploadInitCode(CONTRACT),
                    contractCreate(CONTRACT),
                    cryptoCreate(CONTRACT_CONTROLLED).keyShape(KeyShape.CONTRACT.signedWith(CONTRACT)),
                    cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS).between(GENESIS, CONTRACT_CONTROLLED)),
                    scheduleCreate(SCHEDULE_A, cryptoTransfer(tinyBarsFromTo(CONTRACT_CONTROLLED, RECEIVER, 1)))
                            .exposingCreatedIdTo(scheduleID_A::set),
                    scheduleCreate(SCHEDULE_B, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                            .exposingCreatedIdTo(scheduleID_B::set));
        }

        @HapiTest
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Signature executes schedule transaction")
        final Stream<DynamicTest> authorizeScheduleWithContract() {
            return hapiTest(
                    getScheduleInfo(SCHEDULE_A).isNotExecuted(),
                    contractCall(
                                    CONTRACT,
                                    AUTHORIZE_SCHEDULE_CALL,
                                    mirrorAddrWith(scheduleID_A.get().getScheduleNum()))
                            .gas(1_000_000L),
                    sleepFor(1_000),
                    getScheduleInfo(SCHEDULE_A).isExecuted());
        }

        @HapiTest
        @DisplayName("Signature does not executes schedule transaction")
        final Stream<DynamicTest> authorizeScheduleWithContractNoExec() {
            return hapiTest(
                    getScheduleInfo(SCHEDULE_B).isNotExecuted(),
                    contractCall(
                                    CONTRACT,
                                    AUTHORIZE_SCHEDULE_CALL,
                                    mirrorAddrWith(scheduleID_B.get().getScheduleNum()))
                            .gas(1_000_000L),
                    getScheduleInfo(SCHEDULE_B).isNotExecuted());
        }
    }

    @Nested
    @DisplayName("Authorize schedule from contract")
    class AuthorizeScheduleFromContractTest {
        private static final String SCHEDULE_C = "testScheduleC";
        private static final String SCHEDULE_D = "testScheduleD";
        private static final AtomicReference<ScheduleID> scheduleID_C = new AtomicReference<>();
        private static final AtomicReference<ScheduleID> scheduleID_D = new AtomicReference<>();

        @BeforeAll
        static void beforeAll(final TestLifecycle testLifecycle) {
            testLifecycle.doAdhoc(
                    overriding("contracts.systemContract.scheduleService.enabled", "true"),
                    overriding("contracts.systemContract.scheduleService.authorizeSchedule.enabled", "true"),
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER),
                    uploadInitCode(CONTRACT),
                    // For whatever reason, omitting the admin key sets the admin key to the contract key
                    contractCreate(CONTRACT).omitAdminKey(),
                    cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS).between(GENESIS, CONTRACT)),
                    scheduleCreate(SCHEDULE_C, cryptoTransfer(tinyBarsFromTo(CONTRACT, RECEIVER, 1)))
                            .exposingCreatedIdTo(scheduleID_C::set),
                    scheduleCreate(SCHEDULE_D, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                            .exposingCreatedIdTo(scheduleID_D::set));
        }

        @HapiTest
        @DisplayName("Signature executes schedule transaction")
        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        final Stream<DynamicTest> authorizeScheduleWithContract() {
            return hapiTest(
                    getScheduleInfo(SCHEDULE_C).isNotExecuted(),
                    contractCall(
                                    CONTRACT,
                                    AUTHORIZE_SCHEDULE_CALL,
                                    mirrorAddrWith(scheduleID_C.get().getScheduleNum()))
                            .gas(1_000_000L),
                    sleepFor(1000L),
                    getScheduleInfo(SCHEDULE_C).isExecuted());
        }

        @HapiTest
        @DisplayName("Signature does not executes schedule transaction")
        final Stream<DynamicTest> authorizeScheduleWithContractNoExec() {
            return hapiTest(
                    getScheduleInfo(SCHEDULE_D).isNotExecuted(),
                    contractCall(
                                    CONTRACT,
                                    AUTHORIZE_SCHEDULE_CALL,
                                    mirrorAddrWith(scheduleID_D.get().getScheduleNum()))
                            .gas(1_000_000L),
                    getScheduleInfo(SCHEDULE_D).isNotExecuted());
        }
    }

    @Nested
    @DisplayName("Sign Schedule From EOA")
    class SignScheduleFromEOATest {
        private static final AtomicReference<ScheduleID> scheduleID_E = new AtomicReference<>();
        private static final AtomicReference<ScheduleID> scheduleID_F = new AtomicReference<>();
        private static final String OTHER_SENDER = "otherSender";
        private static final String SIGN_SCHEDULE = "signSchedule";
        private static final String IHRC755 = "IHRC755";
        private static final String SCHEDULE_E = "testScheduleE";
        private static final String SCHEDULE_F = "testScheduleF";

        @BeforeAll
        static void beforeAll(final TestLifecycle testLifecycle) {
            testLifecycle.doAdhoc(
                    overriding("contracts.systemContract.scheduleService.enabled", "true"),
                    overriding("contracts.systemContract.scheduleService.signSchedule.enabled", "true"),
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OTHER_SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                    scheduleCreate(SCHEDULE_E, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                            .exposingCreatedIdTo(scheduleID_E::set),
                    scheduleCreate(SCHEDULE_F, cryptoTransfer(tinyBarsFromTo(OTHER_SENDER, RECEIVER, 1)))
                            .exposingCreatedIdTo(scheduleID_F::set));
        }

        @HapiTest
        @DisplayName("Signature executes schedule transaction")
        final Stream<DynamicTest> authorizeScheduleWithContract() {
            var scheduleAddress = asEntityString(scheduleID_E.get().getScheduleNum());
            return hapiTest(
                    getScheduleInfo(SCHEDULE_E).isNotExecuted(),
                    contractCallWithFunctionAbi(
                                    scheduleAddress,
                                    getABIFor(
                                            com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                            SIGN_SCHEDULE,
                                            IHRC755))
                            .payingWith(SENDER)
                            .gas(1_000_000),
                    getScheduleInfo(SCHEDULE_E).isExecuted());
        }

        @HapiTest
        @DisplayName("Signature does not executes schedule transaction")
        final Stream<DynamicTest> authorizeScheduleWithContractNoExec() {
            var scheduleAddress = "0.0." + scheduleID_F.get().getScheduleNum();
            return hapiTest(
                    getScheduleInfo(SCHEDULE_F).isNotExecuted(),
                    contractCallWithFunctionAbi(
                                    scheduleAddress,
                                    getABIFor(
                                            com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                            SIGN_SCHEDULE,
                                            IHRC755))
                            .payingWith(SENDER)
                            .gas(1_000_000),
                    getScheduleInfo(SCHEDULE_F).isNotExecuted());
        }
    }

    @Nested
    @DisplayName("Sign Schedule From Contract")
    class SignScheduleFromContractTest {
        private static final AtomicReference<ScheduleID> scheduleID_G = new AtomicReference<>();
        private static final AtomicReference<ScheduleID> scheduleID_H = new AtomicReference<>();
        private static final String SCHEDULE_G = "testScheduleG";
        private static final String SCHEDULE_H = "testScheduleH";
        private static final String ED_SENDER = "EdSender";
        private static final String ECDSA_RECEIVER_KEY = "EcdsaReceiverKey";
        private static final String ED_RECEIVER = "EdReceiver";

        @BeforeAll
        static void beforeAll(final TestLifecycle testLifecycle) {
            testLifecycle.doAdhoc(
                    overriding("contracts.systemContract.scheduleService.enabled", "true"),
                    overriding("contracts.systemContract.scheduleService.signSchedule.enabled", "true"),
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    newKeyNamed(ED25519KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                    newKeyNamed(ECDSA_RECEIVER_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(ED_SENDER).balance(ONE_HUNDRED_HBARS).key(ED25519KEY),
                    cryptoCreate(ED_RECEIVER),
                    uploadInitCode(CONTRACT),
                    contractCreate(CONTRACT),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                    scheduleCreate(
                                    SCHEDULE_G,
                                    cryptoTransfer(tinyBarsFromToWithAlias(ECDSA_KEY, ECDSA_RECEIVER_KEY, 1)))
                            .exposingCreatedIdTo(scheduleID_G::set),
                    scheduleCreate(SCHEDULE_H, cryptoTransfer(tinyBarsFromTo(ED_SENDER, ED_RECEIVER, 1)))
                            .exposingCreatedIdTo(scheduleID_H::set));
        }

        @HapiTest
        @DisplayName("Sign schedule using ecdsa key")
        final Stream<DynamicTest> signScheduleWithContractEcdsaKey() {
            return hapiTest(
                    getScheduleInfo(SCHEDULE_G).isNotExecuted(),
                    signWithEd(scheduleID_G, ResponseCodeEnum.CONTRACT_REVERT_EXECUTED),
                    getScheduleInfo(SCHEDULE_G).isNotExecuted().hasSignatories(GENESIS),
                    signWithEcdsa(scheduleID_G, ResponseCodeEnum.SUCCESS),
                    getScheduleInfo(SCHEDULE_G).isExecuted().hasSignatories(GENESIS, ECDSA_KEY));
        }

        @HapiTest
        @DisplayName("Sign schedule using ed key")
        final Stream<DynamicTest> signScheduleWithContractEdKey() {
            return hapiTest(
                    getScheduleInfo(SCHEDULE_H).isNotExecuted(),
                    signWithEcdsa(scheduleID_H, ResponseCodeEnum.CONTRACT_REVERT_EXECUTED),
                    getScheduleInfo(SCHEDULE_H).isNotExecuted().hasSignatories(GENESIS),
                    signWithEd(scheduleID_H, ResponseCodeEnum.SUCCESS),
                    getScheduleInfo(SCHEDULE_H).isExecuted().hasSignatories(GENESIS, ED25519KEY));
        }

        private @NonNull CustomSpecAssert signWithEd(
                final AtomicReference<ScheduleID> scheduleID, final ResponseCodeEnum expectedStatus) {
            return withOpContext((spec, opLog) -> {
                final var message = getMessageBytes(scheduleID);

                final var privateKey = getEd25519PrivateKeyFromSpec(spec, ED25519KEY);
                final var publicKey = spec.registry().getKey(ED25519KEY).getEd25519();
                final var signedBytes = SignatureGenerator.signBytes(message.toByteArray(), privateKey);

                final var signatureMap = SignatureMap.newBuilder()
                        .sigPair(SignaturePair.newBuilder()
                                .ed25519(Bytes.wrap(signedBytes))
                                .pubKeyPrefix(Bytes.wrap(publicKey.toByteArray()))
                                .build())
                        .build();

                final var signatureMapBytes =
                        SignatureMap.PROTOBUF.toBytes(signatureMap).toByteArray();

                final var call = contractCall(
                                CONTRACT,
                                SIGN_SCHEDULE_CALL,
                                mirrorAddrWith(scheduleID.get().getScheduleNum()),
                                signatureMapBytes)
                        .gas(2_000_000L)
                        .hasKnownStatus(expectedStatus);
                allRunFor(spec, call);
            });
        }

        private @NonNull CustomSpecAssert signWithEcdsa(
                final AtomicReference<ScheduleID> scheduleID, final ResponseCodeEnum expectedStatus) {
            return withOpContext((spec, opLog) -> {
                final var message = getMessageBytes(scheduleID);
                final var messageHash = new Keccak.Digest256().digest(message.toByteArray());

                final var privateKey = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                final var publicKey = spec.registry().getKey(ECDSA_KEY).getECDSASecp256K1();
                final var signedBytes = Signing.signMessage(messageHash, privateKey);

                final var signatureMap = SignatureMap.newBuilder()
                        .sigPair(SignaturePair.newBuilder()
                                .ecdsaSecp256k1(Bytes.wrap(signedBytes))
                                .pubKeyPrefix(Bytes.wrap(publicKey.toByteArray()))
                                .build())
                        .build();

                final var signatureMapBytes =
                        SignatureMap.PROTOBUF.toBytes(signatureMap).toByteArray();

                final var call = contractCall(
                                CONTRACT,
                                SIGN_SCHEDULE_CALL,
                                mirrorAddrWith(scheduleID.get().getScheduleNum()),
                                signatureMapBytes)
                        .gas(2_000_000L)
                        .hasKnownStatus(expectedStatus);
                allRunFor(spec, call);
            });
        }

        private Bytes getMessageBytes(AtomicReference<ScheduleID> scheduleID) {
            final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 3);
            buffer.putLong(scheduleID.get().getShardNum());
            buffer.putLong(scheduleID.get().getRealmNum());
            buffer.putLong(scheduleID.get().getScheduleNum());
            return Bytes.wrap(buffer.array());
        }
    }
}
