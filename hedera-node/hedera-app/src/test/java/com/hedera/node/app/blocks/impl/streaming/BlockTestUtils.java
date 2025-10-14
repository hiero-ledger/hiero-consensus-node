// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.hapi.block.internal.BufferedBlock;
import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.ParentEventReference;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class BlockTestUtils {
    private BlockTestUtils() {}

    public static void writeBlockToDisk(final BlockState block, final boolean isAcked, final File file)
            throws IOException {
        final List<BlockItem> items = new ArrayList<>();

        for (int i = 0; i < block.itemCount(); ++i) {
            items.add(block.blockItem(i));
        }

        final Block blk = new Block(items);
        final Instant closedInstant = block.closedTimestamp();

        final Timestamp closedTimestamp = Timestamp.newBuilder()
                .seconds(closedInstant.getEpochSecond())
                .nanos(closedInstant.getNano())
                .build();
        final BufferedBlock bufferedBlock = BufferedBlock.newBuilder()
                .blockNumber(block.blockNumber())
                .closedTimestamp(closedTimestamp)
                .isAcknowledged(isAcked)
                .block(blk)
                .build();

        final Bytes payload = BufferedBlock.PROTOBUF.toBytes(bufferedBlock);
        final int length = (int) payload.length();
        final byte[] lenArray = ByteBuffer.allocate(4).putInt(length).array();
        final Bytes len = Bytes.wrap(lenArray);
        final Bytes bytes = Bytes.merge(len, payload);

        Files.write(
                file.toPath(),
                bytes.toByteArray(),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static BlockState toBlockState(final BufferedBlock bufferedBlock) {
        final BlockState block = new BlockState(bufferedBlock.blockNumber());

        bufferedBlock.block().items().forEach(block::addItem);

        final Timestamp closedTimestamp = bufferedBlock.closedTimestamp();
        final Instant closedInstant = Instant.ofEpochSecond(closedTimestamp.seconds(), closedTimestamp.nanos());
        block.closeBlock(closedInstant);

        return block;
    }

    public static List<BlockState> generateRandomBlocks(final int numBlocks) {
        final List<BlockState> blocks = new ArrayList<>(numBlocks);

        for (int blockNumber = 0; blockNumber < numBlocks; ++blockNumber) {
            blocks.add(generateRandomBlock(blockNumber));
        }

        return blocks;
    }

    public static BlockState generateRandomBlock(final long blockNumber) {
        final int numItems = ThreadLocalRandom.current().nextInt(25, 250);
        final BlockState block = new BlockState(blockNumber);
        block.addItem(newBlockHeader(blockNumber));
        for (int i = 0; i < numItems; ++i) {
            block.addItem(newRandomItem());
        }
        block.addItem(newBlockProof(blockNumber));
        block.closeBlock();

        return block;
    }

    public static List<BlockItem> generateBlockItems(final int count, final long blockNumber, final Set<Long> rounds) {
        final List<BlockItem> items = new ArrayList<>(count);

        items.add(newBlockHeader(blockNumber));

        for (int i = 0; i < count; ++i) {
            items.add(newEventTransaction());
        }

        for (final long round : rounds) {
            items.add(newRoundHeader(round));
        }

        items.add(newBlockProof(blockNumber));

        return items;
    }

    private static final Bytes SIGNATURE;
    private static final Bytes VERIFICATION_KEY;
    private static final Bytes PREV_BLOCK_ROOT_HASH;
    private static final Bytes ROOT_HASH_START;
    private static final Bytes APP_TX;
    private static final Bytes EVENT_HASH;

    static {
        final Random random = new Random();
        final byte[] sig = new byte[512];
        final byte[] key = new byte[128];
        final byte[] prevRootHash = new byte[384];
        final byte[] rootHashStart = new byte[384];
        final byte[] appTx = new byte[512];
        final byte[] eventHash = new byte[384];
        random.nextBytes(sig);
        random.nextBytes(key);
        random.nextBytes(prevRootHash);
        random.nextBytes(rootHashStart);
        random.nextBytes(appTx);
        random.nextBytes(eventHash);
        SIGNATURE = Bytes.wrap(sig);
        VERIFICATION_KEY = Bytes.wrap(key);
        PREV_BLOCK_ROOT_HASH = Bytes.wrap(prevRootHash);
        ROOT_HASH_START = Bytes.wrap(rootHashStart);
        APP_TX = Bytes.wrap(appTx);
        EVENT_HASH = Bytes.wrap(eventHash);
    }

    public static BlockItem newRandomItem() {
        // stateChanges, eventHeader, transaction, roundHeader
        final int type = ThreadLocalRandom.current().nextInt(1, 4);
        return switch (type) {
            case 1 -> newStateChanges();
            case 2 -> newEventHeader();
            case 3 -> newRoundHeader(1);
            default -> newEventTransaction();
        };
    }

    public static BlockItem newStateChanges() {
        final MapUpdateChange muc = MapUpdateChange.newBuilder()
                .key(MapChangeKey.newBuilder()
                        .nodeIdKey(NodeId.newBuilder().id(2))
                        .accountIdKey(AccountID.newBuilder().accountNum(10002)))
                .value(MapChangeValue.newBuilder()
                        .accountIdValue(AccountID.newBuilder().accountNum(10002))
                        .build())
                .build();
        final StateChange stateChange1 =
                StateChange.newBuilder().stateId(100).mapUpdate(muc).build();
        final StateChanges stateChanges = StateChanges.newBuilder()
                .stateChanges(stateChange1)
                .consensusTimestamp(new Timestamp(1000, 1000))
                .build();
        return BlockItem.newBuilder().stateChanges(stateChanges).build();
    }

    public static BlockItem newEventHeader() {
        final EventDescriptor eventDescriptor = EventDescriptor.newBuilder()
                .birthRound(1)
                .creatorNodeId(2)
                .hash(EVENT_HASH)
                .build();
        final ParentEventReference parent1 = ParentEventReference.newBuilder()
                .index(0)
                .eventDescriptor(eventDescriptor)
                .build();
        final ParentEventReference parent2 = ParentEventReference.newBuilder()
                .index(1)
                .eventDescriptor(eventDescriptor)
                .build();
        final EventCore eventCore = EventCore.newBuilder()
                .birthRound(1)
                .creatorNodeId(2)
                .timeCreated(new Timestamp(1000, 1000))
                .build();
        final EventHeader eventHeader = EventHeader.newBuilder()
                .parents(parent1, parent2)
                .eventCore(eventCore)
                .build();
        return BlockItem.newBuilder().eventHeader(eventHeader).build();
    }

    public static BlockItem newEventTransaction() {
        return BlockItem.newBuilder().signedTransaction(APP_TX).build();
    }

    public static BlockItem newRoundHeader(final long roundNumber) {
        final RoundHeader roundHeader =
                RoundHeader.newBuilder().roundNumber(roundNumber).build();
        return BlockItem.newBuilder().roundHeader(roundHeader).build();
    }

    public static BlockItem newBlockProof(final long blockNumber) {
        final BlockProof proof = BlockProof.newBuilder()
                .block(blockNumber)
                .blockSignature(SIGNATURE)
                .verificationKey(VERIFICATION_KEY)
                .previousBlockRootHash(PREV_BLOCK_ROOT_HASH)
                .startOfBlockStateRootHash(ROOT_HASH_START)
                .build();
        return BlockItem.newBuilder().blockProof(proof).build();
    }

    public static BlockItem newBlockHeader(final long blockNumber) {
        final Instant now = Instant.now();
        final BlockHeader header = BlockHeader.newBuilder()
                .number(blockNumber)
                .blockTimestamp(new Timestamp(now.getEpochSecond(), now.getNano()))
                .softwareVersion(new SemanticVersion(1, 2, 3, null, null))
                .build();
        return BlockItem.newBuilder().blockHeader(header).build();
    }
}
