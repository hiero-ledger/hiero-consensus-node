// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.BlockAcknowledgement;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.PublishStreamResponse.ResendBlock;
import org.hiero.block.api.PublishStreamResponse.SkipBlock;

/**
 * Base class for tests that involve block node communication.
 */
public abstract class BlockNodeCommunicationTestBase {

    protected static final int BATCH_SIZE = 5;

    @NonNull
    protected static PublishStreamResponse createSkipBlock(final long blockNumber) {
        final SkipBlock skipBlock =
                SkipBlock.newBuilder().blockNumber(blockNumber).build();
        return PublishStreamResponse.newBuilder().skipBlock(skipBlock).build();
    }

    @NonNull
    protected static PublishStreamResponse createResendBlock(final long blockNumber) {
        final ResendBlock resendBlock =
                ResendBlock.newBuilder().blockNumber(blockNumber).build();
        return PublishStreamResponse.newBuilder().resendBlock(resendBlock).build();
    }

    @NonNull
    protected static PublishStreamResponse createEndOfStreamResponse(
            final EndOfStream.Code responseCode, final long lastVerifiedBlock) {
        final EndOfStream eos = EndOfStream.newBuilder()
                .blockNumber(lastVerifiedBlock)
                .status(responseCode)
                .build();
        return PublishStreamResponse.newBuilder().endStream(eos).build();
    }

    @NonNull
    protected static PublishStreamResponse createBlockAckResponse(final long blockNumber) {
        final BlockAcknowledgement blockAck =
                BlockAcknowledgement.newBuilder().blockNumber(blockNumber).build();

        return PublishStreamResponse.newBuilder().acknowledgement(blockAck).build();
    }

    @NonNull
    protected static PublishStreamRequest createRequest(final BlockItem... items) {
        final BlockItemSet itemSet = BlockItemSet.newBuilder().blockItems(items).build();
        return PublishStreamRequest.newBuilder().blockItems(itemSet).build();
    }

    @NonNull
    protected static PublishStreamRequest createRequest(final EndStream.Code endCode) {
        final EndStream endStream = EndStream.newBuilder().endCode(endCode).build();
        return PublishStreamRequest.newBuilder().endStream(endStream).build();
    }

    protected ConfigProvider createConfigProvider() {
        final var configPath = Objects.requireNonNull(
                        BlockNodeCommunicationTestBase.class.getClassLoader().getResource("bootstrap/"))
                .getPath();
        assertThat(Files.exists(Path.of(configPath))).isTrue();

        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockNode.blockNodeConnectionFileDir", configPath)
                .withValue("blockStream.blockItemBatchSize", BATCH_SIZE)
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

    protected static BlockItem newPreProofBlockStateChangesItem() {
        return BlockItem.newBuilder()
                .stateChanges(StateChanges.newBuilder()
                        .stateChanges(StateChange.newBuilder()
                                .singletonUpdate(SingletonUpdateChange.newBuilder()
                                        .blockStreamInfoValue(
                                                BlockStreamInfo.newBuilder().build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    protected static BlockItem newBlockProofItem() {
        return BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().build())
                .build();
    }

    protected static BlockNodeConfig newBlockNodeConfig(final int port, final int priority) {
        return BlockNodeConfig.newBuilder()
                .address("localhost")
                .port(port)
                .priority(priority)
                .build();
    }
}
