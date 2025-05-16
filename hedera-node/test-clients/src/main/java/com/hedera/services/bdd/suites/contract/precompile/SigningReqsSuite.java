// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asTokenString;
import static com.hedera.services.bdd.spec.HapiPropertySource.idAsHeadlongAddress;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.ON;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.file.FileUpdateSuite.CIVILIAN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// Some of the test cases cannot be converted to use eth calls,
// since they use admin keys, which are held by the txn payer.
// In the case of an eth txn, we revoke the payers keys and the txn would fail.
// The only way an eth account to create a token is the admin key to be of a contractId type.
@Tag(SMART_CONTRACT)
public class SigningReqsSuite {
    private static final String FIRST_CREATE_TXN = "firstCreateTxn";
    private static final String SECOND_CREATE_TXN = "secondCreateTxn";
    private static final long DEFAULT_AMOUNT_TO_SEND = 20 * ONE_HBAR;
    private static final String MINIMAL_CREATIONS_CONTRACT = "MinimalTokenCreations";

    private static final String LEGACY_ACTIVATIONS_PROP = "contracts.keys.legacyActivations";
    public static final String AUTO_RENEW = "autoRenew";
    public static final int GAS_TO_OFFER = 1_000_000;

    @LeakyHapiTest(overrides = {"contracts.keys.legacyActivations"})
    final Stream<DynamicTest> autoRenewAccountCanUseLegacySigActivationIfConfigured() {
        final var autoRenew = AUTO_RENEW;
        final AtomicReference<Address> autoRenewMirrorAddr = new AtomicReference<>();
        final AtomicReference<ContractID> contractId = new AtomicReference<>();
        final var origKey = KeyShape.threshOf(1, ED25519, CONTRACT);
        final AtomicReference<TokenID> createdToken = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(CIVILIAN).balance(10L * ONE_HUNDRED_HBARS),
                uploadInitCode(MINIMAL_CREATIONS_CONTRACT),
                contractCreate(MINIMAL_CREATIONS_CONTRACT)
                        .exposingContractIdTo(contractId::set)
                        .gas(5_000_000L)
                        .refusingEthConversion(),
                cryptoCreate(autoRenew)
                        .keyShape(origKey.signedWith(sigs(ON, MINIMAL_CREATIONS_CONTRACT)))
                        .exposingCreatedIdTo(id -> autoRenewMirrorAddr.set(idAsHeadlongAddress(id))),
                // Fails without the auto-renew account's full-prefix signature
                sourcing(() -> contractCall(
                                MINIMAL_CREATIONS_CONTRACT,
                                "makeRenewableTokenIndirectly",
                                autoRenewMirrorAddr.get(),
                                THREE_MONTHS_IN_SECONDS)
                        .via(FIRST_CREATE_TXN)
                        .gas(10L * GAS_TO_OFFER)
                        .sending(DEFAULT_AMOUNT_TO_SEND)
                        .payingWith(CIVILIAN)
                        .refusingEthConversion()
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords().logged(),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var autoRenewNum = registry.getAccountID(autoRenew).getAccountNum();
                    final var parentContractNum =
                            registry.getContractId(MINIMAL_CREATIONS_CONTRACT).getContractNum();
                    final var overrideValue = autoRenewNum + "by[" + parentContractNum + "]";
                    final var propertyUpdate = overriding("contracts.keys.legacyActivations", overrideValue);
                    CustomSpecAssert.allRunFor(spec, propertyUpdate);
                }),
                // Succeeds now because the called contract received legacy activation privilege
                sourcing(() -> contractCall(
                                MINIMAL_CREATIONS_CONTRACT,
                                "makeRenewableTokenIndirectly",
                                autoRenewMirrorAddr.get(),
                                THREE_MONTHS_IN_SECONDS)
                        .via(SECOND_CREATE_TXN)
                        .gas(10L * GAS_TO_OFFER)
                        .sending(DEFAULT_AMOUNT_TO_SEND)
                        .payingWith(CIVILIAN)
                        .refusingEthConversion()),
                getTxnRecord(SECOND_CREATE_TXN)
                        .andAllChildRecords()
                        .exposingTokenCreationsTo(creations -> createdToken.set(creations.getFirst())),
                childRecordsCheck(
                        FIRST_CREATE_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        TransactionRecordAsserts.recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                sourcing(() -> getTokenInfo(asTokenString(createdToken.get())).hasAutoRenewAccount(autoRenew)));
    }
}
