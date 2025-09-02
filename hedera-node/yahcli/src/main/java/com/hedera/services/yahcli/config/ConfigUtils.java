// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.config;

import static com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils.keyFileAt;

import com.hedera.node.app.config.ConfigProviderBase;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.FeesAndRatesProvider;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.queries.file.HapiGetFileContents;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils;
import com.hedera.services.bdd.suites.meta.VersionInfoSpec;
import com.hedera.services.yahcli.Yahcli;
import com.hedera.services.yahcli.commands.ivy.suites.IvyCryptoSuite;
import com.hedera.services.yahcli.suites.BalanceSuite;
import com.hedera.services.yahcli.suites.CostOfEveryThingSuite;
import com.hedera.services.yahcli.suites.CreateNodeSuite;
import com.hedera.services.yahcli.suites.CreateSuite;
import com.hedera.services.yahcli.suites.DeleteNodeSuite;
import com.hedera.services.yahcli.suites.FreezeHelperSuite;
import com.hedera.services.yahcli.suites.RekeySuite;
import com.hedera.services.yahcli.suites.ScheduleSuite;
import com.hedera.services.yahcli.suites.SendSuite;
import com.hedera.services.yahcli.suites.SpecialFileHashSuite;
import com.hedera.services.yahcli.suites.StakeSetupSuite;
import com.hedera.services.yahcli.suites.StakeSuite;
import com.hedera.services.yahcli.suites.SysFileDownloadSuite;
import com.hedera.services.yahcli.suites.SysFileUploadSuite;
import com.hedera.services.yahcli.suites.UpdateNodeSuite;
import com.hedera.services.yahcli.suites.UpgradeHelperSuite;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class ConfigUtils {
    private ConfigUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static String asId(String entity) {
        try {
            int number = Integer.parseInt(entity);
            return "0.0." + number;
        } catch (NumberFormatException ignore) {
        }
        return entity;
    }

    public static Optional<File> keyFileFor(String keysLoc, String typedNum) {
        return keyFileAt(keysLoc + File.separator + typedNum);
    }

    public static File uncheckedKeyFileFor(String keysLoc, String typedNum) {
        final var fileLoc = keysLoc + File.separator + typedNum;
        final var file = keyFileAt(fileLoc);
        if (file.isEmpty()) {
            throw new IllegalArgumentException("No such key file " + fileLoc);
        } else {
            return file.get();
        }
    }

    public static void ensureDir(String loc) {
        File f = new File(loc);
        if (!f.exists()) {
            if (!f.mkdirs()) {
                throw new IllegalStateException("Failed to create directory: " + f.getAbsolutePath());
            }
        }
    }

    public static ConfigManager configFrom(Yahcli yahcli) throws IOException {
        System.out.println("Log level is " + yahcli.getLogLevel());
        AccessoryUtils.setLogLevels(yahcli.getLogLevel(), YAHCLI_LOGGING_CLASSES);
        var config = ConfigManager.from(yahcli);
        config.assertNoMissingDefaults();
        config.output().printGlobalInfo(config);
        return config;
    }

    public static List<Class<?>> YAHCLI_LOGGING_CLASSES = List.of(
            BalanceSuite.class,
            RekeySuite.class,
            SysFileUploadSuite.class,
            SysFileDownloadSuite.class,
            FreezeHelperSuite.class,
            UpgradeHelperSuite.class,
            CostOfEveryThingSuite.class,
            MapPropertySource.class,
            HapiClients.class,
            FeesAndRatesProvider.class,
            HapiQueryOp.class,
            HapiTxnOp.class,
            HapiGetFileContents.class,
            HapiSpec.class,
            VersionInfoSpec.class,
            SendSuite.class,
            ScheduleSuite.class,
            CreateSuite.class,
            SpecialFileHashSuite.class,
            StakeSuite.class,
            StakeSetupSuite.class,
            CustomSpecAssert.class,
            ConfigProviderBase.class,
            CreateNodeSuite.class,
            UpdateNodeSuite.class,
            DeleteNodeSuite.class,
            IvyCryptoSuite.class);
}
