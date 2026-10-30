// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class ClprQuerySuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(ClprQuerySuite.class);

    private final ConfigManager configManager;
    private final String specName;
    private final HapiQueryOp<?> op;

    public ClprQuerySuite(final ConfigManager configManager, final String specName, final HapiQueryOp<?> op) {
        this.configManager = configManager;
        this.specName = specName;
        this.op = op;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(run());
    }

    final Stream<DynamicTest> run() {
        final var spec =
                new HapiSpec(specName, new MapPropertySource(configManager.asSpecConfig()), new SpecOperation[] {op});
        return HapiSpecUtils.targeted(spec, configManager);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
