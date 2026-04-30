// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SAVED_STATES_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SWIRLDS_LOG;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.STATE_METADATA_FILE;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static com.hedera.services.bdd.junit.support.validators.block.RootHashUtils.extractRootMnemonic;
import static com.hedera.services.bdd.spec.TargetNetworkType.SUBPROCESS_NETWORK;
import static com.swirlds.state.merkle.StateKeyUtils.kvKey;
import static com.swirlds.state.merkle.StateKeyUtils.queueKey;
import static com.swirlds.state.merkle.StateUtils.getStateKeyForSingleton;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.node.app.ServicesMain;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.state.binary.QueueState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Mnemonics;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.Assertions;

/**
 * A validator that replays {@link StateChanges} by mutating a raw {@link VirtualMap} directly,
 * without going through service-specific writable state adapters.
 */
public class BinaryStateChangesValidator implements BlockStreamValidator {

    private static final Logger logger = LogManager.getLogger(BinaryStateChangesValidator.class);
    private static final int HASH_SIZE = 48;
    private static final int QUEUE_STATE_VALUE_ID = 8001;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    private final Bytes expectedRootHash;
    private final Path pathToNode0SwirldsLog;
    private final StateChangesSummary stateChangesSummary = new StateChangesSummary(new TreeMap<>());

    private Instant lastStateChangesTime;
    private VirtualMap state;

    static void main() {
        final var node0Dir = Paths.get("hedera-node/test-clients")
                .resolve(workingDirFor(0, "hapi"))
                .toAbsolutePath()
                .normalize();
        final long shard = 11;
        final long realm = 12;
        final var validator = new BinaryStateChangesValidator(
                Bytes.fromHex(
                        "50ea5c2588457b952dba215bcefc5f54a1b87c298e5c0f2a534a8eb7177354126c55ee5c23319187e964443e4c17c007"),
                node0Dir.resolve("output/swirlds.log"));
        final var blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(
                node0Dir.resolve("data/blockStreams/block-%d.%d.3".formatted(shard, realm)));
        validator.validateBlocks(blocks);
    }

    public static final Factory FACTORY = new Factory() {
        @NonNull
        @Override
        public BlockStreamValidator create(@NonNull final HapiSpec spec) {
            return newValidatorFor(spec);
        }

        @Override
        public boolean appliesTo(@NonNull final HapiSpec spec) {
            return spec.targetNetworkOrThrow().type() == SUBPROCESS_NETWORK;
        }
    };

    /**
     * Constructs a validator that will replay the state changes in the block stream directly into a
     * raw {@link VirtualMap} and compare the resulting root hash to the latest saved state hash.
     *
     * @param spec the spec
     * @return the validator
     */
    public static BinaryStateChangesValidator newValidatorFor(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        final var latestStateDir = findMaybeLatestSavedStateFor(spec);
        if (latestStateDir == null) {
            throw new AssertionError("No saved state directory found");
        }
        final var rootHash = findRootHashFrom(latestStateDir.resolve(STATE_METADATA_FILE));
        if (rootHash == null) {
            throw new AssertionError("No root hash found in state metadata file");
        }
        if (!(spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork)) {
            throw new IllegalArgumentException("Cannot validate state changes for an embedded network");
        }

        final var node0 = subProcessNetwork.getRequiredNode(byNodeId(0));
        return new BinaryStateChangesValidator(rootHash, node0.getExternalPath(SWIRLDS_LOG));
    }

    public BinaryStateChangesValidator(
            @NonNull final Bytes expectedRootHash, @NonNull final Path pathToNode0SwirldsLog) {
        this.expectedRootHash = requireNonNull(expectedRootHash);
        this.pathToNode0SwirldsLog = requireNonNull(pathToNode0SwirldsLog);

        final var platformConfig = ServicesMain.buildPlatformConfig();
        final var metrics = new NoOpMetrics();
        final var merkleDbConfig = platformConfig.getConfigData(MerkleDbConfig.class);
        final var dsBuilder = new MerkleDbDataSourceBuilder(platformConfig, merkleDbConfig.initialCapacity());
        this.state = new VirtualMap(dsBuilder, platformConfig);
        this.state.registerMetrics(metrics);
    }

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        logger.info("Beginning binary replay validation of expected root hash {}", expectedRootHash);
        for (final var block : blocks) {
            for (final var item : block.items()) {
                if (!item.hasStateChanges()) {
                    continue;
                }
                final var changes = item.stateChangesOrThrow();
                final var at = asInstant(changes.consensusTimestampOrThrow());
                if (false && lastStateChangesTime != null && at.isBefore(lastStateChangesTime)) {
                    Assertions.fail("State changes are not in chronological order at " + at);
                }
                lastStateChangesTime = at;
                BinaryStateChangeParser.applyStateChanges(
                        state, StateChanges.PROTOBUF.toBytes(changes), stateChangesSummary);
            }
        }
        logger.info("Summary of binary-applied changes by state:\n{}", stateChangesSummary);

        final var stateToHash = state;
        state = state.copy();
        final var rootHash = requireNonNull(stateToHash.getHash()).getBytes();
        logger.info("Validating binary replay root hash {}", rootHash);

        if (!expectedRootHash.equals(rootHash)) {
            final var expectedRootMnemonic = getMaybeLastHashMnemonics(pathToNode0SwirldsLog);
            final var actualRootMnemonic = Mnemonics.generateMnemonic(stateToHash.getHash());
            final var errorMsg = new StringBuilder("Binary replay hash mismatch");
            errorMsg.append("\n    * root hash - expected ")
                    .append(expectedRootHash)
                    .append(", was ")
                    .append(rootHash);
            if (expectedRootMnemonic != null) {
                errorMsg.append("\n    * root mnemonic - expected ")
                        .append(expectedRootMnemonic)
                        .append(", was ")
                        .append(actualRootMnemonic);
            }
            Assertions.fail(errorMsg.toString());
        }
    }

    private static final class BinaryStateChangeParser {
        private static void applyStateChanges(
                @NonNull final VirtualMap virtualMap,
                @NonNull final Bytes stateChangesBytes,
                @NonNull final StateChangesSummary stateChangesSummary) {
            final ReadableSequentialData input = stateChangesBytes.toReadableSequentialData();
            while (input.hasRemaining()) {
                final int tag = input.readVarInt(false);
                switch (tag) {
                    // consensus_timestamp: field 1, message => (1 << 3) | 2 = 10
                    case 10 -> skipMessage(input);
                    // state_changes:       field 2, message => (2 << 3) | 2 = 18
                    case 18 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            final long endPosition = input.position() + messageLength;
                            processStateChange(virtualMap, input, endPosition, stateChangesSummary);
                        }
                    }
                    default -> skipField(input, tag);
                }
            }
        }

        private static void processStateChange(
                @NonNull final VirtualMap virtualMap,
                @NonNull final ReadableSequentialData input,
                final long endPosition,
                @NonNull final StateChangesSummary stateChangesSummary) {
            int stateId = -1;
            while (input.position() < endPosition) {
                final int tag = input.readVarInt(false);
                switch (tag) {
                    // state_id:         field 1, uint32 varint => (1 << 3) | 0 = 8
                    case 8 -> stateId = ProtoParserTools.readUint32(input);
                    // state_add:        field 2, message       => (2 << 3) | 2 = 18
                    // state_remove:     field 3, message       => (3 << 3) | 2 = 26
                    case 18, 26 -> skipMessage(input);
                    // singleton_update: field 4, message       => (4 << 3) | 2 = 34
                    case 34 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            processSingletonUpdateChange(
                                    virtualMap, requireStateId(stateId), input, input.position() + messageLength);
                            stateChangesSummary.countSingletonPut(stateId);
                        }
                    }
                    // map_update:       field 5, message       => (5 << 3) | 2 = 42
                    case 42 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            processMapUpdateChange(
                                    virtualMap, requireStateId(stateId), input, input.position() + messageLength);
                            stateChangesSummary.countMapUpdate(stateId);
                        }
                    }
                    // map_delete:       field 6, message       => (6 << 3) | 2 = 50
                    case 50 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            processMapDeleteChange(
                                    virtualMap, requireStateId(stateId), input, input.position() + messageLength);
                            stateChangesSummary.countMapDelete(stateId);
                        }
                    }
                    // queue_push:       field 7, message       => (7 << 3) | 2 = 58
                    case 58 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            processQueuePushChange(
                                    virtualMap, requireStateId(stateId), input, input.position() + messageLength);
                            stateChangesSummary.countQueuePush(stateId);
                        }
                    }
                    // queue_pop:        field 8, message       => (8 << 3) | 2 = 66
                    case 66 -> {
                        skipMessage(input);
                        processQueuePopChange(virtualMap, requireStateId(stateId));
                        stateChangesSummary.countQueuePop(stateId);
                    }
                    default -> skipField(input, tag);
                }
            }
        }

        private static void processSingletonUpdateChange(
                @NonNull final VirtualMap virtualMap,
                final int stateId,
                @NonNull final ReadableSequentialData input,
                final long endPosition) {
            final Bytes key = getStateKeyForSingleton(stateId);
            final Bytes rawValue = readOneOfPayload(input, endPosition, "SingletonUpdateChange");
            virtualMap.putBytes(key, wrapStateValue(stateId, rawValue));
        }

        private static void processMapUpdateChange(
                @NonNull final VirtualMap virtualMap,
                final int stateId,
                @NonNull final ReadableSequentialData input,
                final long endPosition) {
            Bytes mapKeyAsStateKey = null;
            Bytes mapValueAsStateValue = null;
            while (input.position() < endPosition) {
                final int tag = input.readVarInt(false);
                switch (tag) {
                    // key:   field 1, message => (1 << 3) | 2 = 10
                    case 10 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            final Bytes rawKey = readMapKeyPayload(input, input.position() + messageLength);
                            mapKeyAsStateKey = kvKey(stateId, rawKey);
                        }
                    }
                    // value: field 2, message => (2 << 3) | 2 = 18
                    case 18 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            final Bytes rawValue =
                                    readOneOfPayload(input, input.position() + messageLength, "MapChangeValue");
                            mapValueAsStateValue = wrapStateValue(stateId, rawValue);
                        }
                    }
                    default -> skipField(input, tag);
                }
            }
            if (mapKeyAsStateKey == null || mapValueAsStateValue == null) {
                throw new IllegalStateException("MapChangeKey or MapChangeValue missing");
            }
            virtualMap.putBytes(mapKeyAsStateKey, mapValueAsStateValue);
        }

        private static void processMapDeleteChange(
                @NonNull final VirtualMap virtualMap,
                final int stateId,
                @NonNull final ReadableSequentialData input,
                final long endPosition) {
            Bytes mapKeyAsStateKey = null;
            while (input.position() < endPosition) {
                final int tag = input.readVarInt(false);
                switch (tag) {
                    // key: field 1, message => (1 << 3) | 2 = 10
                    case 10 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            final Bytes rawKey = readMapKeyPayload(input, input.position() + messageLength);
                            mapKeyAsStateKey = kvKey(stateId, rawKey);
                        }
                    }
                    default -> skipField(input, tag);
                }
            }
            if (mapKeyAsStateKey == null) {
                throw new IllegalStateException("MapChangeKey missing in MapDeleteChange");
            }
            virtualMap.remove(mapKeyAsStateKey);
        }

        private static void processQueuePushChange(
                @NonNull final VirtualMap virtualMap,
                final int stateId,
                @NonNull final ReadableSequentialData input,
                final long endPosition) {
            final QueueState queueState = readQueueState(virtualMap, stateId).orElse(new QueueState(1, 1));
            final Bytes rawElement = readOneOfPayload(input, endPosition, "QueuePushChange");
            virtualMap.putBytes(queueKey(stateId, queueState.tail()), wrapStateValue(stateId, rawElement));
            virtualMap.putBytes(getStateKeyForSingleton(stateId), wrapQueueStateValue(queueState.elementAdded()));
        }

        private static void processQueuePopChange(@NonNull final VirtualMap virtualMap, final int stateId) {
            final QueueState queueState = readQueueState(virtualMap, stateId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Cannot pop from queue - queue state not found for stateId: " + stateId));
            if (queueState.head() >= queueState.tail()) {
                throw new IllegalStateException("Cannot pop from empty queue for stateId: " + stateId);
            }

            virtualMap.remove(queueKey(stateId, queueState.head()));
            virtualMap.putBytes(getStateKeyForSingleton(stateId), wrapQueueStateValue(queueState.elementRemoved()));
        }

        private static @NonNull java.util.Optional<QueueState> readQueueState(
                @NonNull final VirtualMap virtualMap, final int stateId) {
            final Bytes existingQueueStateBytes = virtualMap.getBytes(getStateKeyForSingleton(stateId));
            if (existingQueueStateBytes == null) {
                return java.util.Optional.empty();
            }
            try {
                return java.util.Optional.of(
                        QueueState.QueueStateCodec.INSTANCE.parse(unwrapStateValue(existingQueueStateBytes)));
            } catch (ParseException e) {
                throw new IllegalStateException("Failed to parse QueueState for stateId: " + stateId, e);
            }
        }

        private static Bytes readOneOfPayload(
                @NonNull final ReadableSequentialData input,
                final long endPosition,
                @NonNull final String description) {
            Bytes payload = null;
            while (input.position() < endPosition) {
                final int tag = input.readVarInt(false);
                final var wireType = ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK);
                if (payload == null && wireType == ProtoConstants.WIRE_TYPE_DELIMITED) {
                    final int length = input.readVarInt(false);
                    payload = input.readBytes(length);
                } else {
                    skipField(input, wireType);
                }
            }
            if (payload == null) {
                throw new IllegalStateException(description + " payload missing");
            }
            return payload;
        }

        /**
         * Most block-stream key payloads are already byte-compatible with the state key bytes stored in the
         * VirtualMap. Token relationship keys are the important exception: block stream uses TokenAssociation
         * while state stores EntityIDPair, whose field ordering is different.
         */
        private static Bytes readMapKeyPayload(@NonNull final ReadableSequentialData input, final long endPosition) {
            Bytes payload = null;
            while (input.position() < endPosition) {
                final int tag = input.readVarInt(false);
                final var wireType = ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK);
                if (payload == null && wireType == ProtoConstants.WIRE_TYPE_DELIMITED) {
                    final int length = input.readVarInt(false);
                    payload = input.readBytes(length);
                } else {
                    skipField(input, wireType);
                }
            }
            if (payload == null) {
                throw new IllegalStateException("MapChangeKey payload missing");
            }
            return payload;
        }

        private static Bytes wrapQueueStateValue(@NonNull final QueueState queueState) {
            return wrapStateValue(QUEUE_STATE_VALUE_ID, QueueState.QueueStateCodec.INSTANCE.toBytes(queueState));
        }

        private static Bytes wrapStateValue(final int stateId, @NonNull final Bytes rawValue) {
            final int tag =
                    (stateId << ProtoParserTools.TAG_FIELD_OFFSET) | ProtoConstants.WIRE_TYPE_DELIMITED.ordinal();
            final int valueLength = (int) rawValue.length();
            final int tagSize = ProtoWriterTools.sizeOfVarInt32(tag);
            final int valueSize = ProtoWriterTools.sizeOfVarInt32(valueLength);
            final int totalSize = tagSize + valueSize + valueLength;
            final byte[] buffer = new byte[totalSize];
            final BufferedData out = BufferedData.wrap(buffer);
            out.writeVarInt(tag, false);
            out.writeVarInt(valueLength, false);
            rawValue.writeTo(buffer, (int) out.position());
            return Bytes.wrap(buffer);
        }

        private static Bytes unwrapStateValue(@NonNull final Bytes stateValueBytes) {
            final ReadableSequentialData input = stateValueBytes.toReadableSequentialData();
            input.readVarInt(false);
            final int valueLength = input.readVarInt(false);
            return input.readBytes(valueLength);
        }

        private static int requireStateId(final int stateId) {
            if (stateId < 0) {
                throw new IllegalStateException("StateChange missing state_id");
            }
            return stateId;
        }

        private static void skipField(@NonNull final ReadableSequentialData input, final int tag) {
            skipField(input, ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK));
        }

        private static void skipField(
                @NonNull final ReadableSequentialData input, @NonNull final ProtoConstants wireType) {
            try {
                ProtoParserTools.skipField(input, wireType);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to skip protobuf field with wire type " + wireType, e);
            }
        }

        private static void skipMessage(@NonNull final ReadableSequentialData input) {
            final int messageLength = input.readVarInt(false);
            input.skip(messageLength);
        }
    }

    private static final class StateChangesSummary {
        private final Map<Integer, StateChangeCounts> summaries;

        private StateChangesSummary(@NonNull final Map<Integer, StateChangeCounts> summaries) {
            this.summaries = summaries;
        }

        private void countSingletonPut(final int stateId) {
            summaries.computeIfAbsent(stateId, ignore -> new StateChangeCounts()).singletonPuts++;
        }

        private void countMapUpdate(final int stateId) {
            summaries.computeIfAbsent(stateId, ignore -> new StateChangeCounts()).mapUpdates++;
        }

        private void countMapDelete(final int stateId) {
            summaries.computeIfAbsent(stateId, ignore -> new StateChangeCounts()).mapDeletes++;
        }

        private void countQueuePush(final int stateId) {
            summaries.computeIfAbsent(stateId, ignore -> new StateChangeCounts()).queuePushes++;
        }

        private void countQueuePop(final int stateId) {
            summaries.computeIfAbsent(stateId, ignore -> new StateChangeCounts()).queuePops++;
        }

        @Override
        public String toString() {
            final var sb = new StringBuilder();
            summaries.forEach((stateId, counts) ->
                    sb.append("- ").append(stateId).append(" - ").append(counts).append('\n'));
            return sb.toString();
        }
    }

    private static final class StateChangeCounts {
        private long singletonPuts;
        private long mapUpdates;
        private long mapDeletes;
        private long queuePushes;
        private long queuePops;

        @Override
        public String toString() {
            final var parts = new StringBuilder();
            if (singletonPuts > 0) {
                parts.append("singleton puts=").append(singletonPuts).append(' ');
            }
            if (mapUpdates > 0 || mapDeletes > 0) {
                parts.append("map updates=")
                        .append(mapUpdates)
                        .append(", deletes=")
                        .append(mapDeletes)
                        .append(' ');
            }
            if (queuePushes > 0 || queuePops > 0) {
                parts.append("queue pushes=")
                        .append(queuePushes)
                        .append(", pops=")
                        .append(queuePops);
            }
            return parts.toString().trim();
        }
    }

    private static @Nullable Bytes findRootHashFrom(@NonNull final Path stateMetadataPath) {
        try (final var lines = Files.lines(stateMetadataPath)) {
            return lines.filter(line -> line.startsWith("HASH:"))
                    .map(line -> line.substring(line.length() - 2 * HASH_SIZE))
                    .map(Bytes::fromHex)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            logger.error("Failed to read state metadata file {}", stateMetadataPath, e);
            return null;
        }
    }

    private static @Nullable Path findMaybeLatestSavedStateFor(@NonNull final HapiSpec spec) {
        final var savedStateDirs = spec.getNetworkNodes().stream()
                .map(node -> node.getExternalPath(SAVED_STATES_DIR))
                .map(Path::toAbsolutePath)
                .toList();
        for (final var savedStatesDir : savedStateDirs) {
            try {
                final var latestRoundPath = findLargestNumberDirectory(savedStatesDir);
                if (latestRoundPath != null) {
                    return latestRoundPath;
                }
            } catch (IOException e) {
                logger.error("Failed to find the latest saved state directory in {}", savedStatesDir, e);
            }
        }
        return null;
    }

    private static @Nullable Path findLargestNumberDirectory(@NonNull final Path savedStatesDir) throws IOException {
        long latestRound = -1;
        Path latestRoundPath = null;
        try (final var stream =
                Files.newDirectoryStream(savedStatesDir, BinaryStateChangesValidator::isNumberDirectory)) {
            for (final var numberDirectory : stream) {
                final var round = Long.parseLong(numberDirectory.getFileName().toString());
                if (round > latestRound) {
                    latestRound = round;
                    latestRoundPath = numberDirectory;
                }
            }
        }
        return latestRoundPath;
    }

    private static boolean isNumberDirectory(@NonNull final Path path) {
        return path.toFile().isDirectory()
                && NUMBER_PATTERN.matcher(path.getFileName().toString()).matches();
    }

    private static @Nullable String getMaybeLastHashMnemonics(final Path path) {
        String rootMnemonicLine = null;
        try {
            final var lines = Files.readAllLines(path);
            for (final var line : lines) {
                if (line.startsWith("(root)")) {
                    rootMnemonicLine = line;
                    break;
                }
            }
        } catch (IOException e) {
            logger.error("Could not read root mnemonic from {}", path, e);
            return null;
        }
        logger.info("Read root mnemonic:\n{}", rootMnemonicLine);
        return rootMnemonicLine == null ? null : extractRootMnemonic(rootMnemonicLine);
    }
}
