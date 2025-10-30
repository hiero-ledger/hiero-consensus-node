// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.jrs;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.crypto.CryptoCreateSuite.ED_25519_KEY;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.initializeSettings;

import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.suites.HapiSuite;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class NodeOpsForUpgrade extends HapiSuite {
    private static final Logger log = LogManager.getLogger(NodeOpsForUpgrade.class);

    public static void main(String... args) {
        new NodeOpsForUpgrade().runSuiteSync();
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(doDelete());
    }

    final Stream<DynamicTest> doDelete() {
        AtomicReference<String> account100 = new AtomicReference<>();
        return defaultHapiSpec("NodeOpsForUpgrade")
                .given(initializeSettings())
                .when(
                        withOpContext((spec, log) -> account100.set(spec.shard() + "." + spec.realm() + ".100")),
                        overridingTwo("nodes.enableDAB", "true", "nodes.updateAccountIdAllowed", "true"),
                        newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                        sourcing(() -> cryptoTransfer(tinyBarsFromTo(GENESIS, account100.get(), 10000000000L))),
                        nodeDelete("3").payingWith(GENESIS).signedBy(GENESIS),
                        nodeUpdate("2")
                                .description("UpdatedNode0")
                                .accountId("100")
                                .payingWith(GENESIS)
                                .signedBy(GENESIS))
                .then(overridingTwo("nodes.enableDAB", "true", "nodes.updateAccountIdAllowed", "false"));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
