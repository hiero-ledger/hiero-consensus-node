// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.ContextRequirement;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class FeeScheduleUpdateWaiverTest {
    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS, requirement = ContextRequirement.NO_CONCURRENT_CREATIONS)
    final Stream<DynamicTest> feeScheduleControlAccountIsntCharged() {
        final AtomicReference<byte[]> schedule = new AtomicReference<>();
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, FEE_SCHEDULE_CONTROL, 1_000_000_000_000L)),
                balanceSnapshot("pre", FEE_SCHEDULE_CONTROL),
                getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(schedule::set),
                sourcing(() -> updateLargeFile(
                        FEE_SCHEDULE_CONTROL, SIMPLE_FEE_SCHEDULE, ByteString.copyFrom(schedule.get()))),
                getAccountBalance(FEE_SCHEDULE_CONTROL).hasTinyBars(changeFromSnapshot("pre", 0)));
    }
}
