// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getEd25519PrivateKeyFromSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.restoreDefault;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INVALID_OPERATION;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.SignatureGenerator;
import com.hedera.node.app.service.contract.impl.utils.SystemContractUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.ScheduleID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class SignScheduleTest {

    private static final String HSS_ADDRESS = "0x000000000000000000000000000000000000016b";
    private static final int TRANSACTION_MAX_GAS = 15_000_000;
    private static final int TRANSACTION_MAX_BYTES = 6144;
    private static final String SENDER = "signScheduleSender";
    private static final String RECEIVER = "signScheduleReceiver";
    private static final String SCHEDULE = "signSchedule";
    private static final String KEY = "signScheduleKey";
    private static final String TX_NAME = "signScheduleTx";

    static final AtomicReference<ScheduleID> scheduleId = new AtomicReference<>();

    @BeforeAll
    public static void setup(TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).receiverSigRequired(true),
                scheduleCreate(SCHEDULE, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                        .payingWith(SENDER)
                        .waitForExpiry()
                        .expiringIn(600)
                        .exposingCreatedIdTo(scheduleId::set));
    }

    @LeakyHapiTest(overrides = {"hedera.transaction.maxBytes"})
    public Stream<DynamicTest> hapiScheduleSignSignatureMapSizeLimitTest() {
        final var keysCount = TRANSACTION_MAX_BYTES / 100 + 1; // assuming each SignaturePair = ~100 bytes
        return hapiTest(withOpContext((spec, _) -> {
            List<String> keyNames = new ArrayList<>();
            // create keys, we do not care if keys are from needed account or not, we interested just in keys count
            allRunFor(
                    spec,
                    IntStream.range(0, keysCount)
                            .boxed()
                            .<SpecOperation>map(e -> {
                                final var keyName = KEY + e;
                                keyNames.add(keyName);
                                return newKeyNamed(keyName);
                            })
                            .toList());
            // prepare SignatureMap
            final var signatureMap = prepareSignatureMap(spec, keyNames);
            final var signatureMapBytes =
                    SignatureMap.PROTOBUF.toBytes(signatureMap).toByteArray();
            final var function = getABIFor(FUNCTION, "signSchedule", "IHederaScheduleService");
            // execute schedule sign
            allRunFor(
                    spec,
                    overriding("hedera.transaction.maxBytes", String.valueOf(TRANSACTION_MAX_BYTES)),
                    // limited by INVALID_TRANSACTION_BODY -> halt(INVALID_OPERATION)
                    ethereumCallWithFunctionAbi(
                                    false, HSS_ADDRESS, function, mirrorAddrWith(scheduleId.get()), signatureMapBytes)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .payingWith(SENDER)
                            .gasLimit(TRANSACTION_MAX_GAS)
                            .via(TX_NAME)
                            .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION),
                    getTxnRecord(TX_NAME)
                            .exposingTo(e -> assertEquals(
                                    INVALID_OPERATION.toString(),
                                    e.getContractCallResult().getErrorMessage())),
                    // increasing 'transaction.maxBytes' to check if max signatures in map depends on
                    // 'transaction.maxBytes'
                    overriding("hedera.transaction.maxBytes", String.valueOf(TRANSACTION_MAX_BYTES * 2)),
                    ethereumCallWithFunctionAbi(
                                    false, HSS_ADDRESS, function, mirrorAddrWith(scheduleId.get()), signatureMapBytes)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .payingWith(SENDER)
                            .gasLimit(TRANSACTION_MAX_GAS)
                            .hasKnownStatus(SUCCESS),
                    restoreDefault("contracts.maxGasPerTransaction"));
        }));
    }

    private static SignatureMap prepareSignatureMap(@NonNull final HapiSpec spec, @NonNull final List<String> keyNames)
            throws SignatureException, NoSuchAlgorithmException, InvalidKeyException {
        List<SignaturePair> sigPair = new ArrayList<>();
        for (String keyName : keyNames) {
            final var message = SystemContractUtils.messageFromScheduleId(CommonPbjConverters.toPbj(scheduleId.get()));
            final var privateKey = getEd25519PrivateKeyFromSpec(spec, keyName);
            final var publicKey = spec.registry().getKey(keyName).getEd25519();
            final var signedBytes = SignatureGenerator.signBytes(message.toByteArray(), privateKey);
            sigPair.add(SignaturePair.newBuilder()
                    .ed25519(Bytes.wrap(signedBytes))
                    .pubKeyPrefix(Bytes.wrap(publicKey.toByteArray()))
                    .build());
        }
        return SignatureMap.newBuilder().sigPair(sigPair).build();
    }
}
