// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.batch;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPrivateKey;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ANY;
import static com.hedera.services.bdd.spec.keys.SigControl.ED25519_ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getEcdsaPrivateKeyFromSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getEd25519PrivateKeyFromSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newThresholdKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE_TYPE_MISMATCHING_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.node.app.hapi.utils.SignatureGenerator;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.RepeatableKeyGenerator;
import com.hedera.services.bdd.suites.utils.contracts.BoolResult;
import com.hedera.services.bdd.utils.Signing;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.jcajce.provider.digest.SHA384.Digest;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of IsAuthorizedTest. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.

/**
 * Tests expected behavior of the HRC-632 {@code isAuthorizedRaw(address,bytes,bytes)} function
 * when the {@code contracts.systemContract.accountService.isAuthorizedRawEnabled} feature flag is on (which is
 * true by default in  the current release.)
 */
@Tag(SMART_CONTRACT)
@HapiTestLifecycle
@SuppressWarnings("java:S1192") // "String literals should not be duplicated" - would impair readability here
public class AtomicIsAuthorizedTest {

    public static final String ACCOUNT = "account";
    public static final String ANOTHER_ACCOUNT = "anotherAccount";

    private static final String ECDSA_KEY = "ecdsaKey";
    private static final String ECDSA_KEY_ANOTHER = "ecdsaKeyAnother";
    private static final String ED25519_KEY = "ed25519Key";
    private static final String THRESHOLD_KEY = "ThreshKey";
    private static final String KEY_LIST = "ListKeys";

    private static final KeyShape THRESHOLD_KEY_SHAPE = KeyShape.threshOf(1, ED25519, SIMPLE);
    private static final KeyShape KEY_LIST_SHAPE = listOf(ED25519, SIMPLE);

    private static final String HRC632_CONTRACT = "HRC632Contract";
    private static final String CONTRACTS_SYSTEM_CONTRACT_ACCOUNT_SERVICE_IS_AUTHORIZED_RAW_ENABLED =
            "contracts.systemContract.accountService.isAuthorizedRawEnabled";
    private static final String CONTRACTS_SYSTEM_CONTRACT_ACCOUNT_SERVICE_IS_AUTHORIZED_ENABLED =
            "contracts.systemContract.accountService.isAuthorizedRawEnabled";

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled",
                "true",
                "atomicBatch.maxNumberOfTransactions",
                "50",
                "contracts.throttle.throttleByGas",
                "false",
                "cryptoCreateWithAlias.enabled",
                "false"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @Nested
    @DisplayName("IsAuthorizedRaw")
    class IsAuthorizedRawTests {

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawECDSAHappyPath() {
            return hapiTest(
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Keccak.Digest256().digest("submit".getBytes());

                        final var privateKey = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                        final var addressBytes = recoverAddressFromPrivateKey(privateKey);
                        final var signedBytes = Signing.signMessage(messageHash, privateKey);

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                asHeadlongAddress(addressBytes),
                                                messageHash,
                                                signedBytes)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        allRunFor(spec, call);
                    }),
                    getTxnRecord("authorizeCall")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(true)))));
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawECDSAInsufficientGas() {
            return hapiTest(
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Keccak.Digest256().digest("submit".getBytes());

                        final var privateKey = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                        final var addressBytes = recoverAddressFromPrivateKey(privateKey);
                        final var signedBytes = Signing.signMessage(messageHash, privateKey);

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                asHeadlongAddress(addressBytes),
                                                messageHash,
                                                signedBytes)
                                        .via("authorizeCall")
                                        .hasPrecheck(INSUFFICIENT_GAS)
                                        .gas(1L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasPrecheck(INSUFFICIENT_GAS);
                        allRunFor(spec, call);
                    }));
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawECDSADifferentHash() {
            return hapiTest(
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Keccak.Digest256().digest("submit".getBytes());
                        final var differentHash = new Keccak.Digest256().digest("submit1".getBytes());

                        final var privateKey = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                        final var addressBytes = recoverAddressFromPrivateKey(privateKey);
                        final var signedBytes = Signing.signMessage(messageHash, privateKey);

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                asHeadlongAddress(addressBytes),
                                                differentHash,
                                                signedBytes)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        allRunFor(spec, call);
                    }),
                    getTxnRecord("authorizeCall")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(false)))));
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawECDSAInvalidVValue() {
            return hapiTest(
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Keccak.Digest256().digest("submit".getBytes());

                        final var privateKey = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                        final var addressBytes = recoverAddressFromPrivateKey(privateKey);
                        final var signature = Signing.signMessage(messageHash, privateKey);
                        signature[signature.length - 1] = (byte) 2;

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                asHeadlongAddress(addressBytes),
                                                messageHash,
                                                signature)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        allRunFor(spec, call);
                    }),
                    getTxnRecord("authorizeCall")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(false)))));
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawECDSAInvalidSignatureLength() {
            return hapiTest(
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Keccak.Digest256().digest("submit".getBytes());

                        final var privateKey = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                        final var addressBytes = recoverAddressFromPrivateKey(privateKey);
                        final byte[] invalidSignature = new byte[64];

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                asHeadlongAddress(addressBytes),
                                                messageHash,
                                                invalidSignature)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);
                        allRunFor(spec, call);
                    }),
                    childRecordsCheck(
                            "authorizeCall",
                            CONTRACT_REVERT_EXECUTED,
                            recordWith().status(INVALID_SIGNATURE_TYPE_MISMATCHING_KEY)));
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawEDHappyPath() {
            final AtomicReference<Address> accountNum = new AtomicReference<>();

            return hapiTest(
                    newKeyNamed(ED25519_KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                    cryptoCreate(ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(ED25519_KEY)
                            .exposingCreatedIdTo(id -> accountNum.set(idAsHeadlongAddress(id))),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Digest().digest("submit".getBytes());

                        final var edKey = spec.registry().getKey(ED25519_KEY);
                        final var privateKey = spec.keys()
                                .getEd25519PrivateKey(
                                        CommonUtils.hex(edKey.toByteArray()).substring(4));
                        final var signedBytes = SignatureGenerator.signBytes(messageHash, privateKey);

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                accountNum.get(),
                                                messageHash,
                                                signedBytes)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        allRunFor(spec, call);
                    }),
                    getTxnRecord("authorizeCall")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(true)))));
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawEDDifferentHash() {
            final AtomicReference<Address> accountNum = new AtomicReference<>();

            return hapiTest(
                    newKeyNamed(ED25519_KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                    cryptoCreate(ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(ED25519_KEY)
                            .exposingCreatedIdTo(id -> accountNum.set(idAsHeadlongAddress(id))),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Digest().digest("submit".getBytes());
                        final var differentHash = new Digest().digest("submit1".getBytes());

                        final var edKey = spec.registry().getKey(ED25519_KEY);
                        final var privateKey = spec.keys()
                                .getEd25519PrivateKey(
                                        CommonUtils.hex(edKey.toByteArray()).substring(4));
                        final var signedBytes = SignatureGenerator.signBytes(messageHash, privateKey);

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                accountNum.get(),
                                                differentHash,
                                                signedBytes)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        allRunFor(spec, call);
                    }),
                    getTxnRecord("authorizeCall")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(false)))));
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawEDInsufficientGas() {
            final AtomicReference<Address> accountNum = new AtomicReference<>();

            return hapiTest(
                    newKeyNamed(ED25519_KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                    cryptoCreate(ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(ED25519_KEY)
                            .exposingCreatedIdTo(id -> accountNum.set(idAsHeadlongAddress(id))),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Digest().digest("submit".getBytes());

                        final var edKey = spec.registry().getKey(ED25519_KEY);
                        final var privateKey = spec.keys()
                                .getEd25519PrivateKey(
                                        CommonUtils.hex(edKey.toByteArray()).substring(4));
                        final var signedBytes = SignatureGenerator.signBytes(messageHash, privateKey);

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                accountNum.get(),
                                                messageHash,
                                                signedBytes)
                                        .via("authorizeCall")
                                        .hasPrecheck(INSUFFICIENT_GAS)
                                        .gas(1L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasPrecheck(INSUFFICIENT_GAS);
                        allRunFor(spec, call);
                    }));
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawEDInvalidSignatureAddressPairsFails() {
            final AtomicReference<Address> accountNum = new AtomicReference<>();

            return hapiTest(
                    newKeyNamed(ED25519_KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                    cryptoCreate(ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(ED25519_KEY)
                            .exposingCreatedIdTo(id -> accountNum.set(idAsHeadlongAddress(id))),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Keccak.Digest256().digest("submit".getBytes());

                        final var callECSigWithLongZero = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                accountNum.get(),
                                                messageHash,
                                                new byte[65])
                                        .via("authorizeCallECWithLongZero")
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);
                        final var callECWithInvalidSignature = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                accountNum.get(),
                                                messageHash,
                                                new byte[63])
                                        .via("authorizeCallECWithInvalidSignature")
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);
                        allRunFor(spec, callECSigWithLongZero, callECWithInvalidSignature);
                    }),
                    childRecordsCheck(
                            "authorizeCallECWithLongZero",
                            CONTRACT_REVERT_EXECUTED,
                            recordWith().status(INVALID_ACCOUNT_ID)),
                    childRecordsCheck(
                            "authorizeCallECWithInvalidSignature",
                            CONTRACT_REVERT_EXECUTED,
                            recordWith().status(INVALID_TRANSACTION_BODY)));
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawAliasWithECFails() {
            final AtomicReference<Address> accountNum = new AtomicReference<>();

            return hapiTest(
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    cryptoCreate(ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(ECDSA_KEY)
                            .exposingCreatedIdTo(id -> accountNum.set(idAsHeadlongAddress(id))),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Digest().digest("submit".getBytes());
                        final var privateKey = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                        final var signedBytes = Signing.signMessage(messageHash, privateKey);

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                accountNum.get(),
                                                messageHash,
                                                signedBytes)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);
                        allRunFor(spec, call);
                    }),
                    childRecordsCheck(
                            "authorizeCall",
                            CONTRACT_REVERT_EXECUTED,
                            recordWith().status(INVALID_TRANSACTION_BODY)));
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawEDWithComplexKeysFails() {
            final AtomicReference<Address> accountNum = new AtomicReference<>();
            final AtomicReference<Address> accountAnotherNum = new AtomicReference<>();
            return hapiTest(
                    newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ED25519_ON, ANY))),
                    newKeyNamed(KEY_LIST).shape(KEY_LIST_SHAPE.signedWith(sigs(ED25519_ON, ANY))),
                    newKeyNamed(ED25519_KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                    cryptoCreate(ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(THRESHOLD_KEY)
                            .exposingCreatedIdTo(id -> accountNum.set(idAsHeadlongAddress(id))),
                    cryptoCreate(ANOTHER_ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(KEY_LIST)
                            .exposingCreatedIdTo(id -> accountAnotherNum.set(idAsHeadlongAddress(id))),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Digest().digest("submit".getBytes());
                        final var edKey = spec.registry().getKey(ED25519_KEY);
                        final var privateKey = spec.keys()
                                .getEd25519PrivateKey(
                                        CommonUtils.hex(edKey.toByteArray()).substring(4));
                        final var signedBytes = SignatureGenerator.signBytes(messageHash, privateKey);

                        final var callWithThreshold = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                accountNum.get(),
                                                messageHash,
                                                signedBytes)
                                        .via("authorizeCallWithThreshold")
                                        .gas(2_000_000L)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);

                        final var callWithKeyList = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                accountAnotherNum.get(),
                                                messageHash,
                                                signedBytes)
                                        .via("authorizeCallWithKeyList")
                                        .gas(2_000_000L)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);
                        allRunFor(spec, callWithThreshold, callWithKeyList);
                    }),
                    childRecordsCheck(
                            "authorizeCallWithThreshold",
                            CONTRACT_REVERT_EXECUTED,
                            recordWith().status(INVALID_TRANSACTION_BODY)),
                    childRecordsCheck(
                            "authorizeCallWithKeyList",
                            CONTRACT_REVERT_EXECUTED,
                            recordWith().status(INVALID_TRANSACTION_BODY)));
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawAccountAndSignatureKeysCombinations() {
            final AtomicReference<Address> accountNum = new AtomicReference<>();

            return hapiTest(
                    newKeyNamed(ED25519_KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    newKeyNamed(ECDSA_KEY_ANOTHER).shape(SECP_256K1_SHAPE),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY_ANOTHER, ONE_HUNDRED_HBARS)),
                    cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS).key(ED25519_KEY),
                    cryptoCreate(ANOTHER_ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(ED25519_KEY)
                            .exposingCreatedIdTo(id -> accountNum.set(idAsHeadlongAddress(id))),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var messageHash = new Digest().digest("submit".getBytes());
                        final var messageHash32Bytes = new Keccak.Digest256().digest("submit".getBytes());

                        // Sign message with ED25519
                        final var edKey = spec.registry().getKey(ED25519_KEY);
                        final var privateKey = spec.keys()
                                .getEd25519PrivateKey(
                                        CommonUtils.hex(edKey.toByteArray()).substring(4));
                        final var signedBytes = SignatureGenerator.signBytes(messageHash, privateKey);

                        // Sign message with ECDSA
                        final var privateKeyECDSA = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                        final var privateKeyECDSAAnother = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY_ANOTHER);
                        final var addressBytes = recoverAddressFromPrivateKey(privateKeyECDSAAnother);
                        final var signedBytesECDSA = Signing.signMessage(messageHash32Bytes, privateKeyECDSA);

                        // Perform test calls
                        final var callECDSADifferent = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                asHeadlongAddress(addressBytes),
                                                messageHash32Bytes,
                                                signedBytesECDSA)
                                        .via("callSignatureWithDifferentAddressECDSAKey")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);

                        final var callWithDifferentAccountsWithSameKey = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                accountNum.get(),
                                                messageHash,
                                                signedBytes)
                                        .via("callWithDifferentAccountsWithSameKey")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);

                        final var callECKeyEDSignature = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedRawCall",
                                                asHeadlongAddress(addressBytes),
                                                messageHash,
                                                signedBytes)
                                        .via("callWithECKeyAddressForEDSignature")
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);
                        allRunFor(spec, callECDSADifferent, callWithDifferentAccountsWithSameKey, callECKeyEDSignature);
                    }),
                    getTxnRecord("callSignatureWithDifferentAddressECDSAKey")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(false)))),
                    getTxnRecord("callWithDifferentAccountsWithSameKey")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(true)))),
                    childRecordsCheck(
                            "callWithECKeyAddressForEDSignature",
                            CONTRACT_REVERT_EXECUTED,
                            recordWith().status(INVALID_SIGNATURE_TYPE_MISMATCHING_KEY)));
        }

        // FUTURE: Note the difference in returned status between the EC tests and the ED tests:
        // INSUFFICIENT_GAS vs CONTRACT_REVERT_EXECUTED.  This has to do with whether the tests run out
        // of gas before/after the `isAuthorizedRaw` call, or within it.  And it also shows up in whether
        // there is a child record for the method call.  The question is: Given that this difference is
        // visible to callers, is it acceptable?  Question will become more important as more system
        // contract methods gain individual gas fees.

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawECDSACheckGasRequirements() {

            // Intrinsic gas is 21_000, ECRECOVER is 3_000, but there's also the contract itself that we're calling

            record TestCase(long gasAmount, ResponseCodeEnum status) {}

            final var testCases = new ArrayList<TestCase>(List.of(new TestCase(100000, SUCCESS)));
            for (long g = 28000; g < 34000; g += 1000) {
                testCases.add(new TestCase(g, INSUFFICIENT_GAS));
            }
            for (long g = 34000; g < 34300; g += 100) {
                testCases.add(new TestCase(g, INSUFFICIENT_GAS));
            }
            for (long g = 34300; g < 35000; g += 100) {
                testCases.add(new TestCase(g, SUCCESS));
            }
            for (long g = 35000; g < 40000; g += 1000) {
                testCases.add(new TestCase(g, SUCCESS));
            }

            final var dynamicTests = new ArrayList<Stream<DynamicTest>>(testCases.size());
            for (final var testCase : testCases) {
                final var testName = "isAuthorizedRawECDSACheckGasRequirements with %d gas, expecting %s"
                        .formatted(testCase.gasAmount(), testCase.status());
                final var recordName = "authorizeCallEC-%d".formatted(testCase.gasAmount());

                final var throughWhen = defaultHapiSpec(testName)
                        .given(
                                overriding(CONTRACTS_SYSTEM_CONTRACT_ACCOUNT_SERVICE_IS_AUTHORIZED_RAW_ENABLED, "true"),
                                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                                uploadInitCode(HRC632_CONTRACT),
                                contractCreate(HRC632_CONTRACT))
                        .when(withOpContext((spec, opLog) -> {
                            final var messageHash = new Keccak.Digest256().digest("submit".getBytes());

                            final var privateKey = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                            final var addressBytes = recoverAddressFromPrivateKey(privateKey);
                            final var signedBytes = Signing.signMessage(messageHash, privateKey);

                            var call = atomicBatch(contractCall(
                                                    HRC632_CONTRACT,
                                                    "isAuthorizedRawCall",
                                                    asHeadlongAddress(addressBytes),
                                                    messageHash,
                                                    signedBytes)
                                            .via(recordName)
                                            .gas(testCase.gasAmount())
                                            .batchKey(BATCH_OPERATOR)
                                            .hasKnownStatusFrom(SUCCESS, INSUFFICIENT_GAS))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatusFrom(SUCCESS, INNER_TRANSACTION_FAILED);
                            allRunFor(spec, call);
                        }));
                final var hapiSpec = testCase.status() == SUCCESS
                        ? throughWhen.then(getTxnRecord(recordName)
                                .hasPriority(recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contractCallResult(BoolResult.flag(true)))))
                        : throughWhen.then(getTxnRecord(recordName)
                                .hasPriority(recordWith()
                                        .status(INSUFFICIENT_GAS)
                                        .contractCallResult(resultWith().gasUsed(testCase.gasAmount()))));
                dynamicTests.add(hapiSpec);
            }

            return dynamicTests.stream().reduce(Stream::concat).get();
        }

        @HapiTest
        final Stream<DynamicTest> isAuthorizedRawED25519CheckGasRequirements() {

            // Intrinsic gas is 21_000, hard-coded verification charge is 1_500_000, but there's also the contract
            // itself
            // that we're calling - allow 55K gas (actually, determined empirically)

            final long GAS_BURNT_IN_ADDITION_TO_IS_AUTHORIZED_RAW_ALLOWANCE = 55_000L;

            record TestCase(long gasAmount, ResponseCodeEnum status) {}

            final var testCases = new ArrayList<TestCase>();
            for (long g = 1_550_000; g < 1_554_000; g += 1000) {
                testCases.add(new TestCase(g, INSUFFICIENT_GAS));
            }
            for (long g = 1_554_000; g < 1_554_500; g += 100) {
                testCases.add(new TestCase(g, INSUFFICIENT_GAS));
            }
            for (long g = 1_554_500; g < 1_555_000; g += 100) {
                testCases.add(new TestCase(g, SUCCESS));
            }
            for (long g = 1_555_000; g < 1_560_000; g += 1000) {
                testCases.add(new TestCase(g, SUCCESS));
            }

            final var dynamicTests = new ArrayList<Stream<DynamicTest>>(testCases.size());
            for (final var testCase : testCases) {
                final var testName = "isAuthorizedRawED25519CheckGasRequirements with %d gas, expecting %s"
                        .formatted(testCase.gasAmount(), testCase.status());
                final var recordName = "authorizeCallED-%d".formatted(testCase.gasAmount());

                final AtomicReference<Address> accountNum = new AtomicReference<>();

                final var throughWhen = defaultHapiSpec(testName)
                        .given(
                                newKeyNamed(ED25519_KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                                cryptoCreate(ACCOUNT)
                                        .balance(ONE_MILLION_HBARS)
                                        .key(ED25519_KEY)
                                        .exposingCreatedIdTo(id -> accountNum.set(idAsHeadlongAddress(id))),
                                uploadInitCode(HRC632_CONTRACT),
                                contractCreate(HRC632_CONTRACT))
                        .when(withOpContext((spec, opLog) -> {
                            final var messageHash = new Digest().digest("submit".getBytes());

                            final var edKey = spec.registry().getKey(ED25519_KEY);
                            final var privateKey = spec.keys()
                                    .getEd25519PrivateKey(
                                            CommonUtils.hex(edKey.toByteArray()).substring(4));
                            final var signedBytes = SignatureGenerator.signBytes(messageHash, privateKey);

                            var call = atomicBatch(contractCall(
                                                    HRC632_CONTRACT,
                                                    "isAuthorizedRawCall",
                                                    accountNum.get(),
                                                    messageHash,
                                                    signedBytes)
                                            .via(recordName)
                                            .gas(testCase.gasAmount())
                                            .batchKey(BATCH_OPERATOR)
                                            .hasKnownStatusFrom(SUCCESS, CONTRACT_REVERT_EXECUTED))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatusFrom(SUCCESS, INNER_TRANSACTION_FAILED);
                            allRunFor(spec, call);
                        }));
                final var hapiSpec = testCase.status() == SUCCESS
                        ? throughWhen.then(getTxnRecord(recordName)
                                .hasPriority(recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contractCallResult(BoolResult.flag(true)))))
                        : throughWhen.then(childRecordsCheck(
                                recordName,
                                CONTRACT_REVERT_EXECUTED,
                                recordWith()
                                        .status(INSUFFICIENT_GAS)
                                        .contractCallResult(resultWith()
                                                .gasUsedIsInRange(
                                                        (testCase.gasAmount()
                                                                - GAS_BURNT_IN_ADDITION_TO_IS_AUTHORIZED_RAW_ALLOWANCE),
                                                        testCase.gasAmount()))));
                dynamicTests.add(hapiSpec);
            }

            return dynamicTests.stream().reduce(Stream::concat).get();
        }
    }

    @Nested
    @DisplayName("IsAuthorizedTests")
    class IsAuthorizedTests {

        @HapiTest
        final Stream<DynamicTest> ecdsaHappyPath() {
            return hapiTest(
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var message = "submit".getBytes();
                        final var messageHash = new Keccak.Digest256().digest("submit".getBytes());
                        final var privateKey = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                        final var publicKey = spec.registry().getKey(ECDSA_KEY).getECDSASecp256K1();
                        final var addressBytes = recoverAddressFromPrivateKey(privateKey);
                        final var signedBytes = Signing.signMessage(messageHash, privateKey);

                        final var signatureMap = SignatureMap.newBuilder()
                                .sigPair(SignaturePair.newBuilder()
                                        .ecdsaSecp256k1(Bytes.wrap(signedBytes))
                                        .pubKeyPrefix(Bytes.wrap(publicKey.toByteArray()))
                                        .build())
                                .build();

                        final var signatureMapBytes =
                                SignatureMap.PROTOBUF.toBytes(signatureMap).toByteArray();

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedCall",
                                                asHeadlongAddress(addressBytes),
                                                message,
                                                signatureMapBytes)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        allRunFor(spec, call);
                    }),
                    getTxnRecord("authorizeCall")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(true)))));
        }

        @HapiTest
        final Stream<DynamicTest> ed25519HappyPath() {
            final AtomicReference<AccountID> accountNum = new AtomicReference<>();
            return hapiTest(
                    newKeyNamed(ED25519_KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                    cryptoCreate(ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(ED25519_KEY)
                            .exposingCreatedIdTo(accountNum::set),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ED25519_KEY, ONE_HUNDRED_HBARS)),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var message = "submit".getBytes();
                        final var privateKey = getEd25519PrivateKeyFromSpec(spec, ED25519_KEY);
                        final var publicKey =
                                spec.registry().getKey(ED25519_KEY).getEd25519();
                        final var signedBytes = SignatureGenerator.signBytes(message, privateKey);
                        final var signatureMap = SignatureMap.newBuilder()
                                .sigPair(SignaturePair.newBuilder()
                                        .ed25519(Bytes.wrap(signedBytes))
                                        .pubKeyPrefix(Bytes.wrap(publicKey.toByteArray()))
                                        .build())
                                .build();
                        final var signatureMapBytes =
                                SignatureMap.PROTOBUF.toBytes(signatureMap).toByteArray();

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedCall",
                                                asHeadlongAddress(asAddress(accountNum.get())),
                                                message,
                                                signatureMapBytes)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        allRunFor(spec, call);
                    }),
                    getTxnRecord("authorizeCall")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(true)))));
        }

        @HapiTest
        final Stream<DynamicTest> keyListHappyPath() {
            final AtomicReference<AccountID> accountNum = new AtomicReference<>();
            return hapiTest(
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    newKeyNamed(ED25519_KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                    newKeyListNamed(KEY_LIST, List.of(ECDSA_KEY, ED25519_KEY)),
                    cryptoCreate(ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(KEY_LIST)
                            .exposingCreatedIdTo(accountNum::set),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ED25519_KEY, ONE_HUNDRED_HBARS)),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var message = "submit".getBytes();
                        final var messageHash = new Keccak.Digest256().digest("submit".getBytes());
                        final var privateKeyEcdsa = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                        final var publicKeyEcdsa =
                                spec.registry().getKey(ECDSA_KEY).getECDSASecp256K1();
                        final var addressBytesEcdsa = recoverAddressFromPrivateKey(privateKeyEcdsa);
                        final var signedBytesEcdsa = Signing.signMessage(messageHash, privateKeyEcdsa);

                        final var privateKeyEd = getEd25519PrivateKeyFromSpec(spec, ED25519_KEY);
                        final var publicKeyEd =
                                spec.registry().getKey(ED25519_KEY).getEd25519();
                        final var signedBytesEd = SignatureGenerator.signBytes(message, privateKeyEd);
                        final var signatureMap = SignatureMap.newBuilder()
                                .sigPair(List.of(
                                        SignaturePair.newBuilder()
                                                .ed25519(Bytes.wrap(signedBytesEd))
                                                .pubKeyPrefix(Bytes.wrap(publicKeyEd.toByteArray()))
                                                .build(),
                                        SignaturePair.newBuilder()
                                                .ecdsaSecp256k1(Bytes.wrap(signedBytesEcdsa))
                                                .pubKeyPrefix(Bytes.wrap(publicKeyEcdsa.toByteArray()))
                                                .build()))
                                .build();
                        final var signatureMapBytes =
                                SignatureMap.PROTOBUF.toBytes(signatureMap).toByteArray();

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedCall",
                                                asHeadlongAddress(asAddress(accountNum.get())),
                                                message,
                                                signatureMapBytes)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        allRunFor(spec, call);
                    }),
                    getTxnRecord("authorizeCall")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(true)))));
        }

        @HapiTest
        final Stream<DynamicTest> thresholdKey1of2HappyPath() {
            final AtomicReference<AccountID> accountNum = new AtomicReference<>();
            return hapiTest(
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    newKeyNamed(ED25519_KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                    newThresholdKeyNamed(THRESHOLD_KEY, 1, List.of(ECDSA_KEY, ED25519_KEY)),
                    cryptoCreate(ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(THRESHOLD_KEY)
                            .exposingCreatedIdTo(accountNum::set),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ED25519_KEY, ONE_HUNDRED_HBARS)),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var message = "submit".getBytes();
                        final var messageHash = new Keccak.Digest256().digest("submit".getBytes());
                        final var privateKeyEcdsa = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                        final var publicKeyEcdsa =
                                spec.registry().getKey(ECDSA_KEY).getECDSASecp256K1();
                        final var addressBytesEcdsa = recoverAddressFromPrivateKey(privateKeyEcdsa);
                        final var signedBytesEcdsa = Signing.signMessage(messageHash, privateKeyEcdsa);

                        final var privateKeyEd = getEd25519PrivateKeyFromSpec(spec, ED25519_KEY);
                        final var publicKeyEd =
                                spec.registry().getKey(ED25519_KEY).getEd25519();
                        final var signedBytesEd = SignatureGenerator.signBytes(message, privateKeyEd);
                        final var signatureMap = SignatureMap.newBuilder()
                                .sigPair(List.of(
                                        //                                        SignaturePair.newBuilder()
                                        //
                                        // .ed25519(Bytes.wrap(signedBytesEd))
                                        //
                                        // .pubKeyPrefix(Bytes.wrap(publicKeyEd.toByteArray()))
                                        //                                                .build(),
                                        SignaturePair.newBuilder()
                                                .ecdsaSecp256k1(Bytes.wrap(signedBytesEcdsa))
                                                .pubKeyPrefix(Bytes.wrap(publicKeyEcdsa.toByteArray()))
                                                .build()))
                                .build();
                        final var signatureMapBytes =
                                SignatureMap.PROTOBUF.toBytes(signatureMap).toByteArray();

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedCall",
                                                asHeadlongAddress(asAddress(accountNum.get())),
                                                message,
                                                signatureMapBytes)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        allRunFor(spec, call);
                    }),
                    getTxnRecord("authorizeCall")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(true)))));
        }

        @HapiTest
        final Stream<DynamicTest> thresholdKey2of2HappyPath() {
            final AtomicReference<AccountID> accountNum = new AtomicReference<>();
            return hapiTest(
                    newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE).generator(new RepeatableKeyGenerator()),
                    newKeyNamed(ED25519_KEY).shape(ED25519).generator(new RepeatableKeyGenerator()),
                    newThresholdKeyNamed(THRESHOLD_KEY, 2, List.of(ECDSA_KEY, ED25519_KEY)),
                    cryptoCreate(ACCOUNT)
                            .balance(ONE_MILLION_HBARS)
                            .key(THRESHOLD_KEY)
                            .exposingCreatedIdTo(accountNum::set),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ED25519_KEY, ONE_HUNDRED_HBARS)),
                    uploadInitCode(HRC632_CONTRACT),
                    contractCreate(HRC632_CONTRACT),
                    withOpContext((spec, opLog) -> {
                        final var message = "submit".getBytes();
                        final var messageHash = new Keccak.Digest256().digest("submit".getBytes());
                        final var privateKeyEcdsa = getEcdsaPrivateKeyFromSpec(spec, ECDSA_KEY);
                        final var publicKeyEcdsa =
                                spec.registry().getKey(ECDSA_KEY).getECDSASecp256K1();
                        final var addressBytesEcdsa = recoverAddressFromPrivateKey(privateKeyEcdsa);
                        final var signedBytesEcdsa = Signing.signMessage(messageHash, privateKeyEcdsa);

                        final var privateKeyEd = getEd25519PrivateKeyFromSpec(spec, ED25519_KEY);
                        final var publicKeyEd =
                                spec.registry().getKey(ED25519_KEY).getEd25519();
                        final var signedBytesEd = SignatureGenerator.signBytes(message, privateKeyEd);
                        final var signatureMap = SignatureMap.newBuilder()
                                .sigPair(List.of(
                                        SignaturePair.newBuilder()
                                                .ed25519(Bytes.wrap(signedBytesEd))
                                                .pubKeyPrefix(Bytes.wrap(publicKeyEd.toByteArray()))
                                                .build(),
                                        SignaturePair.newBuilder()
                                                .ecdsaSecp256k1(Bytes.wrap(signedBytesEcdsa))
                                                .pubKeyPrefix(Bytes.wrap(publicKeyEcdsa.toByteArray()))
                                                .build()))
                                .build();
                        final var signatureMapBytes =
                                SignatureMap.PROTOBUF.toBytes(signatureMap).toByteArray();

                        final var call = atomicBatch(contractCall(
                                                HRC632_CONTRACT,
                                                "isAuthorizedCall",
                                                asHeadlongAddress(asAddress(accountNum.get())),
                                                message,
                                                signatureMapBytes)
                                        .via("authorizeCall")
                                        .gas(2_000_000L)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        allRunFor(spec, call);
                    }),
                    getTxnRecord("authorizeCall")
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().contractCallResult(BoolResult.flag(true)))));
        }
    }
}
