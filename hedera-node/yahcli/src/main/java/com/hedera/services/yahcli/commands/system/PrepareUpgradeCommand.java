// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.system;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.yahcli.util.ParseUtils.normalizePossibleIdLiteral;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.Yahcli;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.UpgradeHelperSuite;
import java.util.concurrent.Callable;
import org.hiero.base.utility.CommonUtils;
import picocli.CommandLine;

@CommandLine.Command(
        name = "prepare-upgrade",
        subcommands = {picocli.CommandLine.HelpCommand.class},
        description = "Stages artifacts prior to an NMT software upgrade")
public class PrepareUpgradeCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Yahcli yahcli;

    @CommandLine.Option(
            names = {"-f", "--upgrade-file-num"},
            paramLabel = "Number of the upgrade ZIP file",
            defaultValue = "150")
    private String upgradeFileNum;

    @CommandLine.Option(
            names = {"-h", "--upgrade-zip-hash"},
            paramLabel = "Hex-encoded SHA-384 hash of the upgrade ZIP")
    private String upgradeFileHash;

    @Override
    public Integer call() throws Exception {
        final var config = ConfigUtils.configFrom(yahcli);

        final var normalizedUpgradeFileNum = normalizePossibleIdLiteral(config, upgradeFileNum);
        final var upgradeFile =
                asEntityString(config.shard().getShardNum(), config.realm().getRealmNum(), normalizedUpgradeFileNum);
        final var unhexedHash = CommonUtils.unhex(upgradeFileHash);
        final var delegate = new UpgradeHelperSuite(config, unhexedHash, upgradeFile);

        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            config.output().info("SUCCESS - NMT upgrade staged from " + upgradeFile + " artifacts ZIP");
        } else {
            config.output().warn("FAILED - NMT software upgrade is not in staged ");
            return 1;
        }

        return 0;
    }
}
