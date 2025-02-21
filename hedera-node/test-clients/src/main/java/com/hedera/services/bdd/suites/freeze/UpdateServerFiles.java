// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.suites.utils.ZipUtil.createZip;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;

/**
 * (FUTURE) Integrate this function to CI in some form?
 */
public class UpdateServerFiles extends HapiSuite {
    private static final Logger log = LogManager.getLogger(UpdateServerFiles.class);
    private static final String zipFile = "Archive.zip";
    private static final String DEFAULT_SCRIPT = "src/main/resources/testfiles/updateFeature/updateSettings/exec.sh";

    private static String uploadPath = "updateFiles/";

    private static String fileIDString = "UPDATE_FEATURE"; // mnemonic for file 0.0.150

    public static void main(final String... args) {

        if (args.length > 0) {
            uploadPath = args[0];
        }

        if (args.length > 1) {
            fileIDString = args[1];
        }

        new UpdateServerFiles().runSuiteSync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return allOf(postiveTests());
    }

    private List<Stream<DynamicTest>> postiveTests() {
        return Arrays.asList(performsFreezeUpgrade());
    }

    // Zip all files under target directory and add an unzip and launch script to it
    // then send to server to update server
    final Stream<DynamicTest> performsFreezeUpgrade() {
        log.info("Creating zip file from {}", uploadPath);
        // create directory if uploadPath doesn't exist
        if (!new File(uploadPath).exists()) {
            new File(uploadPath).mkdirs();
        }
        final String temp_dir = "temp/";
        final String sdk_dir = temp_dir + "sdk/";
        byte[] data = null;
        try {
            // create a temp sdk directory
            final File directory = new File(temp_dir);
            if (directory.exists()) {
                // delete everything in it recursively
                FileUtils.cleanDirectory(directory);
            } else {
                directory.mkdir();
            }

            (new File(sdk_dir)).mkdir();
            // copy files to sdk directory
            FileUtils.copyDirectory(new File(uploadPath), new File(sdk_dir));
            createZip(temp_dir, zipFile, DEFAULT_SCRIPT);
            final String uploadFile = zipFile;

            log.info("Uploading file {}", uploadFile);
            data = Files.readAllBytes(Paths.get(uploadFile));
        } catch (final IOException e) {
            log.error("Directory creation failed", e);
            Assertions.fail("Directory creation failed");
        }
        final byte[] hash = CommonUtils.noThrowSha384HashOf(data);
        return hapiTest(
                fileUpdate(APP_PROPERTIES)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of("maxFileSize", "2048000")),
                UtilVerbs.updateLargeFile(GENESIS, fileIDString, ByteString.copyFrom(data)),
                freezeUpgrade()
                        .withUpdateFile(fileIDString)
                        .havingHash(hash)
                        .payingWith(GENESIS)
                        .startingIn(60)
                        .seconds());
    }
}
