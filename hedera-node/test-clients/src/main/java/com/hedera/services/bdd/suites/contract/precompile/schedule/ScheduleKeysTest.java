package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.KeyLabels;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoCreate;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.SECP256K1;
import static com.hedera.services.bdd.spec.keys.SigControl.ANY;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.exposeSpecSecondTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class ScheduleKeysTest {

    private static final String TARGET_CONTRACT = "EmptyOne";
    private static final String SIGNER_ACCOUNT = "ScheduleCreateAccount";
    private static final String HSS_CONTRACT_ENTITY = asEntityString(0, 0, 363);
    private static final String SCHEDULE_CALL_ABI = "{\"inputs\":[{\"internalType\":\"address\",\"name\":\"to\",\"type\":\"address\"},{\"internalType\":\"uint256\",\"name\":\"expirySecond\",\"type\":\"uint256\"},{\"internalType\":\"uint256\",\"name\":\"gasLimit\",\"type\":\"uint256\"},{\"internalType\":\"uint64\",\"name\":\"value\",\"type\":\"uint64\"},{\"internalType\":\"bytes\",\"name\":\"callData\",\"type\":\"bytes\"}],\"name\":\"scheduleCall\",\"outputs\":[{\"internalType\":\"int64\",\"name\":\"responseCode\",\"type\":\"int64\"},{\"internalType\":\"address\",\"name\":\"scheduleAddress\",\"type\":\"address\"}],\"stateMutability\":\"nonpayable\",\"type\":\"function\"}";

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                uploadInitCode(TARGET_CONTRACT),
                contractCreate(TARGET_CONTRACT)
        );
    }

    @LeakyHapiTest(fees = "scheduled-contract-fees.json")
    @DisplayName("EOA can call HSS.scheduleCall() using ECDSA key")
    final Stream<DynamicTest> scheduleCallWithECDSAKey() {
        final var key = "scheduleKeyECDSA";
        return hapiTest(
                newKeyNamed(key).shape(SECP256K1),
                scheduleCallWithKey(key, null, SUCCESS)
        );
    }

    @LeakyHapiTest(fees = "scheduled-contract-fees.json")
    @DisplayName("EOA can call HSS.scheduleCall() using ED25519 key")
    final Stream<DynamicTest> scheduleCallWithED25519Key() {
        final var key = "scheduleKeyED25519";
        return hapiTest(
                newKeyNamed(key).shape(ED25519),
                scheduleCallWithKey(key, null, SUCCESS)
        );
    }

    @LeakyHapiTest(fees = "scheduled-contract-fees.json")
    @DisplayName("EOA with 1-of-3 ThresholdKey can call HSS.scheduleCall() using K1")
    final Stream<DynamicTest> scheduleCallWith1of3ThresholdKeyAnd1Sign() {
        final var K1 = "scheduleKey1";
        final var K2 = "scheduleKey2";
        final var K3 = "scheduleKey3";
        final var THRESHOLD_KEY = "scheduleKey2of3";
        return hapiTest(
                newKeyNamed(K1).shape(SECP256K1),
                newKeyNamed(K2).shape(SECP256K1),
                newKeyNamed(K3).shape(SECP256K1),
                // Build the composite 1-of-3 ThresholdKey that will control Alice's account
                newKeyNamed(THRESHOLD_KEY)
                        .shape(SigControl.threshSigs(1, ANY, ANY, ANY))
                        .labels(KeyLabels.complex(K1, K2, K3)),
                // K1 + K2 sign (ON), K3 does not (OFF) — satisfies the 2-of-3 threshold
                scheduleCallWithKey(THRESHOLD_KEY, forKey(THRESHOLD_KEY, SigControl.threshSigs(1, ON, OFF, OFF)), INVALID_SIGNATURE)
        );
    }

    @LeakyHapiTest(fees = "scheduled-contract-fees.json")
    @DisplayName("EOA with 2-of-3 ThresholdKey can call HSS.scheduleCall() using K1+K2")
    final Stream<DynamicTest> scheduleCallWith2of3ThresholdKeyAnd2Sign() {
        final var K1 = "scheduleKey1";
        final var K2 = "scheduleKey2";
        final var K3 = "scheduleKey3";
        final var THRESHOLD_KEY = "scheduleKey2of3";
        return hapiTest(
                newKeyNamed(K1).shape(SECP256K1),
                newKeyNamed(K2).shape(SECP256K1),
                newKeyNamed(K3).shape(SECP256K1),
                // Build the composite 2-of-3 ThresholdKey that will control Alice's account
                newKeyNamed(THRESHOLD_KEY)
                        .shape(SigControl.threshSigs(2, ANY, ANY, ANY))
                        .labels(KeyLabels.complex(K1, K2, K3)),
                // K1 + K2 sign (ON), K3 does not (OFF) — satisfies the 2-of-3 threshold
                scheduleCallWithKey(THRESHOLD_KEY, forKey(THRESHOLD_KEY, SigControl.threshSigs(2, ON, ON, OFF)), INVALID_SIGNATURE)
        );
    }

    @LeakyHapiTest(fees = "scheduled-contract-fees.json")
    @DisplayName("EOA with 2-of-3 ThresholdKey can call HSS.scheduleCall() using K1+K2+K3")
    final Stream<DynamicTest> scheduleCallWith2of3ThresholdKeyAnd3Sign() {
        final var K1 = "scheduleKey1";
        final var K2 = "scheduleKey2";
        final var K3 = "scheduleKey3";
        final var THRESHOLD_KEY = "scheduleKey2of3";
        return hapiTest(
                newKeyNamed(K1).shape(SECP256K1),
                newKeyNamed(K2).shape(SECP256K1),
                newKeyNamed(K3).shape(SECP256K1),
                // Build the composite 2-of-3 ThresholdKey that will control Alice's account
                newKeyNamed(THRESHOLD_KEY)
                        .shape(SigControl.threshSigs(2, ANY, ANY, ANY))
                        .labels(KeyLabels.complex(K1, K2, K3)),
                // K1 + K2 sign (ON), K3 does not (OFF) — satisfies the 2-of-3 threshold
                scheduleCallWithKey(THRESHOLD_KEY, null, INVALID_SIGNATURE)
        );
    }

    final CustomSpecAssert scheduleCallWithKey(final String key, ControlForKey sigControl, ResponseCodeEnum expectedResponse) {
        return withOpContext((spec, opLog) -> {
            final AtomicLong nowSeconds = new AtomicLong();
            final AtomicReference<ResponseCodeEnum> scheduleCreateResponse = new AtomicReference<>();
            // prepare for scheduleCall
            HapiCryptoCreate create = cryptoCreate(SIGNER_ACCOUNT)
                    .key(key)
                    .balance(ONE_HUNDRED_HBARS);
            if (sigControl != null) {
                create.sigControl(sigControl);
            }
            allRunFor(spec, create, exposeSpecSecondTo(nowSeconds::set));
            // create scheduleCall, directly call the HSS system contract by its entity number ("363")
            HapiContractCall call = contractCallWithFunctionAbi(
                    HSS_CONTRACT_ENTITY,
                    SCHEDULE_CALL_ABI,
                    HapiParserUtil.asHeadlongAddress(
                            asHexedSolidityAddress(spec.registry().getContractId(TARGET_CONTRACT))),
                    BigInteger.valueOf(nowSeconds.get() + 120),
                    BigInteger.valueOf(2_000_000L),
                    BigInteger.ZERO, // uint64 value in tinybars
                    new byte[0])
                    .gas(2_000_000L)
                    .payingWith(SIGNER_ACCOUNT)
                    .signingWith(SIGNER_ACCOUNT)
                    .exposingResultTo(res -> {
                        scheduleCreateResponse.set(ResponseCodeEnum.forNumber(Long.valueOf((long) res[0]).intValue()));
                        opLog.info("Response Code: {} scheduleAddress: {}", res[0], res[1]);
                    })
                    .hasKnownStatus(SUCCESS);
            if (sigControl != null) {
                call.sigControl(sigControl);
            }
            allRunFor(spec, call);
            Assertions.assertEquals(expectedResponse, scheduleCreateResponse.get());
        });
    }
}
