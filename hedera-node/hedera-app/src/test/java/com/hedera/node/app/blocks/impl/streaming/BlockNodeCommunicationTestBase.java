// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.PublishStreamResponse.Acknowledgement;
import com.hedera.hapi.block.PublishStreamResponse.BlockAcknowledgement;
import com.hedera.hapi.block.PublishStreamResponse.EndOfStream;
import com.hedera.hapi.block.PublishStreamResponse.ResendBlock;
import com.hedera.hapi.block.PublishStreamResponse.SkipBlock;
import com.hedera.hapi.block.PublishStreamResponseCode;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Base class for tests that involve block node communication.
 */
public abstract class BlockNodeCommunicationTestBase {

    protected static final long BATCH_SIZE = 5L;

    @NonNull
    protected static PublishStreamResponse createSkipBlock(long blockNumber) {
        SkipBlock skipBlock = SkipBlock.newBuilder().blockNumber(blockNumber).build();

        return PublishStreamResponse.newBuilder().skipBlock(skipBlock).build();
    }

    @NonNull
    protected static PublishStreamResponse createResendBlock(long blockNumber) {
        ResendBlock resendBlock =
                ResendBlock.newBuilder().blockNumber(blockNumber).build();

        return PublishStreamResponse.newBuilder().resendBlock(resendBlock).build();
    }

    @NonNull
    protected static PublishStreamResponse createEndOfStreamResponse(
            PublishStreamResponseCode responseCode, long lastVerifiedBlock) {
        EndOfStream eos = EndOfStream.newBuilder()
                .blockNumber(lastVerifiedBlock)
                .status(responseCode)
                .build();

        return PublishStreamResponse.newBuilder().endStream(eos).build();
    }

    @NonNull
    protected static PublishStreamResponse createBlockAckResponse(long blockNumber, boolean alreadyExists) {
        BlockAcknowledgement blockAck = BlockAcknowledgement.newBuilder()
                .blockNumber(blockNumber)
                .blockAlreadyExists(alreadyExists)
                .build();

        Acknowledgement acknowledgement =
                Acknowledgement.newBuilder().blockAck(blockAck).build();

        return PublishStreamResponse.newBuilder()
                .acknowledgement(acknowledgement)
                .build();
    }

    protected ConfigProvider createConfigProvider(long batchSize) {
        final var configPath = Objects.requireNonNull(
                        BlockNodeCommunicationTestBase.class.getClassLoader().getResource("bootstrap/"))
                .getPath();
        assertThat(Files.exists(Path.of(configPath))).isTrue();

        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockNode.blockNodeConnectionFileDir", configPath)
                .withValue("blockStream.blockItemBatchSize", batchSize)
                .getOrCreateConfig();
        return () -> new VersionedConfigImpl(config, 1L);
    }

    protected static BlockItem newBlockHeaderItem() {
        return BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
    }

    protected static BlockItem newBlockTxItem() {
        return BlockItem.newBuilder().build();
    }

    protected static BlockItem newBlockProofItem() {
        return BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().build())
                .build();
    }
}
