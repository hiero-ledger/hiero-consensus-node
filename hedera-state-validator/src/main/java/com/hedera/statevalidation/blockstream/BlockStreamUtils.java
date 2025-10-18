// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static java.util.Comparator.comparing;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.QueuePushChange;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BlockStreamUtils {
    private static final Logger log = LogManager.getLogger(BlockStreamUtils.class);

    public static Object singletonPutFor(@NonNull final SingletonUpdateChange singletonUpdateChange) {
        return switch (singletonUpdateChange.newValue().kind()) {
            case UNSET -> throw new IllegalStateException("Singleton update value is not set");
            case BLOCK_INFO_VALUE -> singletonUpdateChange.blockInfoValueOrThrow();
            case CONGESTION_LEVEL_STARTS_VALUE -> singletonUpdateChange.congestionLevelStartsValueOrThrow();
            case ENTITY_NUMBER_VALUE -> new EntityNumber(singletonUpdateChange.entityNumberValueOrThrow());
            case EXCHANGE_RATE_SET_VALUE -> singletonUpdateChange.exchangeRateSetValueOrThrow();
            case NETWORK_STAKING_REWARDS_VALUE -> singletonUpdateChange.networkStakingRewardsValueOrThrow();
            case NODE_REWARDS_VALUE -> singletonUpdateChange.nodeRewardsValueOrThrow();
            case BYTES_VALUE -> new ProtoBytes(singletonUpdateChange.bytesValueOrThrow());
            case STRING_VALUE -> new ProtoString(singletonUpdateChange.stringValueOrThrow());
            case RUNNING_HASHES_VALUE -> singletonUpdateChange.runningHashesValueOrThrow();
            case THROTTLE_USAGE_SNAPSHOTS_VALUE -> singletonUpdateChange.throttleUsageSnapshotsValueOrThrow();
            case TIMESTAMP_VALUE -> singletonUpdateChange.timestampValueOrThrow();
            case BLOCK_STREAM_INFO_VALUE -> singletonUpdateChange.blockStreamInfoValueOrThrow();
            case PLATFORM_STATE_VALUE -> singletonUpdateChange.platformStateValueOrThrow();
            case ROSTER_STATE_VALUE -> singletonUpdateChange.rosterStateValueOrThrow();
            case HINTS_CONSTRUCTION_VALUE -> singletonUpdateChange.hintsConstructionValueOrThrow();
            case ENTITY_COUNTS_VALUE -> singletonUpdateChange.entityCountsValueOrThrow();
            case HISTORY_PROOF_CONSTRUCTION_VALUE -> singletonUpdateChange.historyProofConstructionValueOrThrow();
            case CRS_STATE_VALUE -> singletonUpdateChange.crsStateValueOrThrow();
        };
    }

    public static Object queuePushFor(@NonNull final QueuePushChange queuePushChange) {
        return switch (queuePushChange.value().kind()) {
            case UNSET, PROTO_STRING_ELEMENT -> throw new IllegalStateException("Queue push value is not supported");
            case PROTO_BYTES_ELEMENT -> new ProtoBytes(queuePushChange.protoBytesElementOrThrow());
            case TRANSACTION_RECEIPT_ENTRIES_ELEMENT -> queuePushChange.transactionReceiptEntriesElementOrThrow();
        };
    }

    public static Object mapKeyFor(@NonNull final MapChangeKey mapChangeKey) {
        return switch (mapChangeKey.keyChoice().kind()) {
            case UNSET -> throw new IllegalStateException("Key choice is not set for " + mapChangeKey);
            case ACCOUNT_ID_KEY -> mapChangeKey.accountIdKeyOrThrow();
            case TOKEN_RELATIONSHIP_KEY -> pairFrom(mapChangeKey.tokenRelationshipKeyOrThrow());
            case ENTITY_NUMBER_KEY -> new EntityNumber(mapChangeKey.entityNumberKeyOrThrow());
            case FILE_ID_KEY -> mapChangeKey.fileIdKeyOrThrow();
            case NFT_ID_KEY -> mapChangeKey.nftIdKeyOrThrow();
            case PROTO_BYTES_KEY -> new ProtoBytes(mapChangeKey.protoBytesKeyOrThrow());
            case PROTO_LONG_KEY -> new ProtoLong(mapChangeKey.protoLongKeyOrThrow());
            case PROTO_STRING_KEY -> new ProtoString(mapChangeKey.protoStringKeyOrThrow());
            case SCHEDULE_ID_KEY -> mapChangeKey.scheduleIdKeyOrThrow();
            case SLOT_KEY_KEY -> mapChangeKey.slotKeyKeyOrThrow();
            case TOKEN_ID_KEY -> mapChangeKey.tokenIdKeyOrThrow();
            case TOPIC_ID_KEY -> mapChangeKey.topicIdKeyOrThrow();
            case CONTRACT_ID_KEY -> mapChangeKey.contractIdKeyOrThrow();
            case PENDING_AIRDROP_ID_KEY -> mapChangeKey.pendingAirdropIdKeyOrThrow();
            case TIMESTAMP_SECONDS_KEY -> mapChangeKey.timestampSecondsKeyOrThrow();
            case SCHEDULED_ORDER_KEY -> mapChangeKey.scheduledOrderKeyOrThrow();
            case TSS_MESSAGE_MAP_KEY -> mapChangeKey.tssMessageMapKeyOrThrow();
            case TSS_VOTE_MAP_KEY -> mapChangeKey.tssVoteMapKeyOrThrow();
            case HINTS_PARTY_ID_KEY -> mapChangeKey.hintsPartyIdKeyOrThrow();
            case PREPROCESSING_VOTE_ID_KEY -> mapChangeKey.preprocessingVoteIdKeyOrThrow();
            case NODE_ID_KEY -> mapChangeKey.nodeIdKeyOrThrow();
            case CONSTRUCTION_NODE_ID_KEY -> mapChangeKey.constructionNodeIdKeyOrThrow();
            case HOOK_ID_KEY -> mapChangeKey.hookIdKeyOrThrow();
            case LAMBDA_SLOT_KEY -> mapChangeKey.lambdaSlotKeyOrThrow();
        };
    }

    public static Object mapValueFor(@NonNull final MapChangeValue mapChangeValue) {
        return switch (mapChangeValue.valueChoice().kind()) {
            case UNSET -> throw new IllegalStateException("Value choice is not set for " + mapChangeValue);
            case ACCOUNT_VALUE -> mapChangeValue.accountValueOrThrow();
            case ACCOUNT_ID_VALUE -> mapChangeValue.accountIdValueOrThrow();
            case BYTECODE_VALUE -> mapChangeValue.bytecodeValueOrThrow();
            case FILE_VALUE -> mapChangeValue.fileValueOrThrow();
            case NFT_VALUE -> mapChangeValue.nftValueOrThrow();
            case PROTO_STRING_VALUE -> new ProtoString(mapChangeValue.protoStringValueOrThrow());
            case SCHEDULE_VALUE -> mapChangeValue.scheduleValueOrThrow();
            case SCHEDULE_ID_VALUE -> mapChangeValue.scheduleIdValueOrThrow();
            case SCHEDULE_LIST_VALUE -> mapChangeValue.scheduleListValueOrThrow();
            case SLOT_VALUE_VALUE -> mapChangeValue.slotValueValueOrThrow();
            case STAKING_NODE_INFO_VALUE -> mapChangeValue.stakingNodeInfoValueOrThrow();
            case TOKEN_VALUE -> mapChangeValue.tokenValueOrThrow();
            case TOKEN_RELATION_VALUE -> mapChangeValue.tokenRelationValueOrThrow();
            case TOPIC_VALUE -> mapChangeValue.topicValueOrThrow();
            case NODE_VALUE -> mapChangeValue.nodeValueOrThrow();
            case ACCOUNT_PENDING_AIRDROP_VALUE -> mapChangeValue.accountPendingAirdropValueOrThrow();
            case ROSTER_VALUE -> mapChangeValue.rosterValueOrThrow();
            case SCHEDULED_COUNTS_VALUE -> mapChangeValue.scheduledCountsValueOrThrow();
            case THROTTLE_USAGE_SNAPSHOTS_VALUE -> mapChangeValue.throttleUsageSnapshotsValue();
            case TSS_ENCRYPTION_KEYS_VALUE -> mapChangeValue.tssEncryptionKeysValue();
            case TSS_MESSAGE_VALUE -> mapChangeValue.tssMessageValueOrThrow();
            case TSS_VOTE_VALUE -> mapChangeValue.tssVoteValueOrThrow();
            case HINTS_KEY_SET_VALUE -> mapChangeValue.hintsKeySetValueOrThrow();
            case PREPROCESSING_VOTE_VALUE -> mapChangeValue.preprocessingVoteValueOrThrow();
            case CRS_PUBLICATION_VALUE -> mapChangeValue.crsPublicationValueOrThrow();
            case HISTORY_PROOF_VOTE_VALUE -> mapChangeValue.historyProofVoteValue();
            case HISTORY_SIGNATURE_VALUE -> mapChangeValue.historySignatureValue();
            case PROOF_KEY_SET_VALUE -> mapChangeValue.proofKeySetValue();
            case EVM_HOOK_STATE_VALUE -> mapChangeValue.evmHookStateValueOrThrow();
        };
    }

    private static EntityIDPair pairFrom(@NonNull final TokenAssociation tokenAssociation) {
        return new EntityIDPair(tokenAssociation.accountId(), tokenAssociation.tokenId());
    }

    /**
     * Reads all files matching the block file pattern from the given path and returns them in
     * ascending order of block number.
     *
     * @param path the path to read blocks from
     * @return the stream of blocks
     * @throws UncheckedIOException if an I/O error occurs
     */
    public static Stream<Block> readBlocks(@NonNull final Path path) {
        return readBlocks(path, true);
    }
    /**
     * Reads all files matching the block file pattern from the given path and returns them in
     * ascending order of block number.
     *
     * @param path the path to read blocks from
     * @return the stream of blocks
     * @throws UncheckedIOException if an I/O error occurs
     */
    public static Stream<Block> readBlocks(@NonNull final Path path, boolean checkForMarkerFiles) {
        try {
            return orderedBlocksFrom(path, checkForMarkerFiles).stream().map(BlockStreamUtils::blockFrom);
        } catch (IOException e) {
            log.error("Failed to read blocks from path {}", path, e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads a single block from the given path.
     * @param path the path to read the block from
     * @return the block
     */
    public static Block blockFrom(@NonNull final Path path) {
        final var fileName = path.getFileName().toString();
        try {
            if (fileName.endsWith(".gz")) {
                try (final GZIPInputStream in = new GZIPInputStream(Files.newInputStream(path))) {
                    return Block.PROTOBUF.parse(Bytes.wrap(in.readAllBytes()));
                }
            } else {
                return Block.PROTOBUF.parse(Bytes.wrap(Files.readAllBytes(path)));
            }
        } catch (IOException | ParseException e) {
            throw new RuntimeException("Failed reading block @ " + path, e);
        }
    }

    private static List<Path> orderedBlocksFrom(@NonNull final Path path, boolean checkForMarkerFiles)
            throws IOException {
        try (final var stream = Files.walk(path)) {
            return stream.filter(p -> isBlockFile(p, checkForMarkerFiles))
                    .sorted(comparing(BlockStreamUtils::extractBlockNumber))
                    .toList();
        }
    }

    /**
     * Checks if the given path is a block file.
     * @param path the path to check
     * @return true if the path is a block file, false otherwise
     */
    public static boolean isBlockFile(@NonNull final Path path, boolean checkForMarkerFiles) {
        if (!path.toFile().isFile() || extractBlockNumber(path) == -1) {
            return false;
        }
        final var name = path.getFileName().toString();
        if (name.endsWith(".pnd.json")) {
            return false;
        }
        if (name.endsWith(".pnd")) {
            return Files.exists(path.resolveSibling(name + ".json"));
        } else if (name.endsWith(".pnd.gz")) {
            return Files.exists(path.resolveSibling(name.replace(".gz", ".json")));
        }

        // Check for marker file
        final var markerFile =
                path.resolveSibling(name.replace(".blk.gz", ".mf").replace(".blk", ".mf"));
        return Files.exists(markerFile) || !checkForMarkerFiles;
    }

    /**
     * Extracts the block number from the given path.
     *
     * @param path the path
     * @return the block number
     */
    public static long extractBlockNumber(@NonNull final Path path) {
        return extractBlockNumber(path.getFileName().toString());
    }

    /**
     * Extracts the block number from the given file name.
     *
     * @param fileName the file name
     * @return the block number, or -1 if it cannot be extracted
     */
    public static long extractBlockNumber(@NonNull final String fileName) {
        try {
            int i = fileName.indexOf(".blk");
            if (i == -1) {
                i = fileName.indexOf(".pnd");
            }
            return Long.parseLong(fileName.substring(0, i));
        } catch (Exception ignore) {
        }
        return -1;
    }
}
