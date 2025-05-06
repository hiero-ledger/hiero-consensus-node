// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.yahcli.suites.Utils.isSpecialFile;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.utils.sysfiles.serdes.StandardSerdes;
import com.hedera.services.bdd.suites.utils.sysfiles.serdes.SysFileSerde;
import com.hedera.services.yahcli.commands.files.SysFileUploadCommand;
import com.hedera.services.yahcli.output.CommonMessages;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.DynamicTest;

public class SysFileUploadSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(SysFileUploadSuite.class);

    private static final int NOT_APPLICABLE = -1;

    private final int bytesPerOp;
    private final int appendsPerBurst;
    private final boolean restartFromFailure;
    private final long sysFileId;
    private final String srcDir;
    private final boolean isDryRun;
    private final Map<String, String> specConfig;

    private ByteString uploadData;

    public SysFileUploadSuite(
            final String srcDir, final Map<String, String> specConfig, final String sysFile, final boolean isDryRun) {
        this(NOT_APPLICABLE, NOT_APPLICABLE, false, srcDir, specConfig, sysFile, isDryRun);
    }

    public SysFileUploadSuite(
            final int bytesPerOp,
            final int appendsPerBurst,
            final boolean restartFromFailure,
            final String srcDir,
            final Map<String, String> specConfig,
            final String sysFile,
            final boolean isDryRun) {
        this.bytesPerOp = bytesPerOp;
        this.appendsPerBurst = appendsPerBurst;
        this.restartFromFailure = restartFromFailure;
        this.srcDir = srcDir;
        this.isDryRun = isDryRun;
        this.specConfig = specConfig;
        this.sysFileId = Utils.rationalized(sysFile);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        uploadData = appropriateContents(sysFileId);
        return isDryRun ? Collections.emptyList() : List.of(uploadSysFiles());
    }

    final Stream<DynamicTest> uploadSysFiles() {
        final var name = String.format("UploadSystemFile-%s", sysFileId);
        final var fileId = asEntityString(sysFileId);
        final var isSpecial = isSpecialFile(sysFileId);
        final AtomicInteger wrappedAppendsToSkip = new AtomicInteger();

        return HapiSpec.customHapiSpec(name)
                .withProperties(specConfig)
                .given(UtilVerbs.withOpContext((spec, opLog) -> {
                    if (!restartFromFailure) {
                        return;
                    }
                    final var lookup = QueryVerbs.getFileInfo(fileId);
                    CustomSpecAssert.allRunFor(spec, lookup);
                    final var currentHash =
                            lookup.getResponse().getFileGetInfo().getFileInfo().getMemo();
                    wrappedAppendsToSkip.set(skippedAppendsFor(currentHash));
                }))
                .when()
                .then(UtilVerbs.sourcing(() -> isSpecial
                        ? UtilVerbs.updateSpecialFile(
                                HapiSuite.DEFAULT_PAYER,
                                fileId,
                                uploadData,
                                bytesPerOp,
                                appendsPerBurst,
                                wrappedAppendsToSkip.get())
                        : UtilVerbs.updateLargeFile(
                                HapiSuite.DEFAULT_PAYER,
                                fileId,
                                uploadData,
                                true,
                                OptionalLong.of(10_000_000_000L),
                                updateOp -> updateOp.alertingPre(CommonMessages.COMMON_MESSAGES::uploadBeginning)
                                        .alertingPost(CommonMessages.COMMON_MESSAGES::uploadEnding),
                                (appendOp, appendsLeft) -> appendOp.alertingPre(
                                                CommonMessages.COMMON_MESSAGES::appendBeginning)
                                        .alertingPost(code ->
                                                CommonMessages.COMMON_MESSAGES.appendEnding(code, appendsLeft)))));
    }

    private ByteString appropriateContents(final Long fileNum) {
        if (isSpecialFile(fileNum)) {
            return specialFileContents(fileNum);
        }

        final SysFileSerde<String> serde = StandardSerdes.SYS_FILE_SERDES.get(fileNum);
        final String name = serde.preferredFileName();
        final String loc = srcDir + File.separator + name;
        try {
            final var stylized = Files.readString(Paths.get(loc));
            final var contents =
                    ByteString.copyFrom(serde.toValidatedRawFile(stylized, SysFileUploadCommand.activeSrcDir.get()));
            if (isDryRun) {
                final var contentsLoc = binVersionOfPath(loc);
                Files.write(Paths.get(contentsLoc), contents.toByteArray());
            }
            return contents;
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read update file @ '" + loc + "'!", e);
        }
    }

    private int skippedAppendsFor(final String hexedCurrentHash) {
        final var bytesToUpload = uploadData.size();

        int position = Math.min(bytesPerOp, bytesToUpload);
        int appendsToSkip = 0;
        int numBetweenLogs = 100;
        int i = 0;
        do {
            i++;
            if (i % numBetweenLogs == 0) {
                log.info("Considering skipping appends ending at {} (consideration #{})", position, i);
            }
            final var hashSoFar = hexedPrefixHash(position);
            if (hashSoFar.equals(hexedCurrentHash)) {
                return appendsToSkip;
            }
            appendsToSkip++;
            final var bytesLeft = bytesToUpload - position;
            final var bytesThisAppend = Math.min(bytesLeft, bytesPerOp);
            position += Math.max(1, bytesThisAppend);
        } while (position <= bytesToUpload);
        return 0;
    }

    private String hexedPrefixHash(final int prefixLen) {
        final byte[] hashSoFar = com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf(
                ByteStringUtils.unwrapUnsafelyIfPossible(uploadData.substring(0, prefixLen)));
        return CommonUtils.hex(hashSoFar);
    }

    private ByteString specialFileContents(final long num) {
        final var loc = Utils.specialFileLoc(srcDir, num);
        try {
            return ByteString.copyFrom(Files.readAllBytes(Paths.get(loc)));
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read update file @ '" + loc + "'!", e);
        }
    }

    private String binVersionOfPath(final String loc) {
        final int lastDot = loc.lastIndexOf(".");
        if (lastDot == -1) {
            return loc + ".bin";
        } else {
            return loc.substring(0, lastDot) + ".bin";
        }
    }
}
