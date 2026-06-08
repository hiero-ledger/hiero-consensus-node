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
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.HashingOutputStream;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Reconstructs events from the block stream in a streaming fashion.
 *
 * <p>Events are reconstructed one block at a time via {@link #processBlock(Block, Consumer)}.
 * Each completed event is immediately delivered to the consumer, so the caller can write it to
 * PCES, validate it, or otherwise handle it without accumulating all events in memory. Only the
 * current block's events are held in memory (for in-block parent index resolution); cross-block
 * parent references use {@link EventDescriptor}s directly from the stream and require no lookup
 * against previously reconstructed events.
 *
 * <p>This is a copy of
 * {@code com.hedera.services.bdd.junit.support.validators.block.BlockStreamEventBuilder} from the
 * {@code test-clients} module, adapted for production use (no assertj dependency) and refactored
 * from a batch API ({@code List<Block>} in, {@code List<PlatformEvent>} out) to a streaming API
 * (one block at a time, events emitted via callback).
 *
 * <p>The reconstructed events are unsigned: the block stream does not carry the creator's
 * {@code GossipEvent.signature}, so the signature field is left empty. This does not affect the
 * event hash, which is computed only over {@code EventCore}, parent descriptors, and the
 * (double-hashed) event transactions.
 */
public class BlockStreamEventBuilder {

    /** Track events by index within the current block, for in-block parent lookups. */
    private final Map<Integer, PlatformEvent> eventIndexToEvent = new HashMap<>();

    /** Transactions collected for the event currently being assembled. */
    private final List<Bytes> currentTransactions = new ArrayList<>();

    /** The header of the event we are currently collecting transactions for. */
    private EventHeader currentEventHeader = null;

    /** The index of the current event within the current block. */
    private int eventIndexWithinBlock = 0;

    /**
     * Processes a single block, reconstructing events from its items and delivering each completed
     * event to the given consumer. Only the current block's events are held in memory; once this
     * method returns, the block and its events can be garbage-collected.
     *
     * @param block the block to process
     * @param eventConsumer receives each reconstructed {@link PlatformEvent} as it is completed
     */
    public void processBlock(@NonNull final Block block, @NonNull final Consumer<PlatformEvent> eventConsumer) {
        requireNonNull(block);
        requireNonNull(eventConsumer);

        startOfBlock();

        for (final BlockItem item : block.items()) {
            final var itemKind = item.item().kind();
            switch (itemKind) {
                case EVENT_HEADER -> eventHeader(item.item().as(), eventConsumer);
                case SIGNED_TRANSACTION -> signedTransaction(item.item().as());
                case REDACTED_ITEM -> redactedItem(item.item().as());
                default -> {
                    // Skip other item types (block headers, round headers, proofs, etc.)
                }
            }
        }

        endOfBlock(eventConsumer);
    }

    /**
     * Transactions included in the event hash have a nonce of zero and are not scheduled.
     * Other transactions (e.g. synthetic transactions) have a non-zero nonce and must not be
     * included in the event to calculate the correct event hash.
     */
    public static boolean isTransactionInEvent(@NonNull final Bytes transactionBytes) {
        final TransactionBody transactionBody = getTransactionBody(transactionBytes);
        final TransactionID transactionId = transactionBody.transactionIDOrThrow();
        return transactionId.nonce() == 0 && !transactionId.scheduled();
    }

    /**
     * Parses and returns the transaction body from PBJ bytes.
     */
    public static TransactionBody getTransactionBody(@NonNull final Bytes transactionBytes) {
        try {
            final SignedTransaction signedTransaction = SignedTransaction.PROTOBUF.parse(transactionBytes);
            return TransactionBody.PROTOBUF.parse(signedTransaction.bodyBytes());
        } catch (final ParseException e) {
            throw new RuntimeException("Unable to parse transaction bytes", e);
        }
    }

    // ---- Internal block-processing methods ----

    private void startOfBlock() {
        eventIndexWithinBlock = 0;
        currentTransactions.clear();
        eventIndexToEvent.clear();
    }

    private void eventHeader(@NonNull final EventHeader eventHeader, @NonNull final Consumer<PlatformEvent> consumer) {
        if (currentEventHeader != null) {
            completeEvent(consumer);
            eventIndexWithinBlock++;
        }
        currentEventHeader = eventHeader;
        currentTransactions.clear();
    }

    private void signedTransaction(@NonNull final Bytes transactionBytes) {
        if (isTransactionInEvent(transactionBytes)) {
            if (currentEventHeader == null) {
                throw new IllegalStateException("Unexpected transaction item without an active event header!");
            }
            currentTransactions.add(transactionBytes);
        }
    }

    private void redactedItem(@NonNull final RedactedItem redactedItem) {
        throw new UnsupportedOperationException("Redacted block streams are not supported by this tool. "
                + "Event reconstruction requires full transaction bytes.");
    }

    private void endOfBlock(@NonNull final Consumer<PlatformEvent> consumer) {
        if (currentEventHeader != null) {
            completeEvent(consumer);
        }
    }

    /** Assembles the current event, emits it to the consumer, and records it for in-block lookups. */
    private void completeEvent(@NonNull final Consumer<PlatformEvent> consumer) {
        final PlatformEvent platformEvent =
                createEventFromData(currentEventHeader, new ArrayList<>(currentTransactions), eventIndexToEvent);
        eventIndexToEvent.put(eventIndexWithinBlock, platformEvent);
        consumer.accept(platformEvent);
        currentEventHeader = null;
    }

    private PlatformEvent createEventFromData(
            @NonNull final EventHeader eventHeader,
            @NonNull final List<Bytes> transactions,
            @NonNull final Map<Integer, PlatformEvent> eventIndexToEvent) {

        final EventCore eventCore = eventHeader.eventCore();
        if (eventCore == null) {
            throw new IllegalStateException("EventHeader missing EventCore data");
        }

        // Resolve parent hashes from EventHeader parent references
        final List<EventDescriptor> resolvedParents =
                resolveParentReferences(eventHeader.parents(), eventIndexToEvent, eventCore);

        final Hash eventHash = hashEvent(eventCore, resolvedParents, transactions);

        final GossipEvent gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .signature(Bytes.EMPTY)
                .parents(resolvedParents)
                .transactions(transactions)
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
                }
                default ->
                    throw new IllegalStateException("Unknown parent reference kind: "
                            + parentRef.parent().kind());
            }
        }

        return resolvedParents;
    }

    // ---- Hashing ----

    /**
     * Computes the event hash, mirroring the production {@code PbjStreamHasher}: hash
     * {@code EventCore} + parent {@code EventDescriptor}s + per-transaction double-hash
     * (SHA-384 of the transaction bytes, then that hash fed into the event digest).
     */
    @NonNull
    private static Hash hashEvent(
            @NonNull final EventCore eventCore,
            @NonNull final List<EventDescriptor> parents,
            @NonNull final List<Bytes> transactions) {
        try {
            final MessageDigest eventDigest = DigestType.SHA_384.buildDigest();
            final WritableSequentialData eventStream = new WritableStreamingData(new HashingOutputStream(eventDigest));

            EventCore.PROTOBUF.write(eventCore, eventStream);
            for (final EventDescriptor parent : parents) {
                EventDescriptor.PROTOBUF.write(parent, eventStream);
            }

            final MessageDigest transactionDigest = DigestType.SHA_384.buildDigest();
            final WritableSequentialData transactionStream =
                    new WritableStreamingData(new HashingOutputStream(transactionDigest));
            for (final Bytes transaction : transactions) {
                transactionStream.writeBytes(transaction);
                eventStream.writeBytes(transactionDigest.digest());
            }

            return new Hash(eventDigest.digest(), DigestType.SHA_384);
        } catch (final IOException e) {
            throw new RuntimeException("An exception occurred while trying to hash an event!", e);
        }
    }
}
