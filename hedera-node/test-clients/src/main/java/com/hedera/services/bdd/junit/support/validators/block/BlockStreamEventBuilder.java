// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Fail.fail;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.ParentEventReference;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.crypto.PbjStreamHasher;
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
    private final List<Bytes> currentTransactions = new ArrayList<>();

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
     * Reconstructs GossipEvent objects from BlockItems across all blocks. Events are processed in order to handle
     * parent index references correctly.
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
                    default:
                        // Skip other item types (block headers, proofs, etc.)
                        break;
                }
            }
            endOfBlock();
        }
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
            currentTransactions.add(transactionBytes);
        }
    }

    private void endOfBlock() {
        if (currentEventHeader != null) {
            completeEvent();
        }
    }

    /**
     * Events included in the event hash have a nonce of zero and is not a scheduled transaction. Other transactions
     * (e.g. synthetic transactions) have a non-zero nonce and must not be included in the event to calculate
     * the correct event hash.
     *
     * @param transactionBytes the signed transaction bytes to check
     * @return true if the transaction should be included in the event, false otherwise
     */
    private static boolean isTransactionInEvent(@NonNull final Bytes transactionBytes) {
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
    private static TransactionBody getTransactionBody(@NonNull final Bytes transactionBytes) {
        try {
            final SignedTransaction signedTransaction = SignedTransaction.PROTOBUF.parse(transactionBytes);
            return TransactionBody.PROTOBUF.parse(signedTransaction.bodyBytes());
        } catch (final ParseException e) {
            throw new RuntimeException("Unable to parse transaction bytes", e);
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
     * @param transactions the list of transaction bytes
     * @param eventIndexToEvent map for looking up parent events
     * @return reconstructed PlatformEvent with calculated hash
     */
    private PlatformEvent createEventFromData(
            @NonNull final EventHeader eventHeader,
            @NonNull final List<Bytes> transactions,
            @NonNull final Map<Integer, PlatformEvent> eventIndexToEvent) {

        final EventCore eventCore = eventHeader.eventCore();
        if (eventCore == null) {
            throw new IllegalStateException("EventHeader missing EventCore data");
        }

        // Resolve parent hashes from EventHeader parent references
        final List<EventDescriptor> resolvedParents = resolveParentReferences(eventHeader.parents(), eventIndexToEvent);

        // Create GossipEvent with parents and transactions
        final GossipEvent gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .signature(Bytes.EMPTY) // EventHeader doesn't have signature, use empty for now
                .parents(resolvedParents)
                .transactions(transactions)
                .build();
        final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
        calculateEventHash(platformEvent);
        return platformEvent;
    }

    private void calculateEventHash(final PlatformEvent platformEvent) {
        final PbjStreamHasher hasher = new PbjStreamHasher();
        hasher.hashEvent(platformEvent);
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
                    // Unknown parent reference type, skip
                    break;
            }
        }

        return resolvedParents;
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
}
