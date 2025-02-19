/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static com.hedera.services.bdd.spec.HapiPropertySource.NODE_BLOCK_STREAM_DIR;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.services.bdd.junit.support.BlockStreamAccess;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Paths;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class BlockContentsValidator implements BlockStreamValidator {
    private static final Logger logger = LogManager.getLogger(BlockContentsValidator.class);

    public static void main(String[] args) {
        final var node0Dir = Paths.get("hedera-node/test-clients")
                .resolve(workingDirFor(0, "hapi"))
                .toAbsolutePath()
                .normalize();
        final var validator = new BlockContentsValidator();
        final var blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(
                node0Dir.resolve("data/block-streams/" + NODE_BLOCK_STREAM_DIR));
        validator.validateBlocks(blocks);
    }

    public static final Factory FACTORY = new Factory() {
        @NonNull
        @Override
        public BlockStreamValidator create(@NonNull final HapiSpec spec) {
            return new BlockContentsValidator();
        }
    };

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        for (int i = 0; i < blocks.size(); i++) {
            try {
                validate(blocks.get(i));
            } catch (AssertionError err) {
                logger.error("Error validating block {}", blocks.get(i));
                throw err;
            }
        }
    }

    private static void validate(Block block) {
        final var blockItems = block.items();

        // A block SHALL start with a `header`.
        if (!blockItems.getFirst().hasBlockHeader()) {
            Assertions.fail("Block does not start with a block header");
        }

        // A block SHALL end with a `state_proof`.
        if (!blockItems.getLast().hasBlockProof()) {
            Assertions.fail("Block does not end with a block proof");
        }

        // In general, a `block_header` SHALL be followed by an `round_header`
        if (!blockItems.get(1).hasRoundHeader()) {
            Assertions.fail("Block header not followed by an round header");
        }

        // In general, a `round_header` SHALL be followed by an `event_header`, but for hapiTestRestart we get
        // state change singleton update for BLOCK_INFO_VALUE because the post-restart State initialization changes
        // state before any event has reached consensus
        if (!blockItems.get(2).hasEventHeader() && !blockItems.get(2).hasStateChanges()) {
            Assertions.fail("Round header not followed by an event header or state changes");
        }

        if (blockItems.stream().noneMatch(BlockItem::hasEventTransaction)) { // block without a user transaction
            // A block with no user transactions contains a `block_header`, `event_headers`, `state_changes` and
            // `state_proof`.
            if (blockItems.stream()
                    .skip(2) // skip block_header and round_header
                    .limit(blockItems.size() - 3L) // skip state_proof
                    .anyMatch(item -> !item.hasEventHeader() && !item.hasStateChanges())) {
                Assertions.fail(
                        "Block with no user transactions should contain items of type `block_header`, `event_headers`, `state_changes` or `state_proof`");
            }

            return;
        }

        for (int i = 0; i < blockItems.size(); i++) {
            // An `event_transaction` SHALL be followed by a `transaction_result`.
            if (blockItems.get(i).hasEventTransaction()
                    && !blockItems.get(i + 1).hasTransactionResult()) {
                Assertions.fail("Event transaction not followed by a transaction result");
            }
        }
    }
}
