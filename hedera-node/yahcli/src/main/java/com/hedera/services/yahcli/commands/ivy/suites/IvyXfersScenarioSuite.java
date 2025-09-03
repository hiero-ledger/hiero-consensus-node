// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig.SCENARIO_PAYER_NAME;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import com.hedera.services.yahcli.config.YahcliKeys;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class IvyXfersScenarioSuite extends AbstractIvySuite {
    private static final Logger log = LogManager.getLogger(IvyXfersScenarioSuite.class);

    private final int networkSize;

    public IvyXfersScenarioSuite(
            @NonNull final Map<String, String> specConfig,
            @NonNull final ScenariosConfig scenariosConfig,
            @NonNull final Supplier<Supplier<String>> nodeAccounts,
            @NonNull final Runnable persistUpdatedScenarios,
            @NonNull final YahcliKeys yahcliKeys,
            final int networkSize) {
        super(specConfig, scenariosConfig, nodeAccounts, persistUpdatedScenarios, yahcliKeys);
        this.networkSize = networkSize;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(xfersScenario());
    }

    final Stream<DynamicTest> xfersScenario() {
        return HapiSpec.customHapiSpec("XfersScenario")
                .withProperties(specConfig)
                .given(ensureScenarioPayer())
                .when()
                .then(IntStream.range(0, networkSize)
                        .mapToObj(i -> cryptoTransfer(tinyBarsFromTo(SCENARIO_PAYER_NAME, FUNDING, 1L))
                                .hasAnyStatusAtAll()
                                .payingWith(SCENARIO_PAYER_NAME)
                                .setNodeFrom(nodeAccounts.get())
                                .logged())
                        .toArray(SpecOperation[]::new));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
