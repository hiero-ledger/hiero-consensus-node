// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.hapi.utils.sysfiles.domain.KnownBlockValues;
import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import com.hedera.node.config.converter.AccountIDConverter;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.converter.CongestionMultipliersConverter;
import com.hedera.node.config.converter.ContractIDConverter;
import com.hedera.node.config.converter.EntityScaleFactorsConverter;
import com.hedera.node.config.converter.FileIDConverter;
import com.hedera.node.config.converter.FunctionalitySetConverter;
import com.hedera.node.config.converter.KeyValuePairConverter;
import com.hedera.node.config.converter.KnownBlockValuesConverter;
import com.hedera.node.config.converter.LongPairConverter;
import com.hedera.node.config.converter.PermissionedAccountsRangeConverter;
import com.hedera.node.config.converter.ScaleFactorConverter;
import com.hedera.node.config.converter.SemanticVersionConverter;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ApiPermissionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.types.CongestionMultipliers;
import com.hedera.node.config.types.EntityScaleFactors;
import com.hedera.node.config.types.HederaFunctionalitySet;
import com.hedera.node.config.types.KeyValuePair;
import com.hedera.node.config.types.LongPair;
import com.hedera.node.config.types.PermissionedAccountsRange;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.config.AddressBookConfig;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import org.hiero.base.crypto.config.CryptoConfig;

/**
 * Configuration utility that provides access to system properties and Hedera platform configuration.
 */
public final class ConfigUtils {

    private ConfigUtils() {}

    public static String STATE_DIR = System.getProperty("state.dir");

    public static String STATE_FILE_NAME = "SignedState.swh";

    public static String TMP_DIR = System.getProperty("tmp.dir", "");

    public static String NODE_NAME = System.getProperty("node.name");

    public static int PARALLELISM = Integer.parseInt(
            System.getProperty("thread.num", "" + Runtime.getRuntime().availableProcessors()));

    public static int FILE_CHANNELS = Integer.parseInt(
            System.getProperty("file.channels", "" + (Runtime.getRuntime().availableProcessors() / 2)));

    public static Boolean VALIDATE_FILE_LAYOUT =
            Boolean.parseBoolean(System.getProperty("validate.file.layout", "true"));

    public static Integer COLLECTED_INFO_THRESHOLD = Integer.parseInt(System.getProperty("hdhm.collected.infos", "20"));

    public static String NODE_DESCRIPTION = System.getProperty("node.description");

    public static String ROUND = System.getProperty("round", "");

    public static String NET_NAME = System.getProperty("net.name");

    public static final int MAX_OBJ_PER_FILE = Integer.parseInt(System.getProperty("maxObjPerFile", "1000000"));

    public static final boolean PRETTY_PRINT_ENABLED = Boolean.parseBoolean(System.getProperty("prettyPrint", "false"));

    public static String SLACK_TAGS = System.getProperty("slack.tags", "@ivan");

    public static String JOB_URL = System.getProperty("job.url");

    private static Configuration configuration;

    private static void initConfiguration() {
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(VirtualMapConfig.class)
                .withConfigDataType(MerkleDbConfig.class)
                .withConfigDataType(CryptoConfig.class)
                .withConfigDataType(StateCommonConfig.class)
                .withConfigDataType(StateConfig.class)
                .withConfigDataType(TemporaryFileConfig.class)
                .withConfigDataType(FilesConfig.class)
                .withConfigDataType(ApiPermissionConfig.class)
                .withConfigDataType(BootstrapConfig.class)
                .withConfigDataType(VersionConfig.class)
                .withConfigDataType(LedgerConfig.class)
                .withConfigDataType(TokensConfig.class)
                .withConfigDataType(AddressBookConfig.class)
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(AccountsConfig.class)
                .withConfigDataType(TssConfig.class)
                .withConfigDataType(PcesConfig.class)
                .withConfigDataType(BasicConfig.class)
                .withConfigDataType(MetricsConfig.class)
                .withSource(new SimpleConfigSource().withValue("merkleDb.usePbj", false))
                .withSource(new SimpleConfigSource().withValue("merkleDb.minNumberOfFilesInCompaction", 2))
                .withSource(new SimpleConfigSource().withValue("merkleDb.maxFileChannelsPerFileReader", FILE_CHANNELS))
                .withSource(new SimpleConfigSource().withValue("merkleDb.maxThreadsPerFileChannel", 1))
                .withConverter(CongestionMultipliers.class, new CongestionMultipliersConverter())
                .withConverter(EntityScaleFactors.class, new EntityScaleFactorsConverter())
                .withConverter(KnownBlockValues.class, new KnownBlockValuesConverter())
                .withConverter(ScaleFactor.class, new ScaleFactorConverter())
                .withConverter(AccountID.class, new AccountIDConverter())
                .withConverter(ContractID.class, new ContractIDConverter())
                .withConverter(FileID.class, new FileIDConverter())
                .withConverter(PermissionedAccountsRange.class, new PermissionedAccountsRangeConverter())
                .withConverter(SemanticVersion.class, new SemanticVersionConverter())
                .withConverter(LongPair.class, new LongPairConverter())
                .withConverter(KeyValuePair.class, new KeyValuePairConverter())
                .withConverter(HederaFunctionalitySet.class, new FunctionalitySetConverter())
                .withConverter(Bytes.class, new BytesConverter());
        if (!TMP_DIR.isEmpty()) {
            configurationBuilder.withSource(
                    new SimpleConfigSource().withValue("temporaryFiles.temporaryFilePath", TMP_DIR));
        }
        configuration = configurationBuilder.build();
    }

    public static Configuration getConfiguration() {
        if (configuration == null) {
            initConfiguration();
        }
        return configuration;
    }
}
