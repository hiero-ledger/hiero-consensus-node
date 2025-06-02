// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class UpgradeHelperSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(UpgradeHelperSuite.class);

    private final byte[] upgradeFileHash;
    private final String upgradeFile;
    /* Null for a PREPARE_UPGRADE, non-null for a TELEMETRY_UPGRADE or FREEZE_UPGRADE */
    private final Instant startTime;
    private final ConfigManager configManager;
    private final boolean isTelemetryUpgrade;

    public UpgradeHelperSuite(
            final ConfigManager configManager, final byte[] upgradeFileHash, final String upgradeFile) {
        this(configManager, upgradeFileHash, upgradeFile, null, false);
    }

    public UpgradeHelperSuite(
            final ConfigManager configManager,
            final byte[] upgradeFileHash,
            final String upgradeFile,
            @Nullable final Instant startTime) {
        this(configManager, upgradeFileHash, upgradeFile, startTime, false);
    }

    public UpgradeHelperSuite(
            final ConfigManager configManager,
            final byte[] upgradeFileHash,
            final String upgradeFile,
            @Nullable final Instant startTime,
            final boolean isTelemetryUpgrade) {
        this.configManager = configManager;
        this.upgradeFile = upgradeFile;
        this.upgradeFileHash = upgradeFileHash;
        this.startTime = startTime;
        this.isTelemetryUpgrade = isTelemetryUpgrade;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(doStagingAction());
    }

    final Stream<DynamicTest> doStagingAction() {
        final HapiSpecOperation op;

        if (startTime == null) {
            op = UtilVerbs.prepareUpgrade()
                    .noLogging()
                    .withUpdateFile(upgradeFile)
                    .havingHash(upgradeFileHash);
        } else if (isTelemetryUpgrade) {
            op = UtilVerbs.telemetryUpgrade()
                    .noLogging()
                    .startingAt(startTime)
                    .withUpdateFile(upgradeFile)
                    .havingHash(upgradeFileHash);
        } else {
            op = UtilVerbs.freezeUpgrade()
                    .noLogging()
                    .startingAt(startTime)
                    .withUpdateFile(upgradeFile)
                    .havingHash(upgradeFileHash);
        }

        final var spec = new HapiSpec(
                "DoStagingAction", new MapPropertySource(configManager.asSpecConfig()), new SpecOperation[] {op});
        return HapiSpecUtils.targeted(spec, configManager);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
