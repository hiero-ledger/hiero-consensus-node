// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.exports;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;

import com.hedera.services.stream.proto.RecordStreamFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;

/**
 * This is a standalone utility tool to read record stream file and check if block number is
 * increasing as expected.
 */
public class RecordBlockNumberTool {
    private static final String LOG_CONFIG_PROPERTY = "logConfig";
    private static final String FILE_NAME_PROPERTY = "fileName";
    private static final String DIR_PROPERTY = "dir";

    private static final Logger LOGGER = LogManager.getLogger(RecordBlockNumberTool.class);
    private static final Marker MARKER = MarkerManager.getMarker("BLOCK_NUMBER");
    /** default log4j2 file name. */
    private static final String DEFAULT_LOG_CONFIG = "log4j2.xml";
    /** name of RecordStreamType. */
    private static final String RECORD_STREAM_EXTENSION = "rcd";

    private static final String COMPRESSED_RECORD_STREAM_EXTENSION = "rcd.gz";

    private static long prevBlockNumber = -1;

    private RecordBlockNumberTool() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static void prepare() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero");

        LOGGER.info(MARKER, "registering Constructables for parsing record stream files");
        // if we are parsing new record stream files,
        // we need to add HederaNode.jar and hedera-protobuf-java-*.jar into class path,
        // so that we can register for parsing RecordStreamObject
        registry.registerConstructables("com.hedera.services.stream");
    }

    private static Pair<Integer, Optional<RecordStreamFile>> readMaybeCompressedRecordStreamFile(final String loc)
            throws IOException {
        final var isCompressed = loc.endsWith(COMPRESSED_RECORD_STREAM_EXTENSION);
        return isCompressed ? readRecordStreamFile(loc) : readUncompressedRecordStreamFile(loc);
    }

    private static Pair<Integer, Optional<RecordStreamFile>> readUncompressedRecordStreamFile(final String fileLoc)
            throws IOException {
        try (final FileInputStream fin = new FileInputStream(fileLoc)) {
            final int recordFileVersion = ByteBuffer.wrap(fin.readNBytes(4)).getInt();
            final RecordStreamFile recordStreamFile = RecordStreamFile.parseFrom(fin);
            return Pair.of(recordFileVersion, Optional.ofNullable(recordStreamFile));
        }
    }

    private static Pair<Integer, Optional<RecordStreamFile>> readRecordStreamFile(final String fileLoc)
            throws IOException {
        final var uncompressedFileContents = FileCompressionUtils.readUncompressedFileBytes(fileLoc);
        final var recordFileVersion =
                ByteBuffer.wrap(uncompressedFileContents, 0, 4).getInt();
        final var recordStreamFile = RecordStreamFile.parseFrom(
                ByteBuffer.wrap(uncompressedFileContents, 4, uncompressedFileContents.length - 4));
        return Pair.of(recordFileVersion, Optional.ofNullable(recordStreamFile));
    }

    private static void trackBlockNumber(final long currentBlockNumber) {
        if (currentBlockNumber <= prevBlockNumber) {
            LOGGER.error(
                    MARKER,
                    "Found new block number is equal or less than the previous one, current {} vs" + " prev {}",
                    currentBlockNumber,
                    prevBlockNumber);
        }

        if (prevBlockNumber != 0 && (currentBlockNumber - prevBlockNumber) > 1) {
            LOGGER.error(
                    MARKER,
                    "Found a gap between block numbers: current {} vs" + " prev {}",
                    currentBlockNumber,
                    prevBlockNumber);
        }
        LOGGER.info(MARKER, "Block number = {}", currentBlockNumber);
        prevBlockNumber = currentBlockNumber;
    }

    // Suppressing the warning that Optional.isEmpty is not called before using the Optional.
    // In reality, it is called, Sonar just can't detect it.
    // Ignoring also that we use generic exception instead of custom
    @SuppressWarnings({"java:S3655", "java:S112"})
    private static Pair<byte[], byte[]> readRecordFile(final String recordFile) {
        try {
            // parse record file
            final Pair<Integer, Optional<RecordStreamFile>> recordResult =
                    readMaybeCompressedRecordStreamFile(recordFile);

            if (recordResult.getValue().isEmpty()) {
                throw new RuntimeException("Record result is empty");
            }

            final long blockNumber = recordResult.getValue().get().getBlockNumber();

            trackBlockNumber(blockNumber);

            final byte[] startRunningHash = recordResult
                    .getValue()
                    .get()
                    .getStartObjectRunningHash()
                    .getHash()
                    .toByteArray();
            final byte[] endRunningHash = recordResult
                    .getValue()
                    .get()
                    .getEndObjectRunningHash()
                    .getHash()
                    .toByteArray();

            return Pair.of((startRunningHash), (endRunningHash));
        } catch (final IOException e) {
            Thread.currentThread().interrupt();
            LOGGER.error(MARKER, "Got IOException when reading record file {} : {}", recordFile, e);
            return Pair.of(null, null);
        }
    }

    // Suppressing the warning that we use generic exception instead of custom
    @SuppressWarnings("java:S112")
    public static void main(final String[] args) {
        // register constructables and set settings
        try {
            prepare();
        } catch (final ConstructableRegistryException e) {
            LOGGER.error(MARKER, "fail to register constructables.", e);
            return;
        }

        final String logConfigPath = System.getProperty(LOG_CONFIG_PROPERTY);
        final File logConfigFile = logConfigPath == null
                ? getAbsolutePath().resolve(DEFAULT_LOG_CONFIG).toFile()
                : new File(logConfigPath);
        if (logConfigFile.exists()) {
            final LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.setConfigLocation(logConfigFile.toURI());

            final String fileName = System.getProperty(FILE_NAME_PROPERTY);
            final String fileDirName = System.getProperty(DIR_PROPERTY);

            try {
                if (fileDirName != null) {
                    readAllFiles(fileDirName);
                } else {
                    readRecordFile(fileName);
                }
            } catch (final IOException e) {
                LOGGER.error(MARKER, "Got IOException", e);
            }
        } else {
            throw new RuntimeException("Could not find log4j2 configuration file " + logConfigFile);
        }
    }

    /**
     * read all files in the provided directory.
     *
     * @param sourceDir the directory where the files to read are located
     */
    // Suppressing the warning that we are declaring generic exception that is thrown
    @SuppressWarnings("java:S1130")
    public static void readAllFiles(final String sourceDir) throws IOException {
        final File folder = new File(sourceDir);
        final File[] streamFiles =
                folder.listFiles(f -> f.getAbsolutePath().endsWith(COMPRESSED_RECORD_STREAM_EXTENSION)
                        || f.getAbsolutePath().endsWith(RECORD_STREAM_EXTENSION));
        Arrays.sort(streamFiles); // sort by file names and timestamps

        final List<File> totalList = new ArrayList<>();
        totalList.addAll(Arrays.asList(Optional.ofNullable(streamFiles).orElse(new File[0])));
        byte[] startRunningHash = null;
        byte[] endRunningHash = null;
        for (final File item : totalList) {
            final Pair<byte[], byte[]> hashes = readRecordFile(item.getAbsolutePath());
            // check if pair left and right are null
            if (hashes.getLeft() == null || hashes.getRight() == null) {
                LOGGER.error(MARKER, "startRunningHash or endRunningHash of file {} is null", item.getAbsolutePath());
                return;
            }
            startRunningHash = hashes.getLeft();
            if (endRunningHash != null) {
                if (!Arrays.equals(startRunningHash, endRunningHash)) {
                    LOGGER.error(
                            MARKER,
                            "startRunningHash of file {} is not equal to endRunningHash of previous file",
                            item.getAbsolutePath());
                }
            }
            endRunningHash = hashes.getRight();
        }
    }
}
