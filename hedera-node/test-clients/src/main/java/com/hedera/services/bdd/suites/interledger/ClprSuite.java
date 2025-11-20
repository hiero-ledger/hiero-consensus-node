// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hederahashgraph.api.proto.java.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class ClprSuite {

    @HapiTest
    final Stream<DynamicTest> createsClprLedgerConfig() {
        final var now = Instant.now();
        return hapiTest(
                TxnVerbs.clprSetLedgerConfig("ledgerId")
                        .timestamp(Timestamp.newBuilder()
                                .setSeconds(now.getEpochSecond())
                                .setNanos(now.getNano())
                                .build()),
                QueryVerbs.getLedgerConfig("ledgerId")
                        .hasTimestamp(now.getEpochSecond())
                        .hasEndpoints(List.of()));
    }
}
