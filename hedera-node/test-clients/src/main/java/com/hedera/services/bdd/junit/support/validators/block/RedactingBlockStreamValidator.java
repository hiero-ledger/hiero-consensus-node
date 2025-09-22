// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static org.assertj.core.api.Fail.fail;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.ParentEventReference;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.HashingOutputStream;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A BlockStreamValidator implementation that redacts transaction content by replacing transaction data with their
 * cryptographic hashes. This maintains the hash integrity of the block stream while removing transaction details.
 * <p>
 * The validator:
 * <ol>
 *     <li>Accepts blocks and redacts all transactions within them</li>
 *     <li>Replaces signed_transaction content with SHA-384 hashes</li>
 *     <li>Writes redacted blocks to disk using PBJ protobuf serialization</li>
 *     <li>Reads the blocks back into memory for verification</li>
 * </ol>
 * <p>
 * This approach ensures that the block structure and hash chain remain intact while transaction details are obscured
 * for privacy or security purposes.
 */
public class RedactingBlockStreamValidator implements BlockStreamValidator {
    private static final Logger logger = LogManager.getLogger(RedactingBlockStreamValidator.class);

    private final Path outputDirectory;

    private final List<PlatformEvent> allEvents = new ArrayList<>();
    private final Set<Integer> uniqueParentReferenceIndices = new HashSet<>();
    private final Set<Hash> crossBlockParentHashes = new HashSet<>();

    /**
     * Factory for creating RedactingBlockStreamValidator instances.
     */
    public static final Factory FACTORY = new Factory() {
        @Override
        public boolean appliesTo(@NonNull final HapiSpec spec) {
            // Apply to all specs by default, but could be configured based on spec properties
            return true;
        }

        @Override
        @NonNull
        public BlockStreamValidator create(@NonNull final HapiSpec spec) {
            // Create output directory based on spec working directory
            final Path outputDir = Path.of(".", "redacted-blocks", spec.getName());
            return new RedactingBlockStreamValidator(outputDir);
        }
    };

    /**
     * Creates a new RedactingBlockStreamValidator with the specified output directory.
     *
     * @param outputDirectory the directory where redacted blocks will be written
     */
    public RedactingBlockStreamValidator(@NonNull final Path outputDirectory) {
        this.outputDirectory = outputDirectory;
        try {
            // Ensure output directory exists
            Files.createDirectories(outputDirectory);
        } catch (final IOException e) {
            throw new RuntimeException("Failed to create output directory: " + outputDirectory, e);
        }
    }

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        logger.info("Processing {} blocks for transaction redaction", blocks.size());

        try {

            // Step 1: Redact transactions in all blocks
            final List<Block> redactedBlocks = redactBlocks(blocks);

            // Step 2: Write redacted blocks to disk
            final List<Path> writtenFiles = writeBlocksToDisk(redactedBlocks);

            // Step 3: Read blocks back from disk to verify serialization/deserialization
            final List<Block> reloadedBlocks = readBlocksFromDisk(writtenFiles);

            // Step 4: Verify that the redacted blocks maintain structural integrity
            verifyRedactedBlocks(reloadedBlocks, blocks.size(), true);

            logger.info("Successfully processed and verified {} redacted blocks", reloadedBlocks.size());

        } catch (final IOException e) {
            logger.error("Failed to process blocks for redaction", e);
            throw new RuntimeException("Block redaction failed", e);
        }
    }

    /**
     * Redacts all transactions in the given blocks by replacing transaction content with hashes.
     *
     * @param originalBlocks the original blocks containing transactions to redact
     * @return a new list of blocks with redacted transactions
     */
    private List<Block> redactBlocks(@NonNull final List<Block> originalBlocks) {
        logger.debug("Redacting transactions in {} blocks", originalBlocks.size());

        final List<Block> redactedBlocks = new ArrayList<>();

        int blockIndex = 0;
        for (final Block originalBlock : originalBlocks) {
            final Block redactedBlock = redactBlock(originalBlock, blockIndex);
            redactedBlocks.add(redactedBlock);
            blockIndex++;
        }

        return redactedBlocks;
    }

    /**
     * Redacts transactions in a single block.
     *
     * @param originalBlock the original block to redact
     * @param blockIndex the index of the block for logging purposes
     * @return a new block with redacted transactions
     */
    private Block redactBlock(@NonNull final Block originalBlock, final int blockIndex) {
        final List<BlockItem> originalItems = originalBlock.items();
        final List<BlockItem> redactedItems = new ArrayList<>();

        int transactionCount = 0;

        for (final BlockItem item : originalItems) {
            if (hasSignedTransaction(item)) {
                // Redact the transaction
                final BlockItem redactedItem = redactTransactionItem(item);
                redactedItems.add(redactedItem);
                transactionCount++;
            } else {
                // Non-transaction items pass through unchanged
                redactedItems.add(item);
            }
        }

        logger.debug(
                "Block {}: redacted {} transactions out of {} total items",
                blockIndex,
                transactionCount,
                originalItems.size());

        return Block.newBuilder().items(redactedItems).build();
    }

    /**
     * Checks if a BlockItem contains a signed_transaction field.
     *
     * @param item the block item to check
     * @return true if the item contains a signed_transaction
     */
    private boolean hasSignedTransaction(@NonNull final BlockItem item) {
        // Check if this is a signed_transaction item (field 4 in the protobuf)
        final var itemKind = item.item().kind();
        item.item().as();
        return itemKind == BlockItem.ItemOneOfType.SIGNED_TRANSACTION;
    }

    /**
     * Gets the signed_transaction bytes from a BlockItem.
     *
     * @param item the block item containing a signed_transaction
     * @return the transaction bytes
     */
    private Bytes getSignedTransaction(@NonNull final BlockItem item) {
        if (!hasSignedTransaction(item)) {
            throw new IllegalArgumentException("BlockItem does not contain a signed_transaction");
        }
        return item.item().as();
    }

    /**
     * Redacts a single transaction item by replacing its content with a hash.
     *
     * @param originalItem the original block item containing a signed_transaction
     * @return a new block item with the transaction content replaced by its hash
     */
    private BlockItem redactTransactionItem(@NonNull final BlockItem originalItem) {
        final Bytes originalTransactionBytes = getSignedTransaction(originalItem);

        // Compute SHA-384 hash of the original transaction content
        final Bytes transactionHash = computeHash(originalTransactionBytes);

        // Create a new BlockItem with the hash as the signed_transaction content
        return BlockItem.newBuilder().signedTransaction(transactionHash).build();
    }

    /**
     * Computes SHA-384 hash of the given bytes.
     *
     * @param content the content to hash
     * @return the SHA-384 hash as bytes
     */
    private Bytes computeHash(@NonNull final Bytes content) {
        final MessageDigest transactionDigest = DigestType.SHA_384.buildDigest();
        transactionDigest.update(content.toByteArray());
        final byte[] hashBytes = transactionDigest.digest();
        return Bytes.wrap(hashBytes);
    }

    /**
     * Writes redacted blocks to disk using PBJ protobuf serialization.
     *
     * @param redactedBlocks the blocks to write
     * @return list of paths where blocks were written
     * @throws IOException if writing fails
     */
    private List<Path> writeBlocksToDisk(@NonNull final List<Block> redactedBlocks) throws IOException {
        logger.debug("Writing {} redacted blocks to disk", redactedBlocks.size());

        final List<Path> writtenFiles = new ArrayList<>();

        int i = 0;
        for (final Block block : redactedBlocks) {
            final Path blockFile = outputDirectory.resolve(String.format("redacted-block-%d.pb", i));

            // Serialize block using PBJ protobuf codec
            final Bytes serializedBlock = Block.PROTOBUF.toBytes(block);

            // Write to file
            Files.write(
                    blockFile,
                    serializedBlock.toByteArray(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            writtenFiles.add(blockFile);
            logger.trace("Wrote redacted block {} to {}", i, blockFile);
            i++;
        }

        return writtenFiles;
    }

    /**
     * Reads blocks back from disk to verify serialization worked correctly.
     *
     * @param blockFiles the files to read blocks from
     * @return list of blocks read from disk
     * @throws IOException if reading fails
     */
    private List<Block> readBlocksFromDisk(@NonNull final List<Path> blockFiles) throws IOException {
        logger.debug("Reading {} blocks back from disk for verification", blockFiles.size());

        final List<Block> reloadedBlocks = new ArrayList<>();

        for (final Path blockFile : blockFiles) {
            try {
                // Read file contents
                final byte[] fileBytes = Files.readAllBytes(blockFile);

                // Deserialize using PBJ protobuf codec
                final Block reloadedBlock = Block.PROTOBUF.parseStrict(Bytes.wrap(fileBytes));

                reloadedBlocks.add(reloadedBlock);
                logger.trace("Successfully reloaded block from {}", blockFile);

            } catch (final IOException e) {
                logger.error("Failed to reload block from {}", blockFile, e);
                throw new RuntimeException("Failed to reload block from " + blockFile, e);
            } catch (final ParseException e) {
                logger.error("Failed to parse block from {}", blockFile, e);
                throw new RuntimeException("Failed to parse block from " + blockFile, e);
            }
        }

        return reloadedBlocks;
    }

    private void verifyBlocks(@NonNull final List<Block> blocks) throws IOException {
        final List<PlatformEvent> allEvents = reconstructEventsFromBlocks(blocks, false);
        validateEventHashChain(allEvents);
    }

    /**
     * Verifies that the redacted blocks maintain proper event hash integrity. Reconstructs GossipEvent objects from
     * block items and validates the event hash chain using the same approach as EventMigrationTest.
     *
     * @param reloadedBlocks the blocks that were written to and read from disk
     * @param expectedBlockCount the expected number of blocks
     */
    private void verifyRedactedBlocks(@NonNull final List<Block> reloadedBlocks, final int expectedBlockCount,
            final boolean isRedacted)
            throws IOException {
        logger.debug("Verifying event hash integrity in {} reloaded redacted blocks", reloadedBlocks.size());

        if (reloadedBlocks.size() != expectedBlockCount) {
            throw new IllegalStateException(String.format(
                    "Expected %d blocks but found %d after redaction and reload",
                    expectedBlockCount, reloadedBlocks.size()));
        }

        // Reconstruct events from all blocks and validate hash chain
        final List<PlatformEvent> allEvents = reconstructEventsFromBlocks(reloadedBlocks, isRedacted);
        validateEventHashChain(allEvents);

        logger.info(
                "Successfully verified event hash integrity for {} events across {} blocks",
                allEvents.size(),
                reloadedBlocks.size());
    }

    /**
     * Reconstructs GossipEvent objects from BlockItems across all blocks. Events are processed in order to handle
     * parent index references correctly.
     *
     * <p><strong>Important:</strong> The events in the returned list are in consensus order,
     * which also means they are in topological order. This ordering is critical for proper event hash chain validation,
     * as each event's hash must be calculated and stored before it can be referenced by subsequent events as a parent.
     *
     * @param blocks the blocks containing event data
     * @return list of reconstructed GossipEvent objects in consensus/topological order
     */
    private List<PlatformEvent> reconstructEventsFromBlocks(@NonNull final List<Block> blocks)
            throws IOException {
        final Map<Integer, PlatformEvent> eventIndexToEvent = new HashMap<>(); // Track event hashes by index

        EventHeader currentEventHeader = null;
        for (final Block block : blocks) {
            logger.info("NEW BLOCK");
            int eventIndexWithinBlock = 0;
            final List<Bytes> currentTransactions = new ArrayList<>();

            eventIndexToEvent.clear();

            for (final BlockItem item : block.items()) {
                final var itemKind = item.item().kind();

                switch (itemKind) {
                    case EVENT_HEADER:
                        // If we have a previous event, complete it
                        if (currentEventHeader != null) {
                            final PlatformEvent platformEvent = createEventFromData(
                                    currentEventHeader, currentTransactions, eventIndexToEvent);
                            allEvents.add(platformEvent);

                            // Calculate and store the event hash for future parent references
                            eventIndexToEvent.put(eventIndexWithinBlock, platformEvent);
                            eventIndexWithinBlock++;
                            logger.info("EVENT COMPLETED");
                        }
                        logger.info("EVENT HEADER");

                        // Start new event
                        currentEventHeader = item.item().as();
                        currentTransactions.clear();
                        break;

                    case SIGNED_TRANSACTION:
                        // Add transaction to current event
                        logger.info("TRANSACTION");
                        if (currentEventHeader != null) {
                            final Bytes transactionBytes = item.item().as();
                            currentTransactions.add(transactionBytes);
                        } else {
                            fail("Unexpected transaction item without an active event header!");
                        }
                        break;

                    default:
                        // Skip other item types (block headers, proofs, etc.)
                        break;
                }
            }

            // Complete the last event in the block if present
            if (currentEventHeader != null) {
                final PlatformEvent platformEvent = createEventFromData(currentEventHeader, currentTransactions,
                        eventIndexToEvent, isRedacted);
                eventIndexToEvent.put(eventIndexWithinBlock, platformEvent);
                allEvents.add(platformEvent);
                currentEventHeader = null;
                logger.info("EVENT COMPLETED");
            }
        }

        return allEvents;
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
            @NonNull final Map<Integer, PlatformEvent> eventIndexToEvent) throws IOException {

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
        logger.warn("Reconstructed GossipEvent: \n{}", GossipEvent.JSON.toJSON(gossipEvent));
        final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
        if (isRedacted) {
            platformEvent.setHash(calculateEventHashWithRedactedTransactions(platformEvent));
        } else {
            calculateEventHash(platformEvent);
        }
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
                    uniqueParentReferenceIndices.add(parentIndex);
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
     * Calculates the hash of an event using PbjStreamHasher.
     *
     * @param platformEvent the event to hash
     * @return the calculated hash
     */
    private Hash calculateEventHashWithRedactedTransactions(@NonNull final PlatformEvent platformEvent)
            throws IOException {
        // Create new hasher variables for each calculation to avoid state issues
        final MessageDigest eventDigest = DigestType.SHA_384.buildDigest();
        final WritableSequentialData eventStream = new WritableStreamingData(new HashingOutputStream(eventDigest));

        final GossipEvent gossipEvent = platformEvent.getGossipEvent();
        final List<EventDescriptor> parents = platformEvent.getAllParents().stream()
                .map(EventDescriptorWrapper::eventDescriptor).toList();

        // Convert transactions from hash Bytes to TransactionWrapper
        final List<Bytes> transactionHashes = gossipEvent.transactions();

        assert gossipEvent.eventCore() != null;
        EventCore.PROTOBUF.write(gossipEvent.eventCore(), eventStream);
        for (final EventDescriptor parent : parents) {
            EventDescriptor.PROTOBUF.write(parent, eventStream);
        }
        for (final Bytes hash : transactionHashes) {
            eventStream.writeBytes(hash);
        }

        return new Hash(eventDigest.digest(), DigestType.SHA_384);
    }

    /**
     * Validates the event hash chain using the same approach as EventMigrationTest.
     *
     * <p><strong>Critical Requirement:</strong> The events list must be in consensus order
     * (which is also topological order) for validation to work correctly. This ensures that when we collect parent
     * hashes from events, all parent events have already been processed and their hashes calculated, maintaining the
     * integrity of the hash chain validation.
     *
     * @param events the list of reconstructed events in consensus/topological order
     */
    private void validateEventHashChain(@NonNull final List<PlatformEvent> events) {
        if (events.isEmpty()) {
            logger.info("No events to validate");
            return;
        }

        // Calculate and collect event hashes
        final Set<Hash> uniqueEventHashes = events.stream().map(PlatformEvent::getHash).collect(Collectors.toSet());
        final Set<Hash> uniqueParentHashes = new HashSet<>();
        events.stream()
                .map(PlatformEvent::getAllParents)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .map(EventDescriptorWrapper::hash)
                .forEach(uniqueParentHashes::add);

        int numEventsWithUnfoundParents = 0;
        int numEventsWithFoundParents = 0;
        for (final Hash crossBlockParentHash : crossBlockParentHashes) {
            if (uniqueEventHashes.contains(crossBlockParentHash)) {
                numEventsWithFoundParents++;
            } else {
                numEventsWithUnfoundParents++;
            }
        }
        if (numEventsWithFoundParents == 0) {
            fail("No cross block event hashes matched! Found %d cross block hashes, did not find %d",
                    numEventsWithFoundParents, numEventsWithUnfoundParents);
        }

//        for (final Hash parentHash : uniqueParentHashes) {
//            if (!uniqueEventHashes.remove(parentHash)) {
//                logger.warn("Event with hash matching parent hash not found!");
//            }
//        }
//
//        // Validate event hashes by removing the parent hashes from the
//        // list of event hashes we calculated post redaction
//        final int originalUniqueEventCount = uniqueEventHashes.size();
//        uniqueEventHashes.removeAll(uniqueParentHashes);
//        final int unmatchedHashes = uniqueEventHashes.size();
//        final int matchedHashes = originalUniqueEventCount - unmatchedHashes;
//        if (matchedHashes < uniqueParentReferenceIndices.size()) {
//            fail("Failed to properly match %d parent hashes!", uniqueParentReferenceIndices.size() - matchedHashes);
//        }
//
//        // In a real implementation, you would assert expected number of unmatched hashes
//        // based on the number of genesis events expected
//        if (unmatchedHashes > 0) {
//            fail(
//                    "Unmatched parent hashes: {} out of {} events. "
//                            + "This indicates issues with event hash calculation in the presence of redacted transactions.",
//                    unmatchedHashes,
//                    originalUniqueEventCount);
//        }
    }

    /**
     * Gets the output directory where redacted blocks are stored.
     *
     * @return the output directory path
     */
    public Path getOutputDirectory() {
        return outputDirectory;
    }
}
