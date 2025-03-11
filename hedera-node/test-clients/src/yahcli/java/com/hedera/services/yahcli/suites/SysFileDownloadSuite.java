// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.queries.file.HapiGetFileContents;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.utils.sysfiles.serdes.StandardSerdes;
import com.hedera.services.bdd.suites.utils.sysfiles.serdes.SysFileSerde;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class SysFileDownloadSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(SysFileDownloadSuite.class);

    private final String destDir;
    private final Map<String, String> specConfig;
    private final String[] sysFilesToDownload;

    public SysFileDownloadSuite(String destDir, Map<String, String> specConfig, String[] sysFilesToDownload) {
        this.destDir = destDir;
        this.specConfig = specConfig;
        this.sysFilesToDownload = sysFilesToDownload;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(downloadSysFiles());
    }

    final Stream<DynamicTest> downloadSysFiles() {
        long[] targets = Utils.rationalized(sysFilesToDownload);

        return HapiSpec.customHapiSpec("downloadSysFiles")
                .withProperties(specConfig)
                .given()
                .when()
                .then(Arrays.stream(targets).mapToObj(this::appropriateQuery).toArray(HapiSpecOperation[]::new));
    }

    private HapiGetFileContents appropriateQuery(final long fileNum) {
        final String fid = String.format("0.0.%d", fileNum);
        if (Utils.isSpecialFile(fileNum)) {
            final String fqLoc = Utils.specialFileLoc(destDir, fileNum);
            return QueryVerbs.getFileContents(fid)
                    .alertingPre(COMMON_MESSAGES::downloadBeginning)
                    .alertingPost(COMMON_MESSAGES::downloadEnding)
                    .saveTo(fqLoc);
        }
        final SysFileSerde<String> serde = StandardSerdes.SYS_FILE_SERDES.get(fileNum);
        final String fqLoc = destDir + File.separator + serde.preferredFileName();
        return QueryVerbs.getFileContents(fid)
                .alertingPre(COMMON_MESSAGES::downloadBeginning)
                .alertingPost(COMMON_MESSAGES::downloadEnding)
                .saveReadableTo(serde::fromRawFile, fqLoc);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
