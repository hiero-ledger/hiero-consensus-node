// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class ScheduleCreateTokenTest {

    private static final String CONTRACT_KEY = "contractKey";
    private static final String SIGN_SCHEDULE = "signSchedule";
    private static final String IHRC755 = "IHRC755";

    @Contract(contract = "HIP756Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @Account
    static SpecAccount treasury;

    @Account
    static SpecAccount autoRenew;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount treasury2;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount autoRenew2;

    @Account
    static SpecAccount designatedPayer;

    @BeforeAll
    public static void setup(TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                overriding("contracts.systemContract.scheduleService.scheduleNative.enabled", "true"),
                contract.getInfo(),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(contract.name())),
                designatedPayer.authorizeContract(contract));
    }

    @HapiTest
    @DisplayName("Can successfully schedule a create fungible token operation")
    public Stream<DynamicTest> scheduledCreateToken() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    contract.call("scheduleCreateFT", autoRenew, treasury)
                            .gas(1_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1])));
            final var scheduleID = asScheduleId(scheduleAddress.get());
            spec.registry().saveScheduleId("scheduledCreateFT", scheduleID);
            assertScheduleAndSign(spec, "scheduledCreateFT");
        }));
    }

    @HapiTest
    @DisplayName("Can successfully schedule a create fungible token operation with designated payer")
    public Stream<DynamicTest> scheduledCreateTokenWithDesignatedPayer() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    contract.call("scheduleCreateFTWithDesignatedPayer", autoRenew, treasury, designatedPayer)
                            .gas(1_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1])));
            final var scheduleID = asScheduleId(scheduleAddress.get());
            spec.registry().saveScheduleId("scheduledCreateFTDesignatedPayer", scheduleID);
            assertScheduleAndSign(spec, "scheduledCreateFTDesignatedPayer");
        }));
    }

    @HapiTest
    @DisplayName("Can successfully schedule a create non fungible token operation")
    public Stream<DynamicTest> scheduledCreateNonFungibleToken() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    contract.call("scheduleCreateNFT", autoRenew, treasury)
                            .gas(1_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1])));
            final var scheduleID = asScheduleId(scheduleAddress.get());
            spec.registry().saveScheduleId("scheduledCreateNFT", scheduleID);
            assertScheduleAndSign(spec, "scheduledCreateNFT");
        }));
    }

    @HapiTest
    @DisplayName("Can successfully schedule a create non fungible token operation with designated payer")
    public Stream<DynamicTest> scheduledCreateNonFungibleTokenWithDesignatedPayer() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    contract.call("scheduleCreateNFTWithDesignatedPayer", autoRenew, treasury, designatedPayer)
                            .gas(1_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1])));
            final var scheduleID = asScheduleId(scheduleAddress.get());
            spec.registry().saveScheduleId("scheduledCreateNFTDesignatedPayer", scheduleID);
            assertScheduleAndSign(spec, "scheduledCreateNFTDesignatedPayer");
        }));
    }

    @HapiTest
    @DisplayName("Can successfully schedule an update token operation to set treasury and auto renew account")
    public Stream<DynamicTest> scheduledUpdateToken(@FungibleToken(initialSupply = 1000) SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    token.authorizeContracts(contract),
                    contract.call(
                                    "scheduleUpdateTreasuryAndAutoRenewAcc",
                                    token,
                                    treasury,
                                    autoRenew,
                                    token.name(),
                                    "",
                                    "")
                            .gas(1_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1])));
            final var scheduleID = asScheduleId(scheduleAddress.get());
            spec.registry().saveScheduleId("scheduledUpdateToken", scheduleID);
            assertScheduleAndSign(spec, "scheduledUpdateToken");
        }));
    }

    @HapiTest
    @DisplayName(
            "Can successfully schedule an update token operation to set treasury and auto renew account with a designated payer")
    public Stream<DynamicTest> scheduledUpdateTokenWithDesignatedPayer(
            @FungibleToken(initialSupply = 1000) SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    token.authorizeContracts(contract),
                    contract.call(
                                    "scheduleUpdateTreasuryAndAutoRenewAccWithDesignatedPayer",
                                    token,
                                    treasury,
                                    autoRenew,
                                    token.name(),
                                    "",
                                    "",
                                    designatedPayer)
                            .gas(1_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1])));
            final var scheduleID = asScheduleId(scheduleAddress.get());
            spec.registry().saveScheduleId("scheduledUpdateToken", scheduleID);
            assertScheduleAndSign(spec, "scheduledUpdateToken");
        }));
    }

    @HapiTest
    @DisplayName("Can successfully schedule a create fungible token operation and sign with EOA via proxy contract")
    public Stream<DynamicTest> scheduledCreateTokenSignWithEOA() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    contract.call("scheduleCreateFT", autoRenew2, treasury2)
                            .gas(1_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1])));
            final var scheduleID = asScheduleId(scheduleAddress.get());
            spec.registry().saveScheduleId("scheduledCreateFTWithEOA", scheduleID);
            assertScheduleAndSignWithEOA(spec, "scheduledCreateFTWithEOA", String.valueOf(scheduleID.getScheduleNum()));
        }));
    }

    private static void assertScheduleAndSign(@NonNull final HapiSpec spec, @NonNull final String scheduleID) {
        allRunFor(
                spec,
                getScheduleInfo(scheduleID).hasScheduleId(scheduleID).isNotExecuted(),
                scheduleSign(scheduleID).alsoSigningWith(treasury.name()),
                getScheduleInfo(scheduleID).isNotExecuted().hasSignatories(CONTRACT_KEY, treasury.name()),
                scheduleSign(scheduleID).alsoSigningWith(autoRenew.name()),
                getScheduleInfo(scheduleID)
                        .isExecuted()
                        .hasSignatories(CONTRACT_KEY, autoRenew.name(), treasury.name()));
    }

    private static void assertScheduleAndSignWithEOA(
            @NonNull final HapiSpec spec, @NonNull final String scheduleID, @NonNull final String scheduleNum) {
        allRunFor(
                spec,
                getScheduleInfo(scheduleID).hasScheduleId(scheduleID).isNotExecuted(),
                contractCallWithFunctionAbi(
                                scheduleNum,
                                getABIFor(
                                        com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                        SIGN_SCHEDULE,
                                        IHRC755))
                        .payingWith(treasury2.name())
                        .gas(1_000_000),
                getScheduleInfo(scheduleID).isNotExecuted().hasSignatories(CONTRACT_KEY, treasury2.name()),
                contractCallWithFunctionAbi(
                                scheduleNum,
                                getABIFor(
                                        com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                        SIGN_SCHEDULE,
                                        IHRC755))
                        .payingWith(autoRenew2.name())
                        .gas(1_000_000),
                getScheduleInfo(scheduleID)
                        .isExecuted()
                        .hasSignatories(CONTRACT_KEY, autoRenew2.name(), treasury2.name()));
    }
}
