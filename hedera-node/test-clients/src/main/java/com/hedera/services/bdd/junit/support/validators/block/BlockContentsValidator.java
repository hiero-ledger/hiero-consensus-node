// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isEmpty;
import static com.swirlds.platform.system.InitTrigger.RESTART;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.ServicesMain;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.base.time.Time;
import com.swirlds.state.merkle.StateKeyUtils;
import com.swirlds.virtualmap.VirtualMapIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.config.PathsConfig;

/**
 * Validates the structure of blocks, including both normal blocks and Wrapped Record Blocks (WRBs)
 * produced by the historical record file wrapping process.
 */
public class BlockContentsValidator implements BlockStreamValidator {
    private static final Logger logger = LogManager.getLogger(BlockContentsValidator.class);

    private static final int REASONABLE_NUM_PENDING_PROOFS_AT_FREEZE = 3;

    public static void main(String[] args) {
        final var savedStateDir = Paths.get("hedera-node/test-clients/hapi-nodejs/.saved/compressed/175900")
                .toAbsolutePath()
                .normalize();
        final var metrics = new NoOpMetrics();
        final var platformConfig = ServicesMain.buildPlatformConfig();
        final var pathsConfig = platformConfig.getConfigData(PathsConfig.class);
        final var fileSystemManager = new FileSystemManager(pathsConfig.savedStateDir(), pathsConfig.tmpDir());
        final var hedera =
                ServicesMain.newHedera(platformConfig, fileSystemManager, metrics, Time.getCurrent(), NodeId.FIRST_NODE_ID);
        final var stateLifecycleManager = hedera.getStateLifecycleManager();

        try {
            stateLifecycleManager.loadSnapshot(savedStateDir);
            final var state = stateLifecycleManager.getMutableState();
            hedera.initializeStatesApi(state, RESTART, platformConfig);
            final var virtualMap = state.getRoot();
            try {
                final var tokensIterator = new VirtualMapIterator(virtualMap)
                        .setFilter(leafBytes -> StateKeyUtils.extractStateIdFromStateKeyOneOf(leafBytes.keyBytes())
                                == V0490TokenSchema.TOKENS_STATE_ID);
                while (tokensIterator.hasNext()) {
                    final var leafRecord = tokensIterator.next();
                    final var keyBytes = leafRecord.keyBytes();

                    final StateKey stateKey = StateKey.PROTOBUF.parse(keyBytes);
                    final TokenID tokenId = stateKey.key().as();
                    final StateValue stateValue = StateValue.PROTOBUF.parse(
                            leafRecord.valueBytes().toReadableSequentialData(),
                            false,
                            false,
                            Codec.DEFAULT_MAX_DEPTH,
                            Integer.MAX_VALUE);
                    final Token token = stateValue.value().as();

                    final List<String> emptyRoleKeys = new ArrayList<>();
                    if (token.hasAdminKey() && isEmpty(token.adminKey())) {
                        emptyRoleKeys.add("adminKey");
                    }
                    if (token.hasKycKey() && isEmpty(token.kycKey())) {
                        emptyRoleKeys.add("kycKey");
                    }
                    if (token.hasFreezeKey() && isEmpty(token.freezeKey())) {
                        emptyRoleKeys.add("freezeKey");
                    }
                    if (token.hasWipeKey() && isEmpty(token.wipeKey())) {
                        emptyRoleKeys.add("wipeKey");
                    }
                    if (token.hasSupplyKey() && isEmpty(token.supplyKey())) {
                        emptyRoleKeys.add("supplyKey");
                    }
                    if (token.hasFeeScheduleKey() && isEmpty(token.feeScheduleKey())) {
                        emptyRoleKeys.add("feeScheduleKey");
                    }
                    if (token.hasPauseKey() && isEmpty(token.pauseKey())) {
                        emptyRoleKeys.add("pauseKey");
                    }
                    if (token.hasMetadataKey() && isEmpty(token.metadataKey())) {
                        emptyRoleKeys.add("metadataKey");
                    }

                    if (!emptyRoleKeys.isEmpty()) {
                        System.out.printf("Token %s has empty role keys: %s%n", tokenId, emptyRoleKeys);
                    }
                }
            } finally {
                virtualMap.release();
            }
        } catch (IOException | ParseException e) {
            logger.error("Failed to inspect token keys from saved state {}", savedStateDir, e);
        }
    }

    public static final Factory FACTORY = spec -> new BlockContentsValidator();

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        for (int i = 0, n = blocks.size(); i < n; i++) {
            try {
                validate(blocks.get(i), n - 1 - i);
            } catch (AssertionError err) {
                logger.error("Error validating block {}", blocks.get(i));
                throw err;
            }
        }
    }

    private void validate(Block block, final int blocksRemaining) {
        final var items = block.items();
        if (items.isEmpty()) {
            Assertions.fail("Block is empty");
        }

        if (items.size() <= 2) {
            Assertions.fail("Block contains insufficient number of block items");
        }

        validateBlockHeader(items.getFirst());

        if (BlockStreamValidator.isWrappedRecordBlock(items)) {
            validateWrappedRecordBlock(items, blocksRemaining);
        } else {
            validateNormalBlock(items, blocksRemaining);
        }
    }

    private void validateNormalBlock(@NonNull final List<BlockItem> items, final int blocksRemaining) {
        validateRounds(items.subList(1, items.size() - 1));

        if (blocksRemaining > REASONABLE_NUM_PENDING_PROOFS_AT_FREEZE
                && items.getLast().hasBlockProof()) {
            validateBlockProof(items.getLast());
        }
    }

    private void validateWrappedRecordBlock(@NonNull final List<BlockItem> items, final int blocksRemaining) {
        final long blockNumber = items.getFirst().blockHeaderOrThrow().number();
        boolean foundRecordFile = false;
        boolean foundFooter = false;
        boolean foundProof = false;

        for (int i = 1; i < items.size(); i++) {
            final var item = items.get(i);
            final var kind = item.item().kind();
            switch (kind) {
                case STATE_CHANGES -> {
                    Assertions.fail("WRB StateChanges found  at index " + i);
                }
                case RECORD_FILE -> {
                    if (foundRecordFile) {
                        Assertions.fail("WRB contains more than one RecordFileItem at index " + i);
                    }
                    validateRecordFileItem(item, i);
                    foundRecordFile = true;
                }
                case BLOCK_FOOTER -> {
                    if (foundFooter) {
                        Assertions.fail("WRB contains duplicate BlockFooter at index " + i);
                    }
                    if (!foundRecordFile) {
                        Assertions.fail("WRB BlockFooter found before RecordFileItem at index " + i);
                    }
                    if (!item.blockFooter().startOfBlockStateRootHash().equals(HASH_OF_ZERO)) {
                        Assertions.fail("WRB BlockFooter at index " + i
                                + " has start_of_block_state_root_hash != HASH_OF_ZERO");
                    }
                    foundFooter = true;
                }
                case BLOCK_PROOF -> {
                    if (!foundFooter) {
                        Assertions.fail("WRB BlockProof found before BlockFooter at index " + i);
                    }
                    validateWrappedBlockProof(item, i);
                    foundProof = true;
                }
                default -> Assertions.fail("WRB contains unexpected item type " + kind + " at index " + i);
            }
        }

        if (!foundRecordFile) {
            Assertions.fail("WRB is missing RecordFileItem");
        }
        if (!foundFooter) {
            Assertions.fail("WRB is missing BlockFooter");
        }
        if (!foundProof && blocksRemaining > REASONABLE_NUM_PENDING_PROOFS_AT_FREEZE) {
            Assertions.fail("WRB is missing BlockProof");
        }
    }

    private static void validateRecordFileItem(@NonNull final BlockItem item, final int index) {
        final var recordFile = item.recordFileOrThrow();
        if (!recordFile.hasCreationTime()) {
            Assertions.fail("WRB RecordFileItem at index " + index + " is missing creation_time");
        }
        if (!recordFile.hasRecordFileContents()) {
            Assertions.fail("WRB RecordFileItem at index " + index + " is missing record_file_contents");
        }
    }

    private static void validateWrappedBlockProof(@NonNull final BlockItem item, final int index) {
        final var proof = item.blockProofOrThrow();
        if (!proof.hasSignedRecordFileProof()) {
            Assertions.fail("WRB BlockProof at index " + index + " must use SignedRecordFileProof, found: "
                    + proof.proof().kind());
        }
        final var signedProof = proof.signedRecordFileProofOrThrow();
        final int version = signedProof.version();
        if (version != 2 && version != 5 && version != 6) {
            Assertions.fail("WRB SignedRecordFileProof at index " + index + " has invalid version " + version
                    + " (expected 2, 5, or 6)");
        }
        if (signedProof.recordFileSignatures().isEmpty()) {
            Assertions.fail("WRB SignedRecordFileProof at index " + index + " has no signatures");
        }
    }

    private static void validateBlockHeader(final BlockItem item) {
        if (!item.hasBlockHeader()) {
            Assertions.fail("Block must start with a block header");
        }
    }

    private static void validateBlockProof(final BlockItem item) {
        if (!item.hasBlockProof()) {
            Assertions.fail("Block must end with a block proof");
        }
    }

    private void validateRounds(final List<BlockItem> roundItems) {
        int currentIndex = 0;
        while (currentIndex < roundItems.size()) {
            currentIndex = validateSingleRound(roundItems, currentIndex);
        }
    }

    private int validateSingleRound(final List<BlockItem> items, int startIndex) {
        if (!items.get(startIndex).hasRoundHeader()) {
            logger.error("Expected round header at index {}, found: {}", startIndex, items.get(startIndex));
            Assertions.fail("Round must start with a round header");
        }
        int currentIndex = startIndex + 1;
        boolean insideEvent = false;
        boolean hasEventOrStateChange = false;
        while (currentIndex < items.size() && !items.get(currentIndex).hasRoundHeader()) {
            final var item = items.get(currentIndex);
            final var kind = item.item().kind();
            switch (kind) {
                case EVENT_HEADER -> hasEventOrStateChange = insideEvent = true;
                case BLOCK_FOOTER -> insideEvent = false;
                case STATE_CHANGES -> hasEventOrStateChange = true;
                case SIGNED_TRANSACTION ->
                    assertTrue(insideEvent, "Signed transaction found outside of event at index " + currentIndex);
                case RECORD_FILE, FILTERED_SINGLE_ITEM ->
                    Assertions.fail("Unexpected item type " + kind + " at index " + currentIndex);
                default -> {
                    // No-op
                }
            }
            currentIndex++;
        }
        if (!hasEventOrStateChange) {
            logger.error("Round starting at index {} has no event headers or state changes", startIndex);
            Assertions.fail("Round starting at index " + startIndex + " has no event headers or state changes");
        }
        return currentIndex;
    }
}
