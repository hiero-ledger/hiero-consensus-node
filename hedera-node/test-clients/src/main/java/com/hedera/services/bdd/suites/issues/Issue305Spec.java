// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.issues;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;

public class Issue305Spec {
    @LeakyHapiTest(
            overrides = {
                "fees.percentCongestionMultipliers",
                "fees.minCongestionPeriod",
                "contracts.maxGasPerSec",
                "contracts.maxGasPerSecBackend"
            })
    final Stream<DynamicTest> congestionMultipliersRefreshOnPropertyUpdate() {
        final var civilian = "civilian";
        final var preCongestionTxn = "preCongestionTxn";
        final var multipurposeContract = "Multipurpose";
        final var normalPrice = new AtomicLong();
        final var multipliedPrice = new AtomicLong();
        final List<TransactionID> submittedTxnIds = new ArrayList<>();

        return hapiTest(
                cryptoCreate(civilian).balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(multipurposeContract),
                contractCreate(multipurposeContract).payingWith(GENESIS).logging(),
                contractCall(multipurposeContract)
                        .payingWith(civilian)
                        .gas(200_000)
                        .fee(10 * ONE_HBAR)
                        .sending(ONE_HBAR)
                        .via(preCongestionTxn),
                getTxnRecord(preCongestionTxn).providingFeeTo(normalPrice::set),
                overridingAllOf(Map.of(
                        "contracts.maxGasPerSec", "3_000_000",
                        "contracts.maxGasPerSecBackend", "3_000_000",
                        "fees.percentCongestionMultipliers", "1,5x",
                        "fees.minCongestionPeriod", "1")),
                withOpContext((spec, opLog) -> {
                    // We submit 2.5 seconds of transactions with a 1 second congestion period, so
                    // we should see a 5x multiplier in effect at some point here
                    for (int i = 0; i < 100; i++) {
                        spec.sleepConsensusTime(Duration.ofMillis(25));
                        allRunFor(
                                spec,
                                contractCall(multipurposeContract)
                                        .payingWith(civilian)
                                        .gas(200_000)
                                        .fee(10 * ONE_HBAR)
                                        .sending(ONE_HBAR)
                                        .hasPrecheckFrom(BUSY, OK)
                                        .withTxnTransform(txn -> {
                                            submittedTxnIds.add(idOf(txn));
                                            return txn;
                                        })
                                        .noLogging()
                                        .deferStatusResolution());
                    }
                }),
                withOpContext((spec, opLog) -> {
                    final var congestionInEffect = new AtomicBoolean();
                    submittedTxnIds.reversed().forEach(id -> {
                        if (congestionInEffect.get()) {
                            return;
                        }
                        final var lookup = getTxnRecord(id)
                                .payingWith(GENESIS)
                                .assertingNothing()
                                .nodePayment(1L)
                                .hasAnswerOnlyPrecheckFrom(OK, RECORD_NOT_FOUND)
                                .providingFeeTo(multipliedPrice::set);
                        allRunFor(spec, lookup);
                        try {
                            Assertions.assertEquals(5.0, (1.0 * multipliedPrice.get()) / normalPrice.get(), 0.1);
                            // As soon as any transaction is observed to have the 5x multiplier,
                            // we can stop looking
                            congestionInEffect.set(true);
                        } catch (Throwable ignore) {
                        }
                    });
                    if (!congestionInEffect.get()) {
                        Assertions.fail("~5x multiplier was never observed");
                    }
                }));
    }

    private TransactionID idOf(@NonNull final Transaction txn) {
        try {
            return CommonUtils.extractTransactionBody(txn).getTransactionID();
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
