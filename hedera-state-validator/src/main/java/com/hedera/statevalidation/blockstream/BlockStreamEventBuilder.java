// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.RedactedItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.ParentEventReference;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.HashingOutputStream;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Reconstructs events from the block stream.
 *
 * <p>This is a copy of
 * {@code com.hedera.services.bdd.junit.support.validators.block.BlockStreamEventBuilder} from the
 * {@code test-clients} module, adapted for use in this module. {@code test-clients} is
 * a test artifact and must not be a dependency of {@code hedera-state-validator}, so — following the
 * same approach already used for the block-manipulation logic in {@code BlockStreamRecoveryWorkflow}
 * — the logic is copied here. The only behavioral change is that assertion failures (originally
 * {@code org.assertj.core.api.Fail.fail}) are replaced with thrown exceptions, since this is not a
 * test.
 *
 * <p>The reconstructed events are unsigned: the block stream does not carry the creator's
 * {@code GossipEvent.signature}, so the signature field is left empty. This does not affect the
 * event hash, which is computed only over {@code EventCore}, parent descriptors, and the
 * (double-hashed) event transactions.
 */
public class BlockStreamEventBuilder {

    /** The blocks to read events from. */
    private final List<Block> blocks;

    /** Track event hashes by index within a single block, for parent lookups within a block. */
    private final Map<Integer, PlatformEvent> eventIndexToEvent = new HashMap<>();

    /** Transactions to include in the current event. */
    private final List<TransactionWrapper> currentTransactions = new ArrayList<>();

    /** All reconstructed events. */
    private final List<PlatformEvent> events = new ArrayList<>();

    /** The header of the event we are currently collecting transactions for. */
    private EventHeader currentEventHeader = null;

    /** The index of the current event within the current block. */
    private int eventIndexWithinBlock = 0;

    /** Cross-block parent references with context about both parent and child events. */
    private final List<CrossBlockParentRef> crossBlockParentRefs = new ArrayList<>();

    /** Current block index, used for cross-block parent tracking. */
    private int currentBlockIndex = 0;

    /**
     * Details about a cross-block parent reference: the parent's descriptor and the child event
     * that references it.
     *
     * @param parentDescriptor the parent event's descriptor (hash, creator, birth round)
     * @param childCreatorId the creator node ID of the child event
     * @param childBirthRound the birth round of the child event
     * @param childBlockIndex the block index containing the child event
     */
    public record CrossBlockParentRef(
            @NonNull EventDescriptor parentDescriptor,
            long childCreatorId,
            long childBirthRound,
            int childBlockIndex) {}

    /**
     * Constructor.
     *
     * @param blocks the blocks to read events from
     */
    public BlockStreamEventBuilder(@NonNull final List<Block> blocks) {
        this.blocks = requireNonNull(blocks);
    }

    /**
     * Transactions included in the event hash have a nonce of zero and are not scheduled
     * transactions. Other transactions (e.g. synthetic transactions) have a non-zero nonce and must
     * not be included in the event to calculate the correct event hash.
     *
     * @param transactionBytes the signed transaction bytes to check
     * @return true if the transaction should be included in the event, false otherwise
     */
    public static boolean isTransactionInEvent(@NonNull final Bytes transactionBytes) {
        final TransactionBody transactionBody = getTransactionBody(transactionBytes);
        final TransactionID transactionId = transactionBody.transactionIDOrThrow();
        return transactionId.nonce() == 0 && !transactionId.scheduled();
    }

    /**
     * Parses and returns the transaction body from PBJ bytes.
     *
     * @param transactionBytes the transaction bytes
     * @return the parsed transaction body
     */
    public static TransactionBody getTransactionBody(@NonNull final Bytes transactionBytes) {
        try {
            final SignedTransaction signedTransaction = SignedTransaction.PROTOBUF.parse(transactionBytes);
            return TransactionBody.PROTOBUF.parse(signedTransaction.bodyBytes());
        } catch (final ParseException e) {
            throw new RuntimeException("Unable to parse transaction bytes", e);
        }
    }

    /**
     * Returns the events constructed from the blocks. The events are in consensus order, which is
     * also a valid topological order.
     *
     * @return the reconstructed and hashed events
     */
    public List<PlatformEvent> getEvents() {
        if (events.isEmpty()) {
            reconstructEventsFromBlocks();
        }
        return events;
    }

    /**
     * Reconstructs {@link GossipEvent} objects from {@link BlockItem}s across all blocks.
     *
     * <p><strong>Important:</strong> the events in the returned list are in consensus order, which
     * also means they are in topological order. This ordering is critical: each event's hash must be
     * calculated and stored before it can be referenced by a subsequent event as a parent.
     */
    private void reconstructEventsFromBlocks() {
        for (int blockIdx = 0; blockIdx < blocks.size(); blockIdx++) {
            final Block block = blocks.get(blockIdx);
            startOfBlock(blockIdx);

            for (final BlockItem item : block.items()) {
                final var itemKind = item.item().kind();
                switch (itemKind) {
                    case EVENT_HEADER -> eventHeader(item.item().as());
                    case SIGNED_TRANSACTION -> signedTransaction(item.item().as());
                    case REDACTED_ITEM -> redactedItem(item.item().as());
                    default -> {
                        // Skip other item types (block headers, proofs, etc.)
                    }
                }
            }
            endOfBlock();
        }
    }

    private void redactedItem(@NonNull final RedactedItem redactedItem) {
        if (currentEventHeader == null) {
            throw new IllegalStateException("Unexpected redacted item without an active event header!");
        }
        currentTransactions.add(TransactionWrapper.ofTransactionHash(redactedItem.signedTransactionHash()));
    }

    private void startOfBlock(final int blockIndex) {
        currentBlockIndex = blockIndex;
        eventIndexWithinBlock = 0;
        currentTransactions.clear();
        eventIndexToEvent.clear();
    }

    private void eventHeader(@NonNull final EventHeader eventHeader) {
        if (currentEventHeader != null) {
            completeEvent();
            eventIndexWithinBlock++;
        }
        // Start new event
        currentEventHeader = eventHeader;
        currentTransactions.clear();
    }

    private void signedTransaction(@NonNull final Bytes transactionBytes) {
        if (isTransactionInEvent(transactionBytes)) {
            // When performing a network transplant, there may be transactions with a non-zero nonce
            // outside an event header. These transactions should be ignored. But transactions with
            // a zero nonce outside an event header should never happen.
            if (currentEventHeader == null) {
                throw new IllegalStateException("Unexpected transaction item without an active event header!");
            }
            currentTransactions.add(TransactionWrapper.ofTransaction(transactionBytes));
        }
    }

    private void endOfBlock() {
        if (currentEventHeader != null) {
            completeEvent();
        }
    }

    /** Uses the information collected so far about an event to create an event. */
    private void completeEvent() {
        final PlatformEvent platformEvent =
                createEventFromData(currentEventHeader, new ArrayList<>(currentTransactions), eventIndexToEvent);
        eventIndexToEvent.put(eventIndexWithinBlock, platformEvent);
        events.add(platformEvent);
        currentEventHeader = null;
    }

    /**
     * Creates a {@link PlatformEvent} from {@link EventHeader} and transaction data. Resolves parent
     * hashes using the event index lookup.
     *
     * @param eventHeader the event header containing core event data
     * @param wrappedTransactions the list of wrapped transactions
     * @param eventIndexToEvent map for looking up parent events
     * @return reconstructed {@link PlatformEvent} with calculated hash
     */
    private PlatformEvent createEventFromData(
            @NonNull final EventHeader eventHeader,
            @NonNull final List<TransactionWrapper> wrappedTransactions,
            @NonNull final Map<Integer, PlatformEvent> eventIndexToEvent) {

        final EventCore eventCore = eventHeader.eventCore();
        if (eventCore == null) {
            throw new IllegalStateException("EventHeader missing EventCore data");
        }

        // Resolve parent hashes from EventHeader parent references
        final List<EventDescriptor> resolvedParents =
                resolveParentReferences(eventHeader.parents(), eventIndexToEvent, eventCore);

        final List<Bytes> transactionBytes = new ArrayList<>();
        for (final TransactionWrapper wrappedTransaction : wrappedTransactions) {
            if (wrappedTransaction.isTransaction()) {
                transactionBytes.add(wrappedTransaction.transaction());
            } else {
                transactionBytes.add(wrappedTransaction.transactionHash());
            }
        }

        final Hash eventHash = new RedactedEventHasher().hashEvent(eventCore, resolvedParents, wrappedTransactions);

        // Create GossipEvent with parents and transactions. The signature is empty because the block
        // stream does not carry the creator's signature; this does not affect the event hash.
        final GossipEvent gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .signature(Bytes.EMPTY)
                .parents(resolvedParents)
                .transactions(transactionBytes)
                .build();
        final PlatformEvent platformEvent = new PlatformEvent(gossipEvent, EventOrigin.STORAGE);
        platformEvent.setHash(eventHash);
        return platformEvent;
    }

    /**
     * Resolves parent {@link EventDescriptor} references from {@link ParentEventReference} objects.
     * Handles both index-based references (within block) and {@link EventDescriptor} references
     * (outside block).
     *
     * @param parentReferences original parent references from {@link EventHeader}
     * @param eventIndexToEvent lookup map for events within the current block
     * @param childEventCore the {@link EventCore} of the child event referencing these parents
     * @return resolved parent descriptors with proper hashes
     */
    private List<EventDescriptor> resolveParentReferences(
            @NonNull final List<ParentEventReference> parentReferences,
            @NonNull final Map<Integer, PlatformEvent> eventIndexToEvent,
            @NonNull final EventCore childEventCore) {

        final List<EventDescriptor> resolvedParents = new ArrayList<>();

        for (final ParentEventReference parentRef : parentReferences) {
            switch (parentRef.parent().kind()) {
                case INDEX -> {
                    // Parent is referenced by index within the current block
                    final int parentIndex = parentRef.parent().as();
                    final PlatformEvent parent = eventIndexToEvent.get(parentIndex);
                    if (parent != null) {
                        resolvedParents.add(parent.getDescriptor().eventDescriptor());
                    } else {
                        throw new IllegalStateException("Unable to find a parent event for index " + parentIndex);
                    }
                }
                case EVENT_DESCRIPTOR -> {
                    // Parent is already an EventDescriptor (outside current block). Collect the
                    // reference with context for later validation.
                    final EventDescriptor parentDescriptor = parentRef.parent().as();
                    resolvedParents.add(parentDescriptor);
                    crossBlockParentRefs.add(new CrossBlockParentRef(
                            parentDescriptor,
                            childEventCore.creatorNodeId(),
                            childEventCore.birthRound(),
                            currentBlockIndex));
                }
                default ->
                    throw new IllegalStateException("Unknown parent reference kind: "
                            + parentRef.parent().kind());
            }
        }

        return resolvedParents;
    }

    /**
     * Recomputes the event hash, mirroring {@code PbjStreamHasher}, with the extension that a
     * redacted transaction (bytes absent) contributes its stored hash directly instead of being
     * re-hashed from bytes.
     */
    private static final class RedactedEventHasher {
        private final MessageDigest eventDigest = DigestType.SHA_384.buildDigest();
        private final WritableSequentialData eventStream =
                new WritableStreamingData(new HashingOutputStream(eventDigest));
        private final MessageDigest transactionDigest = DigestType.SHA_384.buildDigest();
        private final WritableSequentialData transactionStream =
                new WritableStreamingData(new HashingOutputStream(transactionDigest));

        @NonNull
        Hash hashEvent(
                @NonNull final EventCore eventCore,
                @NonNull final List<EventDescriptor> parents,
                @NonNull final List<TransactionWrapper> wrappedTransactions) {
            try {
                EventCore.PROTOBUF.write(eventCore, eventStream);
                for (final EventDescriptor parent : parents) {
                    EventDescriptor.PROTOBUF.write(parent, eventStream);
                }
                for (final TransactionWrapper transaction : wrappedTransactions) {
                    processTransactionHash(transaction);
                }
            } catch (final IOException e) {
                throw new RuntimeException("An exception occurred while trying to hash an event!", e);
            }
            return new Hash(eventDigest.digest(), DigestType.SHA_384);
        }

        private void processTransactionHash(@NonNull final TransactionWrapper wrappedTransaction) {
            if (wrappedTransaction.isTransaction()) {
                transactionStream.writeBytes(wrappedTransaction.transaction());
                final byte[] hash = transactionDigest.digest();
                eventStream.writeBytes(hash);
            } else {
                eventStream.writeBytes(wrappedTransaction.transactionHash());
            }
        }
    }

    /**
     * A wrapper for either a full transaction or just its hash (when redacted).
     *
     * @param transaction the full transaction bytes, or null if only the hash is available
     * @param transactionHash the transaction hash, or null if the full transaction is available
     */
    private record TransactionWrapper(
            @Nullable Bytes transaction, @Nullable Bytes transactionHash) {
        boolean isTransaction() {
            return transaction != null;
        }

        static TransactionWrapper ofTransaction(@NonNull final Bytes transaction) {
            return new TransactionWrapper(transaction, null);
        }

        static TransactionWrapper ofTransactionHash(@NonNull final Bytes transactionHash) {
            return new TransactionWrapper(null, transactionHash);
        }
    }
}
