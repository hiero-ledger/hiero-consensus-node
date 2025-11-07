// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.services.bdd.junit.support.validators.block.BlockStreamEventBuilder.isTransactionInEvent;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.RedactedItem;
import com.hedera.hapi.node.transaction.SignedTransaction;
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
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.HashingOutputStream;

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
public class RedactingEventHashBlockStreamValidator implements BlockStreamValidator {
    private static final Logger logger = LogManager.getLogger();

    private final Path outputDirectory;

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
            return new RedactingEventHashBlockStreamValidator(outputDir);
        }
    };

    /**
     * Creates a new RedactingBlockStreamValidator with the specified output directory.
     *
     * @param outputDirectory the directory where redacted blocks will be written
     */
    public RedactingEventHashBlockStreamValidator(@NonNull final Path outputDirectory) {
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
            verifyRedactedBlocks(reloadedBlocks, blocks.size());

            logger.info("Successfully processed and verified {} redacted blocks", redactedBlocks.size());

        } catch (final IOException | ParseException e) {
            logger.error("Failed to process blocks for redaction", e);
            throw new RuntimeException("Block redaction failed", e);
        }
    }

    /**
     * Redacts all user transactions in the given blocks by replacing transaction content with hashes.
     *
     * @param originalBlocks the original blocks containing transactions to redact
     * @return a new list of blocks with redacted transactions
     */
    private List<Block> redactBlocks(@NonNull final List<Block> originalBlocks) throws ParseException {
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
     * Redacts user transactions in a single block.
     *
     * @param originalBlock the original block to redact
     * @param blockIndex the index of the block for logging purposes
     * @return a new block with redacted transactions
     */
    private Block redactBlock(@NonNull final Block originalBlock, final int blockIndex) throws ParseException {
        final List<BlockItem> originalItems = originalBlock.items();
        final List<BlockItem> redactedItems = new ArrayList<>();

        int transactionCount = 0;

        for (final BlockItem item : originalItems) {
            final SignedTransaction maybeEventTransaction = getEventTransactionOrNull(item);
            // Redact the transaction if it is a user transaction
            if (maybeEventTransaction != null) {
                final BlockItem redactedItem = redactTransactionItem(maybeEventTransaction);
                redactedItems.add(redactedItem);
                transactionCount++;
            } else {
                // Non-transaction items always pass through unchanged
                redactedItems.add(item);
            }
        }

        logger.debug(
                "Block {}: redacted {} event transactions out of {} total items",
                blockIndex,
                transactionCount,
                originalItems.size());

        return Block.newBuilder().items(redactedItems).build();
    }

    /**
     * Checks if a BlockItem contains a transaction that is part of the event hash.
     *
     * @param item the block item to check
     * @return the SignedTransaction if the item contains a transaction that is part of the event hash
     */
    private SignedTransaction getEventTransactionOrNull(@NonNull final BlockItem item) throws ParseException {
        if (item.hasSignedTransaction() && isTransactionInEvent(item.item().as())) {
            return SignedTransaction.PROTOBUF.parse((Bytes) item.item().as());
        }
        return null;
    }

    /**
     * Redacts a single transaction item by replacing its content with a hash in a filtered block item.
     *
     * @param signedTransaction the signed_transaction
     * @return a new filtered block item with the transaction content replaced by its hash, or the original block item
     */
    private BlockItem redactTransactionItem(@NonNull final SignedTransaction signedTransaction) {
        // Compute SHA-384 hash of the original transaction content
        final Bytes transactionHash = computeTransactionHash(SignedTransaction.PROTOBUF.toBytes(signedTransaction));
        return BlockItem.newBuilder()
                .redactedItem(RedactedItem.newBuilder()
                        .signedTransactionHash(transactionHash)
                        .build())
                .build();
    }

    /**
     * Computes SHA-384 hash of the given bytes.
     *
     * @param content the content to hash
     * @return the SHA-384 hash as bytes
     */
    private static Bytes computeTransactionHash(@NonNull final Bytes content) {
        final MessageDigest transactionDigest = DigestType.SHA_384.buildDigest();
        final WritableSequentialData transactionStream =
                new WritableStreamingData(new HashingOutputStream(transactionDigest));
        transactionStream.writeBytes(content);
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
        final List<Block> reloadedBlocks = new ArrayList<>();
        for (final Path blockFile : blockFiles) {
            try {
                // Read file contents
                final byte[] fileBytes = Files.readAllBytes(blockFile);

                // Deserialize using PBJ protobuf codec
                final Block reloadedBlock = Block.PROTOBUF.parseStrict(Bytes.wrap(fileBytes));
                reloadedBlocks.add(reloadedBlock);

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

    /**
     * Verifies that the redacted blocks maintain proper event hash integrity. Reconstructs GossipEvent objects from
     * block items and validates the event hash chain using the same approach as EventMigrationTest.
     *
     * @param reloadedBlocks the blocks that were written to and read from disk
     * @param expectedBlockCount the expected number of blocks
     */
    private void verifyRedactedBlocks(@NonNull final List<Block> reloadedBlocks, final int expectedBlockCount)
            throws IOException {
        logger.debug("Verifying event hash integrity in {} reloaded redacted blocks", reloadedBlocks.size());

        if (reloadedBlocks.size() != expectedBlockCount) {
            throw new IllegalStateException(String.format(
                    "Expected %d blocks but found %d after redaction and reload",
                    expectedBlockCount, reloadedBlocks.size()));
        }

        // Reconstruct events from all blocks and validate hash chain
        final BlockStreamEventBuilder eventBuilder = new BlockStreamEventBuilder(reloadedBlocks);
        EventHashBlockStreamValidator.validateEventHashChain(
                eventBuilder.getEvents(), eventBuilder.getCrossBlockParentHashes());
    }
}
