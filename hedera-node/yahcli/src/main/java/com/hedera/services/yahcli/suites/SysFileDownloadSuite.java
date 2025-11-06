// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.queries.file.HapiGetFileContents;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.utils.sysfiles.serdes.StandardSerdes;
import com.hedera.services.bdd.suites.utils.sysfiles.serdes.SysFileSerde;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class SysFileDownloadSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(SysFileDownloadSuite.class);

    private final String destDir;
    private final ConfigManager configManager;
    private final String[] sysFilesToDownload;

    public SysFileDownloadSuite(String destDir, ConfigManager configManager, String[] sysFilesToDownload) {
        this.destDir = destDir;
        this.configManager = configManager;
        this.sysFilesToDownload = sysFilesToDownload;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(downloadSysFiles());
    }

    final Stream<DynamicTest> downloadSysFiles() {
        long[] targets = Utils.rationalized(sysFilesToDownload);
        final var spec = new HapiSpec(
                "downloadSysFiles",
                new MapPropertySource(configManager.asSpecConfig()),
                Arrays.stream(targets).mapToObj(this::appropriateQuery).toArray(HapiSpecOperation[]::new));
        return HapiSpecUtils.targeted(spec, configManager);
    }

    private HapiGetFileContents appropriateQuery(final long fileNum) {
        final String fid = asEntityString(
                configManager.shard().getShardNum(), configManager.realm().getRealmNum(), fileNum);
        if (Utils.isSpecialFile(fileNum)) {
            final String fqLoc = Utils.specialFileLoc(destDir, fileNum);
            return QueryVerbs.getFileContents(fid)
                    .alertingPre(configManager.output()::downloadBeginning)
                    .alertingPost(configManager.output()::downloadEnding)
                    .saveTo(fqLoc);
        }
        final SysFileSerde<String> serde = StandardSerdes.SYS_FILE_SERDES.get(fileNum);
        final String fqLoc = destDir + File.separator + serde.preferredFileName();
        return QueryVerbs.getFileContents(fid)
                .alertingPre(configManager.output()::downloadBeginning)
                .alertingPost(configManager.output()::downloadEnding)
                .saveReadableTo(serde::fromRawFile, fqLoc);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
