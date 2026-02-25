// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Fail.fail;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.HashingOutputStream;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A helper class for reconstructing events from the block stream.
 */
public class BlockStreamEventBuilder {

    /** The blocks to read events from. */
    private final List<Block> blocks;

    /** Track event hashes by index within a single block  for parent lookups within a block */
    private final Map<Integer, PlatformEvent> eventIndexToEvent = new HashMap<>();

    /** Track events by event hash for parent lookups across blocks */
    private final Map<Hash, PlatformEvent> eventHashToEvent = new HashMap<>();

    /** A list of transactions to include in the current event */
    private final List<TransactionWrapper> currentTransactions = new ArrayList<>();

    /** A list of all reconstructed events */
    private final List<PlatformEvent> events = new ArrayList<>();

    /** The header of the event we are currently collecting transactions for */
    private EventHeader currentEventHeader = null;

    /** The index of the current event within the current block */
    private int eventIndexWithinBlock = 0;

    /** The set of parent hashes that reference events in another block. Useful for verifying calculated hash integrity. */
    private final Set<Hash> crossBlockParentHashes = new HashSet<>();

    /**
     * Constructor.
     *
     * @param blocks the blocks to read events from
     */
    public BlockStreamEventBuilder(@NonNull final List<Block> blocks) {
        this.blocks = requireNonNull(blocks);
    }

    /**
     * Transactions included in the event hash have a nonce of zero and are not scheduled transactions. Other
     * transactions (e.g. synthetic transactions) have a non-zero nonce and must not be included in the event to
     * calculate the correct event hash.
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
     * Returns a list of events constructed at the time of the call.
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
     * Returns the set of parent hashes that reference events outside the current block.
     *
     * @return the set of cross-block parent hashes
     */
    public Set<Hash> getCrossBlockParentHashes() {
        if (events.isEmpty()) {
            reconstructEventsFromBlocks();
        }
        return crossBlockParentHashes;
    }

    /**
     * Reconstructs GossipEvent objects from BlockItems across all blocks. Events are processed to handle parent index
     * references correctly.
     *
     * <p><strong>Important:</strong> The events in the returned list are in consensus order,
     * which also means they are in topological order. This ordering is critical for proper event hash chain validation,
     * as each event's hash must be calculated and stored before it can be referenced by subsequent events as a parent.
     */
    private void reconstructEventsFromBlocks() {
        for (final Block block : blocks) {
            startOfBlock();

            for (final BlockItem item : block.items()) {
                final var itemKind = item.item().kind();

                switch (itemKind) {
                    case EVENT_HEADER:
                        eventHeader(item.item().as());
                        break;
                    case SIGNED_TRANSACTION:
                        signedTransaction(item.item().as());
                        break;
                    case REDACTED_ITEM:
                        redactedItem(item.item().as());
                        break;
                    default:
                        // Skip other item types (block headers, proofs, etc.)
                        break;
                }
            }
            endOfBlock();
        }
    }

    private void redactedItem(@NonNull final RedactedItem redactedItem) {
        if (currentEventHeader == null) {
            fail("Unexpected redacted item without an active event header!");
        }
        currentTransactions.add(TransactionWrapper.ofTransactionHash(redactedItem.signedTransactionHash()));
    }

    private void startOfBlock() {
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
                fail("Unexpected transaction item without an active event header!");
            }
            currentTransactions.add(TransactionWrapper.ofTransaction(transactionBytes));
        }
    }

    private void endOfBlock() {
        if (currentEventHeader != null) {
            completeEvent();
        }
    }

    /**
     * Uses the information collected so far about an event to create an event.
     */
    private void completeEvent() {
        final PlatformEvent platformEvent =
                createEventFromData(currentEventHeader, new ArrayList<>(currentTransactions), eventIndexToEvent);
        eventIndexToEvent.put(eventIndexWithinBlock, platformEvent);
        eventHashToEvent.put(platformEvent.getHash(), platformEvent);
        events.add(platformEvent);
        currentEventHeader = null;
    }

    /**
     * Creates a PlatformEvent from EventHeader and transaction data. Resolves parent hashes using the event index
     * lookup.
     *
     * @param eventHeader the event header containing core event data
     * @param wrappedTransactions the list of wrapped transactions
     * @param eventIndexToEvent map for looking up parent events
     * @return reconstructed PlatformEvent with calculated hash
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
        final List<EventDescriptor> resolvedParents = resolveParentReferences(eventHeader.parents(), eventIndexToEvent);

        final List<Bytes> transactionBytes = new ArrayList<>();
        for (final TransactionWrapper wrappedTransaction : wrappedTransactions) {
            if (wrappedTransaction.isTransaction()) {
                transactionBytes.add(wrappedTransaction.transaction);
            } else {
                transactionBytes.add(wrappedTransaction.transactionHash);
            }
        }

        final Hash eventHash =
                new RedactedEventHasher().hashEvent(eventHeader.eventCore(), resolvedParents, wrappedTransactions);

        // Create GossipEvent with parents and transactions.
        final GossipEvent gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .signature(Bytes.EMPTY) // EventHeader doesn't have signature, use empty for now
                .parents(resolvedParents)
                .transactions(transactionBytes) // may not match original if there are filtered transactions
                .build();
        final PlatformEvent platformEvent = new PlatformEvent(gossipEvent, EventOrigin.STORAGE);
        platformEvent.setHash(eventHash);
        return platformEvent;
    }

    /**
     * Resolves parent EventDescriptor references from ParentEventReference objects. Handles both index-based references
     * (within block) and EventDescriptor references (outside block).
     *
     * @param parentReferences original parent references from EventHeader
     * @param eventIndexToHash lookup map for event hashes
     * @return resolved parent descriptors with proper hashes
     */
    private List<EventDescriptor> resolveParentReferences(
            @NonNull final List<ParentEventReference> parentReferences,
            @NonNull final Map<Integer, PlatformEvent> eventIndexToHash) {

        final List<EventDescriptor> resolvedParents = new ArrayList<>();

        for (final ParentEventReference parentRef : parentReferences) {
            switch (parentRef.parent().kind()) {
                case INDEX:
                    // Parent is referenced by index within the current block
                    final int parentIndex = parentRef.parent().as();
                    final PlatformEvent parent = eventIndexToHash.get(parentIndex);

                    if (parent != null) {
                        resolvedParents.add(parent.getDescriptor().eventDescriptor());
                    } else {
                        fail("Unable to find a parent event for index %d", parentIndex);
                    }
                    break;

                case EVENT_DESCRIPTOR:
                    // Parent is already an EventDescriptor (outside current block)
                    final EventDescriptor parentDescriptor = parentRef.parent().as();
                    resolvedParents.add(parentDescriptor);
                    final Hash parentHash = new Hash(parentDescriptor.hash());
                    if (!eventHashToEvent.containsKey(parentHash)) {
                        fail("Unable to find event matching parent hash %s", parentHash);
                    }
                    crossBlockParentHashes.add(new Hash(parentDescriptor.hash()));
                    break;

                default:
                    fail("Unknown parent reference kind: %s", parentRef.parent().kind());
                    break;
            }
        }

        return resolvedParents;
    }

    private static class RedactedEventHasher {
        /** The hashing stream for the event. */
        private final MessageDigest eventDigest = DigestType.SHA_384.buildDigest();

        final WritableSequentialData eventStream = new WritableStreamingData(new HashingOutputStream(eventDigest));
        /** The hashing stream for the transactions. */
        private final MessageDigest transactionDigest = DigestType.SHA_384.buildDigest();

        final WritableSequentialData transactionStream =
                new WritableStreamingData(new HashingOutputStream(transactionDigest));

        @NonNull
        public Hash hashEvent(
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
     * A wrapper for either a full transaction or just its hash.
     *
     * @param transaction the full transaction bytes, or null if only the hash is available
     * @param transactionHash the transaction hash, or null if the full transaction is available
     */
    private record TransactionWrapper(
            @Nullable Bytes transaction, @Nullable Bytes transactionHash) {
        public boolean isTransaction() {
            return transaction != null;
        }

        public static TransactionWrapper ofTransaction(@NonNull final Bytes transaction) {
            return new TransactionWrapper(transaction, null);
        }

        public static TransactionWrapper ofTransactionHash(@NonNull final Bytes transactionHash) {
            return new TransactionWrapper(null, transactionHash);
        }
    }
}
