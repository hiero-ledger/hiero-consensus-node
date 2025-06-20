// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class SpecialFileHashSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(SpecialFileHashSuite.class);

    private final String specialFile;
    private final ConfigManager configManager;

    public SpecialFileHashSuite(ConfigManager configManager, String specialFile) {
        this.configManager = configManager;
        this.specialFile = specialFile;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(getSpecialFileHash());
    }

    final Stream<DynamicTest> getSpecialFileHash() {
        long target = Utils.rationalized(specialFile);
        final var fileSpec = new HapiSpec(
                "GetSpecialFileHash", new MapPropertySource(configManager.asSpecConfig()), new HapiSpecOperation[] {
                    UtilVerbs.withOpContext((spec, opLog) -> {
                        final var lookup = QueryVerbs.getFileInfo(asEntityString(spec.shard(), spec.realm(), target));
                        CustomSpecAssert.allRunFor(spec, lookup);
                        final var synthMemo = lookup.getResponse()
                                .getFileGetInfo()
                                .getFileInfo()
                                .getMemo();
                        COMMON_MESSAGES.info("The SHA-384 hash of the " + specialFile + " is:\n" + synthMemo);
                    })
                });
        return HapiSpecUtils.targeted(fileSpec, configManager);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
