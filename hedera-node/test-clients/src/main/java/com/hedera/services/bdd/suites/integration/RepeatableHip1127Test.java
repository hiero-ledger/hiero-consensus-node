// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.services.bdd.junit.RepeatableReason.USES_STATE_SIGNATURE_TRANSACTION_CALLBACK;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContain;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(10)
@Tag(INTEGRATION)
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableHip1127Test {
    @RepeatableHapiTest(USES_STATE_SIGNATURE_TRANSACTION_CALLBACK)
    Stream<DynamicTest> preHip1127TxEncodingStillAccepted() {
        final AtomicReference<String> bypassNodeId = new AtomicReference<>();
        return hapiTest(
                sourcingContextual(spec -> {
                    bypassNodeId.set(asAccountString(
                            fromPbj(spec.getNetworkNodes().getLast().getAccountId())));
                    return cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, bypassNodeId.get(), ONE_HUNDRED_HBARS));
                }),
                doingContextual(spec -> spec.repeatableEmbeddedHederaOrThrow().bypassNextWithPreHip1127TxFormat()),
                sourcing(() -> cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, FUNDING, 1))
                        .setNode(bypassNodeId.get())
                        .hasAnyStatusAtAll()),
                assertHgcaaLogDoesNotContain(byNodeId(0), "WARN", Duration.ofSeconds(1)),
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(1)));
    }
}
