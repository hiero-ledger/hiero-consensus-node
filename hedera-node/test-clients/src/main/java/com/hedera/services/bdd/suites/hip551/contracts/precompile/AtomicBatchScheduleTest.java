// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts.precompile;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class AtomicBatchScheduleTest {

    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";

    private static final String GET_FUNGIBLE_CREATE_TOKEN_INFO = "getFungibleCreateTokenInfo";

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

    /**
     * GetScheduledInfoTest
     */
    @HapiTest
    @DisplayName("Atomic cannot get scheduled info for non-existent fungible create schedule")
    public Stream<DynamicTest> atomicCannotGetScheduledInfoForNonExistentFungibleCreateSchedule(
            @NonNull @Contract(contract = "GetScheduleInfo", creationGas = 5_000_000) final SpecContract contract) {
        return hapiTest(withOpContext((spec, log) -> {
            final var callOp = contract.call(
                            GET_FUNGIBLE_CREATE_TOKEN_INFO, asHeadlongAddress(asSolidityAddress(spec, 1234)))
                    .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR, OK, INNER_TRANSACTION_FAILED)
                    // Assert contract call was reverted and has child precompile call with status RECORD_NOT_FOUND
                    // Note: GetScheduledFungibleTokenCreateCall returns RECORD_NOT_FOUND instead of INVALID_SCHEDULE_ID
                    .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, RECORD_NOT_FOUND));
            allRunFor(spec, callOp);
        }));
    }

    /**
     * ScheduleCreateTokenTest
     */
    @HapiTest
    @DisplayName("Atomic can successfully schedule a create fungible token operation")
    public Stream<DynamicTest> atomicScheduledCreateToken(
            @NonNull @Contract(contract = "HIP756Contract", creationGas = 4_000_000L, isImmutable = true)
                    final SpecContract contract,
            @NonNull @Account final SpecAccount treasury,
            @NonNull @Account final SpecAccount autoRenew) {
        final String contractKey = "contractKey";
        return hapiTest(withOpContext((spec, opLog) -> {
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    contract.call("scheduleCreateFT", autoRenew, treasury)
                            .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                            .gas(1_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1])),
                    newKeyNamed(contractKey).shape(CONTRACT.signedWith(contract.name())));
            final var scheduleID = asScheduleId(spec, scheduleAddress.get());
            spec.registry().saveScheduleId("scheduledCreateFT2", scheduleID);
            allRunFor(
                    spec,
                    getScheduleInfo("scheduledCreateFT2")
                            .hasScheduleId("scheduledCreateFT2")
                            .isNotExecuted(),
                    scheduleSign("scheduledCreateFT2").alsoSigningWith(treasury.name()),
                    getScheduleInfo("scheduledCreateFT2").isNotExecuted().hasSignatories(contractKey, treasury.name()),
                    scheduleSign("scheduledCreateFT2").alsoSigningWith(autoRenew.name()),
                    getScheduleInfo("scheduledCreateFT2")
                            .isExecuted()
                            .hasSignatories(contractKey, autoRenew.name(), treasury.name()));
        }));
    }
}
