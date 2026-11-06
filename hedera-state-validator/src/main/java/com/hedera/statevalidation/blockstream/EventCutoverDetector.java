// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.ParentEventReference;
import com.hedera.hapi.platform.event.EventDescriptor;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.hiero.base.crypto.DigestType;
import org.hiero.consensus.model.event.EventHashFactory;

/**
 * Detects the event hash cutover (SHA-384 to SHA-256) from the block stream itself, so that
 * {@link BlocksToPcesWorkflow} can process a stream spanning the cutover without extra input.
 *
 * <p>Per the platform's {@link EventHashFactory}, events with a birth round lower than the event
 * cutover minimum birth round are hashed with SHA-384, all others with SHA-256. Every cross-block
 * ({@code EVENT_DESCRIPTOR}) parent reference carries the parent's birth round and hash, so it bounds
 * the cutover: a SHA-384 parent at birth round {@code x} means the cutover is above {@code x}, and a
 * SHA-256 parent at birth round {@code y} means the cutover is at or below {@code y}. The cutover is
 * therefore in {@code (maxSha384, minSha256]} over all references in the stream:
 * <ul>
 *   <li>only SHA-384 parents: no cutover, all events are hashed with SHA-384;</li>
 *   <li>only SHA-256 parents: all events are hashed with SHA-256;</li>
 *   <li>both: the cutover is {@code minSha256}. Reconstructed events with a birth round strictly
 *       between the bounds are ambiguous, and detection fails rather than guess.</li>
 * </ul>
 *
 * <p><b>Limitation.</b> Events at the edges of the stream may be hashed with a digest type that no
 * cross-block reference reveals. If the cutover happens right before the end of the stream, before any
 * SHA-256 parent is referenced across blocks, the trailing post-cutover events are treated as SHA-384
 * (and symmetrically at the start of the stream). This can only affect a few blocks at the edges of the
 * extraction window.
 */
final class EventCutoverDetector {

    /** Cutover value meaning "no cutover": all events are hashed with SHA-384. */
    static final long NO_CUTOVER = Long.MAX_VALUE;

    /**
     * Cutover evidence collected from a single block.
     *
     * @param maxSha384ParentBirthRound the highest birth round of a SHA-384 cross-block parent, or
     *     {@code -1} if there is none
     * @param minSha256ParentBirthRound the lowest birth round of a SHA-256 cross-block parent, or
     *     {@code Long.MAX_VALUE} if there is none
     * @param eventBirthRounds the distinct birth rounds of the block's events, sorted ascending
     */
    record BlockEvidence(
            long maxSha384ParentBirthRound, long minSha256ParentBirthRound, @NonNull long[] eventBirthRounds) {}

    private EventCutoverDetector() {}

    /**
     * Collects the cutover evidence of a single block.
     *
     * @param block the block to scan
     * @return the block's cutover evidence
     * @throws IllegalStateException if a parent hash is neither a SHA-384 nor a SHA-256 hash
     */
    @NonNull
    static BlockEvidence scan(@NonNull final Block block) {
        long maxSha384 = -1;
        long minSha256 = Long.MAX_VALUE;
        final LongStream.Builder eventBirthRounds = LongStream.builder();
        for (final BlockItem item : block.items()) {
            if (!item.hasEventHeader()) {
                continue;
            }
            final EventHeader eventHeader = item.eventHeaderOrThrow();
            eventBirthRounds.add(eventHeader.eventCoreOrThrow().birthRound());
            for (final ParentEventReference parentRef : eventHeader.parents()) {
                if (parentRef.parent().kind() != ParentEventReference.ParentOneOfType.EVENT_DESCRIPTOR) {
                    continue;
                }
                final EventDescriptor parent = parentRef.parent().as();
                final long length = parent.hash().length();
                if (length == DigestType.SHA_384.digestLength()) {
                    maxSha384 = Math.max(maxSha384, parent.birthRound());
                } else if (length == DigestType.SHA_256.digestLength()) {
                    minSha256 = Math.min(minSha256, parent.birthRound());
                } else {
                    throw new IllegalStateException(String.format(
                            "Parent event (creator %d, birth round %d) has a %d-byte hash, which is neither a "
                                    + "SHA-384 nor a SHA-256 hash",
                            parent.creatorNodeId(), parent.birthRound(), length));
                }
            }
        }
        return new BlockEvidence(
                maxSha384, minSha256, eventBirthRounds.build().distinct().sorted().toArray());
    }

    /**
     * Resolves the event cutover minimum birth round from the evidence of all blocks in the stream.
     *
     * @param allBlocks the evidence of every block in the stream, used to bound the cutover
     * @param reconstructedBlocks the evidence of the blocks whose events will be reconstructed, checked
     *     for events whose digest type is ambiguous
     * @return the lowest birth round of SHA-256 events, {@code 0} if all events are SHA-256, or
     *     {@link #NO_CUTOVER} if all events are SHA-384
     * @throws IllegalStateException if the references contradict the cutover rule, or reconstructed events
     *     have a birth round for which the digest type cannot be determined
     */
    static long resolve(
            @NonNull final List<BlockEvidence> allBlocks, @NonNull final List<BlockEvidence> reconstructedBlocks) {
        final long maxSha384 = allBlocks.stream()
                .mapToLong(BlockEvidence::maxSha384ParentBirthRound)
                .max()
                .orElse(-1);
        final long minSha256 = allBlocks.stream()
                .mapToLong(BlockEvidence::minSha256ParentBirthRound)
                .min()
                .orElse(Long.MAX_VALUE);

        if (minSha256 == Long.MAX_VALUE) {
            return NO_CUTOVER;
        }
        if (maxSha384 == -1) {
            return 0;
        }
        if (maxSha384 >= minSha256) {
            throw new IllegalStateException(String.format(
                    "Block stream contradicts the event hash cutover rule: a SHA-384 parent has birth round %d, "
                            + "but a SHA-256 parent has the lower or equal birth round %d",
                    maxSha384, minSha256));
        }

        final long ambiguousFrom = maxSha384 + 1;
        final long ambiguousTo = minSha256 - 1;
        final long[] ambiguousBirthRounds = reconstructedBlocks.stream()
                .flatMapToLong(evidence -> LongStream.of(evidence.eventBirthRounds()))
                .filter(birthRound -> birthRound >= ambiguousFrom && birthRound <= ambiguousTo)
                .distinct()
                .sorted()
                .toArray();
        if (ambiguousBirthRounds.length > 0) {
            throw new IllegalStateException(String.format(
                    "Cannot determine the event hash cutover: it lies between birth rounds %d and %d, and events "
                            + "with birth rounds %s in this range are never referenced across blocks, so their "
                            + "digest type (SHA-384 or SHA-256) is unknown",
                    ambiguousFrom,
                    minSha256,
                    Arrays.toString(ambiguousBirthRounds)));
        }
        return minSha256;
    }
}
