// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

@HapiTestLifecycle
public class AtomicBatchKeyReqTest {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    @DisplayName("Account update requires account key signature")
    public Stream<DynamicTest> accountUpdateRequiresKeySig() {
        return hapiTest(
                newKeyNamed("accountKey"),
                cryptoCreate("batchOperator"),
                cryptoCreate("testAccount").key("accountKey"),
                // no account key signature, so should fail
                atomicBatch(cryptoUpdate("testAccount")
                                .memo("new memo")
                                .batchKey("batchOperator")
                                .payingWith(DEFAULT_PAYER)
                                .signedBy(DEFAULT_PAYER)
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // account key signature, so should succeed
                atomicBatch(cryptoUpdate("testAccount")
                                .memo("new memo")
                                .batchKey("batchOperator")
                                .payingWith(DEFAULT_PAYER)
                                .signedBy(DEFAULT_PAYER, "accountKey"))
                        .payingWith("batchOperator"));
    }

    @Nested
    @DisplayName("Token keys requirements")
    class TokenKeysReq {

        @HapiTest
        @DisplayName("Token update requires admin key")
        public Stream<DynamicTest> tokenUpdateReqAdminKey() {
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate("batchOperator"),
                    tokenCreate("testToken").adminKey("adminKey"),
                    // no admin key signature, so should fail
                    atomicBatch(tokenUpdate("testToken")
                                    .entityMemo("new memo1")
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER)
                                    .hasKnownStatus(INVALID_SIGNATURE))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    // admin key signature, so should succeed
                    atomicBatch(tokenUpdate("testToken")
                                    .entityMemo("new memo")
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER, "adminKey"))
                            .payingWith("batchOperator"));
        }

        @HapiTest
        @DisplayName("Custom fee update requires fee schedule key")
        public Stream<DynamicTest> customFeeUpdateReqFeeScheduleKey() {
            return hapiTest(
                    newKeyNamed("feeScheduleKey"),
                    cryptoCreate("collector"),
                    cryptoCreate("batchOperator"),
                    tokenCreate("testToken").feeScheduleKey("feeScheduleKey"),
                    // no fee schedule key signature, so should fail
                    atomicBatch(tokenFeeScheduleUpdate("testToken")
                                    .withCustom(fixedHbarFee(1L, "collector"))
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER)
                                    .hasKnownStatus(INVALID_SIGNATURE))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    // fee schedule key signature, so should succeed
                    atomicBatch(tokenFeeScheduleUpdate("testToken")
                                    .withCustom(fixedHbarFee(1L, "collector"))
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER, "feeScheduleKey"))
                            .payingWith("batchOperator"));
        }

        @HapiTest
        @DisplayName("Metadata update requires metadata key")
        public Stream<DynamicTest> metadataUpdateRequiresKeySig() {
            return hapiTest(
                    newKeyNamed("metadataKey"),
                    cryptoCreate("batchOperator"),
                    tokenCreate("testToken").metadataKey("metadataKey"),
                    // no metadata key signature, so should fail
                    atomicBatch(tokenUpdate("testToken")
                                    .newMetadata("new metadata")
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER)
                                    .hasKnownStatus(INVALID_SIGNATURE))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    // metadata key signature, so should succeed
                    atomicBatch(tokenUpdate("testToken")
                                    .newMetadata("new metadata")
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER, "metadataKey"))
                            .payingWith("batchOperator"));
        }

        @HapiTest
        @DisplayName("Freeze requires freeze key")
        public Stream<DynamicTest> freezeRequiresFreezeKey() {
            return hapiTest(
                    newKeyNamed("freezeKey"),
                    cryptoCreate("toBeFrozen"),
                    cryptoCreate("batchOperator"),
                    tokenCreate("testToken").freezeKey("freezeKey"),
                    tokenAssociate("toBeFrozen", "testToken"),
                    // no freeze key signature, so should fail
                    atomicBatch(tokenFreeze("testToken", "toBeFrozen")
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER)
                                    .hasKnownStatus(INVALID_SIGNATURE))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    // freeze key signature, so should succeed
                    atomicBatch(tokenFreeze("testToken", "toBeFrozen")
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER, "freezeKey"))
                            .payingWith("batchOperator"));
        }

        @HapiTest
        @DisplayName("Token pause requires pause key")
        public Stream<DynamicTest> pauseRequiresPauseKey() {
            return hapiTest(
                    newKeyNamed("pauseKey"),
                    cryptoCreate("batchOperator"),
                    tokenCreate("testToken").pauseKey("pauseKey"),
                    // no pause key signature, so should fail
                    atomicBatch(tokenPause("testToken")
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER)
                                    .hasKnownStatus(INVALID_SIGNATURE))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    // pause key signature, so should succeed
                    atomicBatch(tokenPause("testToken")
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER, "pauseKey"))
                            .payingWith("batchOperator"));
        }

        @HapiTest
        @DisplayName("Token wipe requires wipe key")
        public Stream<DynamicTest> wipeRequiresWipeKey() {
            return hapiTest(
                    newKeyNamed("wipeKey"),
                    cryptoCreate("batchOperator"),
                    cryptoCreate("treasury"),
                    cryptoCreate("receiver").maxAutomaticTokenAssociations(-1),
                    tokenCreate("testToken").treasury("treasury").wipeKey("wipeKey"),
                    cryptoTransfer(moving(5, "testToken").between("treasury", "receiver")),
                    // no wipe key signature, so should fail
                    atomicBatch(wipeTokenAccount("testToken", "receiver", 5L)
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER)
                                    .hasKnownStatus(INVALID_SIGNATURE))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    // wipe key signature, so should succeed
                    atomicBatch(wipeTokenAccount("testToken", "receiver", 5L)
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER, "wipeKey"))
                            .payingWith("batchOperator"));
        }

        @HapiTest
        @DisplayName("KYC requires kyc key")
        public Stream<DynamicTest> kycRequiresKycKey() {
            return hapiTest(
                    newKeyNamed("kycKey"),
                    cryptoCreate("batchOperator"),
                    cryptoCreate("toBeKycGranted"),
                    tokenCreate("testToken").kycKey("kycKey"),
                    tokenAssociate("toBeKycGranted", "testToken"),
                    // no kyc key signature, so should fail
                    atomicBatch(grantTokenKyc("testToken", "toBeKycGranted")
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER)
                                    .hasKnownStatus(INVALID_SIGNATURE))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    // kyc key signature, so should succeed
                    atomicBatch(grantTokenKyc("testToken", "toBeKycGranted")
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER, "kycKey"))
                            .payingWith("batchOperator"));
        }

        @HapiTest
        @DisplayName("Token mint requires supply key")
        public Stream<DynamicTest> mintRequiresSupplyKey() {
            return hapiTest(
                    newKeyNamed("supplyKey"),
                    cryptoCreate("batchOperator"),
                    tokenCreate("testToken").supplyKey("supplyKey"),
                    // no supply key signature, so should fail
                    atomicBatch(mintToken("testToken", 100)
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER)
                                    .hasKnownStatus(INVALID_SIGNATURE))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    // supply key signature, so should succeed
                    atomicBatch(mintToken("testToken", 100)
                                    .batchKey("batchOperator")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedBy(DEFAULT_PAYER, "supplyKey"))
                            .payingWith("batchOperator"));
        }
    }
}
