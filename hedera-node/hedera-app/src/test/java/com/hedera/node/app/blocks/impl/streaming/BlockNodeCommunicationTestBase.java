// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeHelidonGrpcConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeHelidonHttpConfiguration;
import com.hedera.node.app.utils.TestCaseLoggerExtension;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import org.hiero.block.api.BlockEnd;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.BehindPublisher;
import org.hiero.block.api.PublishStreamResponse.BlockAcknowledgement;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.PublishStreamResponse.ResendBlock;
import org.hiero.block.api.PublishStreamResponse.SkipBlock;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Base class for tests that involve block node communication.
 */
@ExtendWith(TestCaseLoggerExtension.class)
public abstract class BlockNodeCommunicationTestBase {

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
    protected static PublishStreamResponse createBlockNodeBehindResponse(final long lastVerifiedBlock) {
        final BehindPublisher nodeBehind =
                BehindPublisher.newBuilder().blockNumber(lastVerifiedBlock).build();
        return PublishStreamResponse.newBuilder()
                .nodeBehindPublisher(nodeBehind)
                .build();
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

    @NonNull
    protected static PublishStreamRequest createRequest(final EndStream.Code endCode, final long earliestBlockNumber) {
        final EndStream endStream = EndStream.newBuilder()
                .endCode(endCode)
                .earliestBlockNumber(earliestBlockNumber)
                .build();
        return PublishStreamRequest.newBuilder().endStream(endStream).build();
    }

    @NonNull
    protected static PublishStreamRequest createRequest(final long blockNumber) {
        final BlockEnd endOfBlock =
                BlockEnd.newBuilder().blockNumber(blockNumber).build();
        return PublishStreamRequest.newBuilder().endOfBlock(endOfBlock).build();
    }

    protected TestConfigBuilder createDefaultConfigProvider() {
        final var configPath = Objects.requireNonNull(
                        BlockNodeCommunicationTestBase.class.getClassLoader().getResource("bootstrap/"))
                .getPath();
        assertThat(Files.exists(Path.of(configPath))).isTrue();

        return HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockNode.blockNodeConnectionFileDir", configPath)
                .withValue("blockNode.highLatencyEventsBeforeSwitching", 3)
                .withValue("blockNode.highLatencyThresholdMs", 500);
    }

    protected ConfigProvider createConfigProvider(final TestConfigBuilder configBuilder) {
        return () -> new VersionedConfigImpl(configBuilder.getOrCreateConfig(), 1L);
    }

    protected static BlockItem newBlockHeaderItem() {
        return BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
    }

    protected static BlockItem newBlockHeaderItem(final long blockNumber) {
        final BlockHeader header = BlockHeader.newBuilder().number(blockNumber).build();
        return BlockItem.newBuilder().blockHeader(header).build();
    }

    protected static BlockItem newBlockTxItem() {
        return BlockItem.newBuilder().build();
    }

    protected static BlockItem newBlockTxItem(final int bytes) {
        final byte[] array = new byte[bytes];
        Arrays.fill(array, (byte) 10);

        return BlockItem.newBuilder().signedTransaction(Bytes.wrap(array)).build();
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

    protected static BlockItem newBlockProofItem(final long blockNumber, final int bytes) {
        final byte[] array = new byte[bytes];
        Arrays.fill(array, (byte) 10);

        final BlockProof proof = BlockProof.newBuilder()
                .block(blockNumber)
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(Bytes.wrap(array))
                        .build())
                .build();
        return BlockItem.newBuilder().blockProof(proof).build();
    }

    protected static BlockNodeConfiguration newBlockNodeConfig(final int port, final int priority) {
        return newBlockNodeConfig("localhost", port, priority);
    }

    protected static BlockNodeConfiguration newBlockNodeConfig(
            final String address, final int port, final int priority) {
        return newBlockNodeConfig(
                address,
                port,
                priority,
                BlockNodeConfiguration.DEFAULT_MESSAGE_SOFT_LIMIT_BYTES,
                BlockNodeConfiguration.DEFAULT_MESSAGE_HARD_LIMIT_BYTES);
    }

    protected static BlockNodeConfiguration newBlockNodeConfig(
            final String address,
            final int port,
            final int priority,
            final long messageSoftLimitBytes,
            final long messageHardLimitBytes) {
        return newBlockNodeConfig(
                address,
                port,
                priority,
                messageSoftLimitBytes,
                messageHardLimitBytes,
                BlockNodeHelidonHttpConfiguration.DEFAULT,
                BlockNodeHelidonGrpcConfiguration.DEFAULT);
    }

    protected static BlockNodeConfiguration newBlockNodeConfig(
            final String address,
            final int port,
            final int priority,
            final long messageSoftLimitBytes,
            final long messageHardLimitBytes,
            final BlockNodeHelidonHttpConfiguration clientHttpConfig,
            final BlockNodeHelidonGrpcConfiguration clientGrpcConfig) {
        return BlockNodeConfiguration.newBuilder()
                .address(address)
                .streamingPort(port)
                .servicePort(port)
                .priority(priority)
                .messageSizeSoftLimitBytes(messageSoftLimitBytes)
                .messageSizeHardLimitBytes(messageHardLimitBytes)
                .clientHttpConfig(clientHttpConfig)
                .clientGrpcConfig(clientGrpcConfig)
                .build();
    }
}
