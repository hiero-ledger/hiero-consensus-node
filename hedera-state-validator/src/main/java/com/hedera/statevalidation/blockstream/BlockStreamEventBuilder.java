// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.MAX_PBJ_RECORD_SIZE;
import static com.hedera.pbj.runtime.Codec.DEFAULT_MAX_DEPTH;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.crypto.Cryptography.DEFAULT_DIGEST_TYPE;

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
import java.util.function.Consumer;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.HashingOutputStream;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Reconstructs events from the block stream in a streaming fashion.
 *
 * <p>Events are reconstructed one block at a time via {@link #processBlock(Block, DigestType, Consumer)}.
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
 *
 * <p>The block stream does not record which digest type the events were hashed with, and it may
 * change in the middle of the stream. The digest type is inferred from the length of the parent
 * hashes carried by cross-block ({@code EVENT_DESCRIPTOR}) parent references: once an event reveals
 * a digest type, it is used for that event and all subsequent events until another event reveals a
 * different one. Events with no cross-block parents keep the current digest type. The digest type in
 * effect at the start of a block is supplied by the caller (see {@link #digestTypeSignal(Block)}).
 */
public class BlockStreamEventBuilder {

    /**
     * The digest types revealed by the cross-block parent references of a block's events.
     *
     * @param first the digest type revealed by the first event with cross-block parents
     * @param last the digest type revealed by the last event with cross-block parents
     */
    public record DigestTypeSignal(@NonNull DigestType first, @NonNull DigestType last) {}

    /** Track events by index within the current block, for in-block parent lookups. */
    private final Map<Integer, PlatformEvent> eventIndexToEvent = new HashMap<>();

    /** Transactions collected for the event currently being assembled. */
    private final List<Bytes> currentTransactions = new ArrayList<>();

    /** The header of the event we are currently collecting transactions for. */
    private EventHeader currentEventHeader = null;

    /** The index of the current event within the current block. */
    private int eventIndexWithinBlock = 0;

    /** The digest type used to hash the current event. */
    private DigestType digestType = DEFAULT_DIGEST_TYPE;

    /**
     * Processes a single block, reconstructing events from its items and delivering each completed
     * event to the given consumer. Only the current block's events are held in memory; once this
     * method returns, the block and its events can be garbage-collected.
     *
     * @param block the block to process
     * @param initialDigestType the digest type in effect at the start of the block, i.e. the last
     *     digest type revealed by earlier blocks in the stream
     * @param eventConsumer receives each reconstructed {@link PlatformEvent} as it is completed
     */
    public void processBlock(
            @NonNull final Block block,
            @NonNull final DigestType initialDigestType,
            @NonNull final Consumer<PlatformEvent> eventConsumer) {
        requireNonNull(block);
        requireNonNull(initialDigestType);
        requireNonNull(eventConsumer);

        startOfBlock(initialDigestType);

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
     *
     * <p>Uses the same max message size ({@code MAX_PBJ_RECORD_SIZE}, 32 MiB) and max depth as
     * {@link com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess#blockFrom}. The PBJ convenience
     * overloads default to a 2 MiB limit, which is smaller than the block reader's limit and smaller
     * than valid node-generated transactions (e.g. history-proof votes up to
     * {@code nodeTransaction.maxBytes} = 32 MiB). Without matching the limit, a block that reads
     * successfully can fail here during event reconstruction.
     */
    public static TransactionBody getTransactionBody(@NonNull final Bytes transactionBytes) {
        try {
            final SignedTransaction signedTransaction = SignedTransaction.PROTOBUF.parse(
                    transactionBytes.toReadableSequentialData(), false, false, DEFAULT_MAX_DEPTH, MAX_PBJ_RECORD_SIZE);
            return TransactionBody.PROTOBUF.parse(
                    signedTransaction.bodyBytes().toReadableSequentialData(),
                    false,
                    false,
                    DEFAULT_MAX_DEPTH,
                    MAX_PBJ_RECORD_SIZE);
        } catch (final ParseException e) {
            throw new RuntimeException("Unable to parse transaction bytes", e);
        }
    }

    /**
     * Scans a block for the digest types revealed by its events' cross-block parent references.
     * Allows a caller that processes blocks out of order to determine, in stream order, the digest
     * type in effect at the start of each block.
     *
     * @param block the block to scan
     * @return the first and last revealed digest types, or {@code null} if no event in the block has
     *     a cross-block parent
     */
    @Nullable
    public static DigestTypeSignal digestTypeSignal(@NonNull final Block block) {
        DigestType first = null;
        DigestType last = null;
        for (final BlockItem item : block.items()) {
            if (item.hasEventHeader()) {
                final DigestType revealed = revealedDigestType(item.eventHeaderOrThrow().parents());
                if (revealed != null) {
                    first = first == null ? revealed : first;
                    last = revealed;
                }
            }
        }
        return first == null ? null : new DigestTypeSignal(first, last);
    }

    /**
     * Returns the digest type revealed by the cross-block parent references of a single event, i.e.
     * the digest type whose output length matches the parent hashes.
     *
     * @param parentReferences the parent references of the event
     * @return the revealed digest type, or {@code null} if the event has no cross-block parents
     * @throws IllegalStateException if a parent hash length matches no known digest type, or the
     *     parent hashes have different lengths
     */
    @Nullable
    private static DigestType revealedDigestType(@NonNull final List<ParentEventReference> parentReferences) {
        DigestType revealed = null;
        for (final ParentEventReference parentRef : parentReferences) {
            if (parentRef.parent().kind() != ParentEventReference.ParentOneOfType.EVENT_DESCRIPTOR) {
                continue;
            }
            final EventDescriptor parentDescriptor = parentRef.parent().as();
            final DigestType parentDigestType = digestTypeOf(parentDescriptor.hash());
            if (revealed != null && revealed != parentDigestType) {
                throw new IllegalStateException("Event references parents hashed with different digest types: "
                        + revealed + " and " + parentDigestType);
            }
            revealed = parentDigestType;
        }
        return revealed;
    }

    @NonNull
    private static DigestType digestTypeOf(@NonNull final Bytes hash) {
        final long length = hash.length();
        for (final DigestType type : DigestType.values()) {
            if (type.digestLength() == length) {
                return type;
            }
        }
        throw new IllegalStateException(
                "Parent event hash length " + length + " bytes does not match any known digest type");
    }

    // ---- Internal block-processing methods ----

    private void startOfBlock(@NonNull final DigestType initialDigestType) {
        digestType = initialDigestType;
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

        // Switch to the digest type revealed by this event's cross-block parents, if any
        final DigestType revealedDigestType = revealedDigestType(eventHeader.parents());
        if (revealedDigestType != null) {
            digestType = revealedDigestType;
        }
        final Hash eventHash = hashEvent(eventCore, resolvedParents, transactions, digestType);

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
                        resolvedParents.add(parent.getDescriptor().toPbj());
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
     * (hash of the transaction bytes, then that hash fed into the event digest), using the given
     * digest type for both.
     */
    @NonNull
    private static Hash hashEvent(
            @NonNull final EventCore eventCore,
            @NonNull final List<EventDescriptor> parents,
            @NonNull final List<Bytes> transactions,
            @NonNull final DigestType digestType) {
        try {
            final MessageDigest eventDigest = digestType.buildDigest();
            final WritableSequentialData eventStream = new WritableStreamingData(new HashingOutputStream(eventDigest));

            EventCore.PROTOBUF.write(eventCore, eventStream);
            for (final EventDescriptor parent : parents) {
                EventDescriptor.PROTOBUF.write(parent, eventStream);
            }

            final MessageDigest transactionDigest = digestType.buildDigest();
            final WritableSequentialData transactionStream =
                    new WritableStreamingData(new HashingOutputStream(transactionDigest));
            for (final Bytes transaction : transactions) {
                transactionStream.writeBytes(transaction);
                eventStream.writeBytes(transactionDigest.digest());
            }

            return new Hash(eventDigest.digest(), digestType);
        } catch (final IOException e) {
            throw new RuntimeException("An exception occurred while trying to hash an event!", e);
        }
    }
}
