// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.ContextRequirement;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class FeeScheduleUpdateWaiverTest {
    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS, requirement = ContextRequirement.NO_CONCURRENT_CREATIONS)
    final Stream<DynamicTest> feeScheduleControlAccountIsntCharged() {
        ResponseCodeEnum[] acceptable = {SUCCESS, FEE_SCHEDULE_FILE_PART_UPLOADED};

        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, FEE_SCHEDULE_CONTROL, 1_000_000_000_000L)),
                balanceSnapshot("pre", FEE_SCHEDULE_CONTROL),
                getFileContents(FEE_SCHEDULE).in4kChunks(true).saveTo("feeSchedule.bin"),
                withOpContext((spec, opLog) -> {
                    var ops = new ArrayList<HapiSpecOperation>();
                    ops.add(fileUpdate(FEE_SCHEDULE)
                            .hasKnownStatusFrom(acceptable)
                            .payingWith(FEE_SCHEDULE_CONTROL)
                            .path(Path.of("./", "part0-feeSchedule.bin").toString()));
                    for (int i = 1; ; i++) {
                        var partPath = Path.of("./", "part" + i + "-feeSchedule.bin");
                        if (!Files.exists(partPath)) {
                            break;
                        }
                        ops.add(fileAppend(FEE_SCHEDULE)
                                .hasKnownStatusFrom(acceptable)
                                .payingWith(FEE_SCHEDULE_CONTROL)
                                .path(partPath.toString()));
                    }
                    allRunFor(spec, ops.toArray(new HapiSpecOperation[0]));
                }),
                getAccountBalance(FEE_SCHEDULE_CONTROL).hasTinyBars(changeFromSnapshot("pre", 0)));
    }
}
