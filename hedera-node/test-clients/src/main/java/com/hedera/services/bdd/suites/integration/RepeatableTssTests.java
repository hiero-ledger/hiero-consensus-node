// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_TSS_CONTROL;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.TssVerbs.startIgnoringTssSignatureRequests;
import static com.hedera.services.bdd.spec.utilops.TssVerbs.stopIgnoringTssSignatureRequests;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockStreamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(2)
@Tag(INTEGRATION)
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableTssTests {
    /**
     * Validates behavior of the {@link BlockStreamManager} under specific conditions related to signature requests
     * and block creation.
     *
     * <p>This test follows three main steps:</p>
     * <ul>
     *     <li>Instructs the fake TSS base service to start ignoring signature requests and
     *     produces several blocks. In this scenario, each transaction is placed into its own round
     *     since the service is operating in repeatable mode.</li>
     *     <li>Verifies that no blocks are written, as no block proofs are available, which is the
     *     expected behavior when the service is ignoring signature requests.</li>
     *     <li>Reactivates the fake TSS base service, creates another block, and verifies that
     *     the {@link BlockStreamManager} processes pending block proofs. It checks that the expected
     *     blocks are written within a brief period after the service resumes normal behavior.</li>
     * </ul>
     *
     * <p>The test ensures that block production halts when block proofs are unavailable and
     * verifies that the system can catch up on pending proofs when the service resumes.</p>
     */
    @RepeatableHapiTest(NEEDS_TSS_CONTROL)
    Stream<DynamicTest> blockStreamManagerCatchesUpWithIndirectProofs() {
        final var indirectProofsAssertion = new IndirectProofsAssertion(2);
        return hapiTest(withOpContext((spec, opLog) -> {
            if (spec.startupProperties().getStreamMode("blockStream.streamMode") != RECORDS) {
                allRunFor(
                        spec,
                        startIgnoringTssSignatureRequests(),
                        blockStreamMustIncludePassFrom(ignore -> indirectProofsAssertion),
                        // Each transaction is placed into its own round and hence block with default config
                        cryptoCreate("firstIndirectProof"),
                        cryptoCreate("secondIndirectProof"),
                        stopIgnoringTssSignatureRequests(),
                        doAdhoc(indirectProofsAssertion::startExpectingBlocks),
                        cryptoCreate("directProof"));
            }
        }));
    }

    /**
     * A {@link BlockStreamAssertion} used to verify the presence of some number {@code n} of expected indirect proofs
     * in the block stream. When constructed, it assumes proof construction is paused, and fails if any block
     * is written in this stage.
     * <p>
     * After {@link #startExpectingBlocks()} is called, the assertion will verify that the next {@code n} proofs are
     * indirect proofs with the correct number of sibling hashes; and are followed by a direct proof, at which point
     * it passes.
     */
    private static class IndirectProofsAssertion implements BlockStreamAssertion {
        private boolean proofsArePaused;
        private int remainingIndirectProofs;

        public IndirectProofsAssertion(final int remainingIndirectProofs) {
            this.proofsArePaused = true;
            this.remainingIndirectProofs = remainingIndirectProofs;
        }

        /**
         * Signals that the assertion should now expect proofs to be created, hence blocks to be written.
         */
        public void startExpectingBlocks() {
            proofsArePaused = false;
        }

        @Override
        public boolean test(@NonNull final Block block) throws AssertionError {
            if (proofsArePaused) {
                throw new AssertionError("No blocks should be written when proofs are unavailable");
            } else {
                final var items = block.items();
                final var proofItem = items.getLast();
                assertTrue(proofItem.hasBlockProof(), "Block proof is expected as the last item");
                final var proof = proofItem.blockProofOrThrow();
                if (remainingIndirectProofs == 0) {
                    assertTrue(
                            proof.siblingHashes().isEmpty(), "No sibling hashes should be present on a direct proof");
                    return true;
                } else {
                    assertEquals(
                            // Two sibling hashes per indirection level
                            2 * remainingIndirectProofs,
                            proof.siblingHashes().size(),
                            "Wrong number of sibling hashes for indirect proof");
                }
                remainingIndirectProofs--;
                return false;
            }
        }
    }
}
