// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;

import com.hedera.services.bdd.spec.SpecOperation;
import org.hiero.hapi.support.fees.FeeSchedule;

/**
 * Simple fees helper class that overwrites the network's simple-fee schedule file with the given schedule.
 */
public class SimpleFeesOps {

    public static SpecOperation overrideSimpleFees(FeeSchedule schedule) {
        final var bytes = FeeSchedule.PROTOBUF.toBytes(schedule).toByteArray();
        return fileUpdate(SIMPLE_FEE_SCHEDULE).payingWith(GENESIS).contents(bytes);
    }

    public static SpecOperation snapshotSimpleFees(String registryKey) {
        return getFileContents(SIMPLE_FEE_SCHEDULE).saveToRegistry(registryKey).payingWith(GENESIS);
    }

    public static SpecOperation restoreSimpleFees(String registryKey) {
        return withOpContext((spec, log) -> {
            final var original = spec.registry().getBytes(registryKey);
            final var restore =
                    fileUpdate(SIMPLE_FEE_SCHEDULE).contents(original).payingWith(GENESIS);
            allRunFor(spec, restore);
        });
    }
}
