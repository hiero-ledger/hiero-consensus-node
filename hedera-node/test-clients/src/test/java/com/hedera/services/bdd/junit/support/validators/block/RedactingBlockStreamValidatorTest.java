// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for RedactingBlockStreamValidator.
 */
class RedactingBlockStreamValidatorTest {

    @TempDir
    Path tempDir;

    @Mock
    private HapiSpec mockSpec;

    private RedactingBlockStreamValidator validator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockSpec.getName()).thenReturn("test-spec");
        validator = new RedactingBlockStreamValidator(tempDir.resolve("redacted-blocks"));
    }

    @Test
    void testFactoryCreatesValidator() {
        // Given
        when(mockSpec.getName()).thenReturn("test-spec-name");
        
        // When
        RedactingBlockStreamValidator createdValidator = 
            (RedactingBlockStreamValidator) RedactingBlockStreamValidator.FACTORY.create(mockSpec);
        
        // Then
        assertNotNull(createdValidator);
        assertTrue(createdValidator.getOutputDirectory().toString().contains("test-spec-name"));
    }

    @Test
    void testFactoryAppliestoAllSpecs() {
        // Given/When/Then
        assertTrue(RedactingBlockStreamValidator.FACTORY.appliesTo(mockSpec));
    }

    @Test
    void testValidateBlocksWithEmptyList() {
        // Given
        List<Block> emptyBlocks = List.of();

        // When/Then - should not throw
        assertDoesNotThrow(() -> validator.validateBlocks(emptyBlocks));
    }

    @Test
    void testValidateBlocksWithSimpleBlock() throws IOException {
        // Given - create a simple PBJ block for testing
        List<Block> blocks = createSimplePbjBlocks();

        // When
        validator.validateBlocks(blocks);

        // Then - verify files were created
        Path outputDir = validator.getOutputDirectory();
        assertTrue(Files.exists(outputDir));
        
        List<Path> createdFiles = Files.list(outputDir)
            .filter(path -> path.toString().endsWith(".pb"))
            .toList();
        assertEquals(1, createdFiles.size());
        
        // Verify the file contains valid block data
        Path blockFile = createdFiles.get(0);
        assertTrue(Files.size(blockFile) > 0);
    }

    @Test
    void testRedactionReplacesTransactionContent() throws NoSuchAlgorithmException, IOException {
        // Given - create blocks with transactions that should be redacted
        List<Block> blocks = createBlocksWithTransactions();
        
        // When
        validator.validateBlocks(blocks);
        
        // Then - verify redacted blocks were written
        Path outputFile = Files.list(validator.getOutputDirectory())
            .filter(path -> path.toString().endsWith(".pb"))
            .findFirst()
            .orElseThrow();
            
        assertTrue(Files.size(outputFile) > 0);
        
        // Read back and verify it's a valid PBJ Block
        byte[] fileBytes = Files.readAllBytes(outputFile);
        Block reloadedBlock = Block.PROTOBUF.fromBytes(Bytes.wrap(fileBytes));
        
        assertNotNull(reloadedBlock);
        assertFalse(reloadedBlock.items().isEmpty());
        
        // Verify that any signed_transaction items are exactly 32 bytes (SHA-256 hash length)
        long redactedTransactionCount = reloadedBlock.items().stream()
            .filter(item -> item.item().kind() == BlockItem.ItemOneOfType.SIGNED_TRANSACTION)
            .peek(item -> {
                Bytes txBytes = (Bytes) item.item().as();
                assertEquals(32, txBytes.length(), "Redacted transaction should be exactly 32 bytes (SHA-256 hash)");
            })
            .count();
        
        // We expect at least one redacted transaction since our test blocks contain them
        assertTrue(redactedTransactionCount > 0, "Should have found at least one redacted transaction");
    }

    @Test
    void testMultipleBlocksProcessing() throws IOException {
        // Given
        List<Block> multipleBlocks = createMultiplePbjBlocks(3);

        // When
        validator.validateBlocks(multipleBlocks);

        // Then
        List<Path> createdFiles = Files.list(validator.getOutputDirectory())
            .filter(path -> path.toString().endsWith(".pb"))
            .toList();
        assertEquals(3, createdFiles.size());
        
        // Verify all files contain valid block data
        for (Path file : createdFiles) {
            assertTrue(Files.size(file) > 0);
            
            // Verify we can deserialize each block
            byte[] fileBytes = Files.readAllBytes(file);
            Block reloadedBlock = Block.PROTOBUF.fromBytes(Bytes.wrap(fileBytes));
            assertNotNull(reloadedBlock);
        }
    }

    @Test
    void testRedactionMaintainsOriginalTransactionHash() throws NoSuchAlgorithmException, IOException {
        // Given
        String originalTxData = "test transaction content";
        List<Block> blocks = createBlocksWithSpecificTransaction(originalTxData);
        
        // Calculate expected hash
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] expectedHash = sha256.digest(originalTxData.getBytes());
        
        // When
        validator.validateBlocks(blocks);
        
        // Then - verify the redacted transaction contains the correct hash
        Path outputFile = Files.list(validator.getOutputDirectory())
            .filter(path -> path.toString().endsWith(".pb"))
            .findFirst()
            .orElseThrow();
            
        byte[] fileBytes = Files.readAllBytes(outputFile);
        Block reloadedBlock = Block.PROTOBUF.fromBytes(Bytes.wrap(fileBytes));
        
        // Find the redacted transaction and verify its hash
        reloadedBlock.items().stream()
            .filter(item -> item.item().kind() == BlockItem.ItemOneOfType.SIGNED_TRANSACTION)
            .forEach(item -> {
                Bytes actualHashBytes = (Bytes) item.item().as();
                assertArrayEquals(expectedHash, actualHashBytes.toByteArray(), 
                    "Redacted transaction hash should match SHA-256 of original content");
            });
    }

    /**
     * Creates simple PBJ blocks for testing.
     */
    private List<Block> createSimplePbjBlocks() {
        return createMultiplePbjBlocks(1);
    }

    /**
     * Creates blocks with transactions for testing redaction.
     */
    private List<Block> createBlocksWithTransactions() {
        return createBlocksWithSpecificTransaction("sample transaction data");
    }

    /**
     * Creates blocks with a specific transaction content.
     */
    private List<Block> createBlocksWithSpecificTransaction(String txData) {
        List<BlockItem> items = new ArrayList<>();
        
        // Add block header
        items.add(BlockItem.newBuilder()
            .blockHeader(BlockHeader.newBuilder().build())
            .build());
        
        // Add a signed_transaction with specific data
        var txBytes = Bytes.wrap(txData.getBytes());
        items.add(BlockItem.newBuilder()
            .signedTransaction(txBytes)
            .build());
        
        // Add block proof
        items.add(BlockItem.newBuilder()
            .blockProof(BlockProof.newBuilder().build())
            .build());

        Block block = Block.newBuilder()
            .items(items)
            .build();
            
        return List.of(block);
    }

    /**
     * Creates multiple simple PBJ blocks for testing.
     */
    private List<Block> createMultiplePbjBlocks(int count) {
        List<Block> blocks = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            List<BlockItem> items = new ArrayList<>();
            
            // Add block header
            items.add(BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build());
            
            // Add a sample signed_transaction to test redaction
            var sampleTxBytes = Bytes.wrap(("sample transaction data " + i).getBytes());
            items.add(BlockItem.newBuilder()
                .signedTransaction(sampleTxBytes)
                .build());
            
            // Add block proof
            items.add(BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().build())
                .build());

            Block block = Block.newBuilder()
                .items(items)
                .build();
                
            blocks.add(block);
        }
        
        return blocks;
    }
}