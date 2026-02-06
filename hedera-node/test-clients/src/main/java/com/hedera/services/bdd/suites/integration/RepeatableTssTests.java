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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
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
        final var indirectProofsAssertion = new IndirectProofsAssertion(3);
        return hapiTest(withOpContext((spec, opLog) -> {
            if (spec.startupProperties().getStreamMode("blockStream.streamMode") != RECORDS) {
                allRunFor(
                        spec,
                        doAdhoc(() -> spec.repeatableEmbeddedHederaOrThrow().setRoundDuration(Duration.ofSeconds(2))),
                        sleepForSeconds(3L),
                        startIgnoringTssSignatureRequests(),
                        blockStreamMustIncludePassFrom(ignore -> indirectProofsAssertion),
                        // Each transaction is placed into its own round and hence block
                        cryptoCreate("somebody").yahcliLogging(),
                        sleepForSeconds(3L),
                        cryptoCreate("firstIndirectProof"),
                        sleepForSeconds(3L),
                        cryptoCreate("secondIndirectProof"),
                        sleepForSeconds(3L),
                        cryptoCreate("thirdIndirectProof"),
                        sleepForSeconds(3L),
                        stopIgnoringTssSignatureRequests(),
                        doAdhoc(indirectProofsAssertion::startExpectingBlocks),
                        cryptoCreate("directProof"),
                        sleepForSeconds(3L),
                        // Reset to default round duration so other tests aren't affected
                        doAdhoc(() -> spec.repeatableEmbeddedHederaOrThrow().resetRoundDuration()));
            }
        }));
    }

    /**
     * A {@link BlockStreamAssertion} used to verify the presence of some number {@code n} of expected indirect proofs
     * in the block stream. When constructed, it assumes proof construction is paused, and fails if any block
     * is written in this stage.
     * <p>
     * After {@link #startExpectingBlocks()} is called, the assertion will verify that the next {@code n} proofs are
     * valid indirect state proofs, and that they are followed by a direct proof, at which point it passes.
     * <p>
     * Note that the conditions checking the state proof items are perhaps more stringent than typically necessary.
     * State proofs, while not verified in their entirety here, nonetheless must meet strict criteria. Normally it
     * would likely suffice for this test to focus on the transition between direct -> indirect -> direct proofs,
     * with only some basic sanity checks on the blocks themselves to determine whether a semblance of the indirect
     * proof is present. However, due to the unpredictable nature of actual indirect proof creation during a typical
     * test run, we take advantage of the opportunity here for a more strict verification.
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
                    assertValidSignedProof(proof);
                    return true;
                } else if (proof.hasSignedBlockProof()) {
                    // We don't want any direct proofs counting towards the indirect proof count
                    return false;
                } else {
                    assertTrue(proof.hasBlockStateProof());
                    assertValidStateProof(proof);

                    remainingIndirectProofs--;
                }

                return false;
            }
        }
    }

    private static void assertValidSignedProof(@NonNull final BlockProof proof) {
        requireNonNull(proof);

        assertTrue(proof.hasSignedBlockProof(), "Expected signed block proof to be present");
        final var signedProof = proof.signedBlockProofOrThrow();
        final var blockSig = signedProof.blockSignature();
        assertNotNull(blockSig, "Expected non-null block signature in signed block proof");
        assertNotEquals(Bytes.EMPTY, blockSig, "Expected non-empty block signature in signed block proof");
        assertEquals(48, blockSig.length(), "Expected block signature to be 48 bytes long");
    }

    private static void assertValidStateProof(@NonNull final BlockProof proof) {
        requireNonNull(proof);

        // 1. Every indirect proof also has a signed block proof
        final var stateProof = proof.blockStateProofOrThrow();
        assertTrue(proof.blockStateProofOrThrow().hasSignedBlockProof());

        // 2. The number of merkle paths is nonzero and divisible by three (three paths per block)
        final var merklePaths = stateProof.paths();
        assertFalse(merklePaths.isEmpty(), "Expected non-empty Merkle paths in block state proof");
        assertEquals(
                0,
                merklePaths.size() % 3,
                "Expected three Merkle paths per block, but got %s (not a multiple of 3)"
                        .formatted(merklePaths.size()));

        // 3. The first merkle path is a leaf with the block's consensus timestamp
        final var mp1 = merklePaths.getFirst();
        assertTrue(mp1.hasTimestampLeaf(), "Expected first Merkle path to have a timestamp");
        assertNotNull(mp1.timestampLeaf());
        final var mp1NextPath = mp1.nextPathIndex();
        assertTrue(
                mp1NextPath >= 0 && mp1NextPath < merklePaths.size(),
                "Expected valid next path index in first Merkle path");

        // 4. The first path's next index correctly points to the third merkle path, which should be immediately
        // preceded by the corresponding second merkle path
        final var mp2 = merklePaths.get(mp1NextPath - 1);
        assertFalse(mp2.hasTimestampLeaf());
        assertTrue(mp2.hasHash());
        assertEquals(
                BlockStreamManager.NUM_SIBLINGS_PER_BLOCK + 1, mp2.siblings().size());

        // 5. As above, the first path's next index points directly to path 3, which should be either an internal node
        // or the root
        final var mp3 = merklePaths.get(mp1NextPath);
        assertTrue(mp3.siblings().isEmpty());
        assertFalse(mp3.hasTimestampLeaf());
        final var mp3NextPath = mp3.nextPathIndex();
        assertTrue(
                mp3NextPath == -1 || (mp3NextPath >= 0 && mp3NextPath < merklePaths.size()),
                "Expected valid next path index in third Merkle path");

        // 6. The final path should be the block's root hash
        final var rootMp3 = merklePaths.getLast();
        assertTrue(rootMp3.siblings().isEmpty(), "Expected root Merkle path to have no siblings");
        assertFalse(rootMp3.hasTimestampLeaf());
        assertEquals(MerklePath.ContentOneOfType.UNSET, rootMp3.content().kind());
        assertEquals(-1, rootMp3.nextPathIndex(), "Expected final Merkle path to terminate with -1");
    }
}
