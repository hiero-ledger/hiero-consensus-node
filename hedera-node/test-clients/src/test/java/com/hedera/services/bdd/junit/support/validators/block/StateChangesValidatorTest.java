// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO_384;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.services.bdd.junit.support.validators.block.StateChangesValidator.applyPartial;
import static com.hedera.services.bdd.junit.support.validators.block.StateChangesValidator.carriesCompressedWrapsProof;
import static com.hedera.services.bdd.junit.support.validators.block.StateChangesValidator.currentBlockHashFromNextBlockFooter;
import static com.hedera.services.bdd.junit.support.validators.block.StateChangesValidator.validateStartOfBlockStateHash;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.virtualmap.VirtualMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StateChangesValidatorTest {

    @Test
    void partialMapReplayPreservesOmittedFieldsAndAppliesExplicitClears() {
        final var prior = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(123))
                .tinybarBalance(10)
                .memo("memo")
                .deleted(true)
                .build();
        final var partial = Account.newBuilder().tinybarBalance(20).build();

        assertEquals(
                prior.copyBuilder().tinybarBalance(20).memo("").deleted(false).build(),
                applyPartial(Account.PROTOBUF, prior, partial, List.of(6, 7)));
    }

    @Test
    void successivePartialsUseThePreviouslyReconstructedValue() {
        final var prior = Account.newBuilder().tinybarBalance(10).memo("memo").build();
        final var first = applyPartial(
                Account.PROTOBUF, prior, Account.newBuilder().tinybarBalance(20).build(), List.of());
        final var second = applyPartial(
                Account.PROTOBUF, first, Account.newBuilder().memo("updated").build(), List.of());

        assertEquals(prior.copyBuilder().tinybarBalance(20).memo("updated").build(), second);
        assertEquals(second, applyPartial(Account.PROTOBUF, second, Account.DEFAULT, List.of()));
    }

    @Test
    void singletonReplayReplacesWholeNestedMessagesAndRepeatedFields() {
        final var prior = ThrottleUsageSnapshots.newBuilder()
                .tpsThrottles(List.of(new ThrottleUsageSnapshot(1, null), new ThrottleUsageSnapshot(2, null)))
                .gasThrottle(new ThrottleUsageSnapshot(10, new Timestamp(1, 2)))
                .build();
        final var partial = ThrottleUsageSnapshots.newBuilder()
                .tpsThrottles(List.of(new ThrottleUsageSnapshot(3, null)))
                .gasThrottle(new ThrottleUsageSnapshot(20, null))
                .build();

        assertEquals(partial, applyPartial(ThrottleUsageSnapshots.PROTOBUF, prior, partial, List.of()));
        assertEquals(
                partial.copyBuilder().tpsThrottles(List.of()).build(),
                applyPartial(ThrottleUsageSnapshots.PROTOBUF, partial, ThrottleUsageSnapshots.DEFAULT, List.of(1)));
    }

    @Test
    void explicitOneofClearsSupportSwitchingToASelectedDefaultValue() {
        final var node = Account.newBuilder().stakedNodeId(7).memo("memo").build();
        final var account = Account.newBuilder()
                .stakedAccountId(AccountID.newBuilder().accountNum(123))
                .build();
        final var switched = applyPartial(Account.PROTOBUF, node, account, List.of(11));
        assertEquals(
                node.copyBuilder().stakedAccountId(account.stakedAccountId()).build(), switched);

        final var zeroNode = Account.newBuilder().stakedNodeId(0).build();
        assertEquals(
                node.copyBuilder().stakedNodeId(0).build(),
                applyPartial(Account.PROTOBUF, switched, zeroNode, List.of(10)));
    }

    @Test
    void malformedPartialMetadataIsRejected() {
        final var prior = Account.newBuilder().tinybarBalance(10).memo("memo").build();
        final var partial = Account.newBuilder().tinybarBalance(20).build();
        // Clears must be sorted and unique, and must not overlap a supplied field
        assertThrows(
                IllegalArgumentException.class, () -> applyPartial(Account.PROTOBUF, prior, partial, List.of(7, 6)));
        assertThrows(
                IllegalArgumentException.class, () -> applyPartial(Account.PROTOBUF, prior, partial, List.of(6, 6)));
        assertThrows(IllegalArgumentException.class, () -> applyPartial(Account.PROTOBUF, prior, partial, List.of(5)));
        assertThrows(IllegalArgumentException.class, () -> applyPartial(Account.PROTOBUF, null, partial, List.of()));
    }

    @Test
    void primitiveStateWrappersCanBeClearedToTheirDefaults() {
        assertEquals(
                ProtoBytes.DEFAULT,
                applyPartial(ProtoBytes.PROTOBUF, new ProtoBytes(Bytes.wrap("value")), ProtoBytes.DEFAULT, List.of(1)));
        assertEquals(
                ProtoString.DEFAULT,
                applyPartial(ProtoString.PROTOBUF, new ProtoString("value"), ProtoString.DEFAULT, List.of(1)));
        assertEquals(
                EntityNumber.DEFAULT,
                applyPartial(EntityNumber.PROTOBUF, new EntityNumber(123), EntityNumber.DEFAULT, List.of(1)));
    }

    @Test
    void partialReplayRequiresAnExistingValue() {
        final var exception = assertThrows(
                IllegalArgumentException.class, () -> applyPartial(Account.PROTOBUF, null, Account.DEFAULT, List.of()));
        assertEquals("Partial update requires a prior value", exception.getMessage());
    }

    private static final Bytes PREV_BLOCK_ROOT_HASH = Bytes.wrap(new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
        33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48
    });

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validatesStateHashWithOrWithoutBlockProof(final boolean withProof) {
        final var block = blockWithStateHash(100, PREV_BLOCK_ROOT_HASH, withProof);

        assertEquals(
                PREV_BLOCK_ROOT_HASH,
                validateStartOfBlockStateHash(block, PREV_BLOCK_ROOT_HASH).startOfBlockStateRootHash());
        final var exception = assertThrows(
                AssertionError.class, () -> validateStartOfBlockStateHash(block, Bytes.wrap(new byte[48])));
        assertTrue(exception.getMessage().contains("Wrong start of block state hash for block #100"));
    }

    @Test
    void rejectsBlockWithoutFooterInsteadOfSkippingStateHashCheck() {
        final var block = blockBuilder().items(List.of(headerItem(100))).build();

        final var exception =
                assertThrows(AssertionError.class, () -> validateStartOfBlockStateHash(block, PREV_BLOCK_ROOT_HASH));
        assertTrue(exception.getMessage().contains("Missing block footer for block #100"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validatesEveryBoundaryRegardlessOfProofSamplingOrAvailability(final boolean withProof)
            throws ReflectiveOperationException {
        final var genesisHash = PREV_BLOCK_ROOT_HASH;
        final var firstEndHash = Bytes.wrap(new byte[48]);
        final var fixture = replayFixture(List.of(firstEndHash, genesisHash, firstEndHash));
        doReturn(false).when(fixture.validator()).shouldVerifyProof(anyInt(), anyInt());
        final var blocks = List.of(
                blockWithStateHash(100, genesisHash, withProof),
                blockWithStateHash(101, firstEndHash, withProof),
                blockWithStateHash(102, genesisHash, withProof));

        assertDoesNotThrow(() -> fixture.validator().validateBlocks(blocks));
        // One snapshot at each subsequent boundary, plus the final saved-state check.
        verify(fixture.lifecycle(), times(3)).copyMutableState();
    }

    @Test
    void detectsWrongStateHashEvenWhenProofIsNotSampled() throws ReflectiveOperationException {
        final var firstEndHash = Bytes.wrap(new byte[48]);
        final var fixture = replayFixture(List.of(firstEndHash, PREV_BLOCK_ROOT_HASH, firstEndHash));
        doReturn(false).when(fixture.validator()).shouldVerifyProof(anyInt(), anyInt());
        final var blocks = List.of(
                blockWithStateHash(100, PREV_BLOCK_ROOT_HASH, true),
                blockWithStateHash(101, firstEndHash, true),
                blockWithStateHash(102, firstEndHash, true));

        final var exception =
                assertThrows(AssertionError.class, () -> fixture.validator().validateBlocks(blocks));

        assertTrue(exception.getMessage().contains("Wrong start of block state hash for block #102"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detectsWrongBoundaryHashAfterProoflessBlocks(final boolean lastBlockHasProof)
            throws ReflectiveOperationException {
        final var firstEndHash = Bytes.wrap(new byte[48]);
        final var fixture = replayFixture(List.of(firstEndHash, PREV_BLOCK_ROOT_HASH, firstEndHash));
        final var blocks = List.of(
                blockWithStateHash(100, PREV_BLOCK_ROOT_HASH, false),
                blockWithStateHash(101, firstEndHash, false),
                blockWithStateHash(102, firstEndHash, lastBlockHasProof));

        final var exception =
                assertThrows(AssertionError.class, () -> fixture.validator().validateBlocks(blocks));

        assertTrue(exception.getMessage().contains("Wrong start of block state hash for block #102"));
        verify(fixture.lifecycle(), times(2)).copyMutableState();
    }

    private record ReplayFixture(
            StateChangesValidator validator, StateLifecycleManager<VirtualMapState, VirtualMap> lifecycle) {}

    @SuppressWarnings("unchecked")
    private static ReplayFixture replayFixture(final List<Bytes> endHashes) throws ReflectiveOperationException {
        // Exercise the real replay loop without bootstrapping a node or a native MerkleDB.
        final var validator = mock(StateChangesValidator.class, CALLS_REAL_METHODS);
        final StateLifecycleManager<VirtualMapState, VirtualMap> lifecycle = mock(StateLifecycleManager.class);
        final var writableStates = mock(WritableStates.class);
        final WritableSingletonState<EntityCounts> counts = mock(WritableSingletonState.class);
        when(writableStates.<EntityCounts>getSingleton(ENTITY_COUNTS_STATE_ID)).thenReturn(counts);
        when(counts.get()).thenReturn(EntityCounts.DEFAULT);
        final var states = endHashes.stream()
                .map(bytes -> {
                    final var state = mock(VirtualMapState.class);
                    final var root = mock(VirtualMap.class);
                    final var hash = new Hash(bytes, org.hiero.base.crypto.DigestType.SHA_384);
                    when(state.getRoot()).thenReturn(root);
                    when(root.getHash()).thenReturn(hash);
                    when(state.getHash()).thenReturn(hash);
                    when(state.getWritableStates(EntityIdService.NAME)).thenReturn(writableStates);
                    return state;
                })
                .toList();
        final var copyIndex = new AtomicInteger();
        when(lifecycle.copyMutableState())
                .thenAnswer(ignored -> states.get(Math.min(copyIndex.incrementAndGet(), states.size() - 1)));
        setField(validator, "stateLifecycleManager", lifecycle);
        setField(validator, "state", states.getFirst());
        setField(
                validator,
                "initializedGenesisStateHash",
                new Hash(PREV_BLOCK_ROOT_HASH, org.hiero.base.crypto.DigestType.SHA_384));
        setField(validator, "expectedRootHash", endHashes.getLast());
        setField(validator, "entityChanges", Map.of());
        // Mockito skips field initializers, so seed the cross-block hashing state the constructor would have set
        setField(validator, "digestType", org.hiero.base.crypto.DigestType.SHA_384);
        setField(validator, "preFoldIntermediateState", List.of());
        return new ReplayFixture(validator, lifecycle);
    }

    private static void setField(final StateChangesValidator validator, final String name, final Object value)
            throws ReflectiveOperationException {
        final var field = StateChangesValidator.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(validator, value);
    }

    private static Block blockWithStateHash(final long number, final Bytes stateHash, final boolean withProof) {
        final var footer = BlockItem.newBuilder()
                .blockFooter(BlockFooter.newBuilder()
                        .previousBlockRootHash(HASH_OF_ZERO_384)
                        .startOfBlockStateRootHash(stateHash))
                .build();
        final var header = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(number).blockTimestamp(new Timestamp(1, 0)))
                .build();
        return blockBuilder()
                .items(withProof ? List.of(header, footer, proofItem(number)) : List.of(header, footer))
                .build();
    }

    @Test
    void readsPreviousBlockRootHashFromCompleteNextBlock() {
        final var nextBlock = blockBuilder()
                .items(List.of(headerItem(100L), footerItem(PREV_BLOCK_ROOT_HASH), proofItem(100L)))
                .build();

        assertEquals(PREV_BLOCK_ROOT_HASH, currentBlockHashFromNextBlockFooter(nextBlock));
    }

    @Test
    void readsPreviousBlockRootHashFromIncompleteNextBlock() {
        // Regression for #24709: an incomplete block flushed at a freeze round ends with the
        // BlockFooter as its last item (no BlockProof). The validator must still recover the
        // previous block's root hash from the footer; otherwise the incremental block-hashes
        // chain desynchronizes from the signer's and later proofs fail to verify.
        final var nextBlock = blockBuilder()
                .items(List.of(headerItem(100L), footerItem(PREV_BLOCK_ROOT_HASH)))
                .build();

        assertEquals(PREV_BLOCK_ROOT_HASH, currentBlockHashFromNextBlockFooter(nextBlock));
    }

    @Test
    void returnsNullForEmptyNextBlock() {
        final var nextBlock = blockBuilder().items(List.of()).build();
        assertNull(currentBlockHashFromNextBlockFooter(nextBlock));
    }

    @Test
    void returnsNullWhenNextBlockHasNoFooter() {
        final var nextBlock = blockBuilder().items(List.of(proofItem(100L))).build();

        assertNull(currentBlockHashFromNextBlockFooter(nextBlock));
    }

    /**
     * Equivalence proof: the helper should treat complete and incomplete next-blocks identically
     * when their footers carry the same data. Before the fix, only the complete shape was
     * recognized; the incomplete shape silently left the validator's previousBlockHash stale,
     * desyncing the incremental block-hashes chain and producing spurious "Invalid signature in
     * proof" failures in subsequent sampled proofs.
     */
    @Test
    void completeAndIncompleteNextBlocksYieldSameHash() {
        final var completeNextBlock = blockBuilder()
                .items(List.of(headerItem(100L), footerItem(PREV_BLOCK_ROOT_HASH), proofItem(100L)))
                .build();
        final var incompleteNextBlock = blockBuilder()
                .items(List.of(headerItem(100L), footerItem(PREV_BLOCK_ROOT_HASH)))
                .build();

        final var fromComplete = currentBlockHashFromNextBlockFooter(completeNextBlock);
        final var fromIncomplete = currentBlockHashFromNextBlockFooter(incompleteNextBlock);

        assertEquals(PREV_BLOCK_ROOT_HASH, fromComplete);
        assertEquals(fromComplete, fromIncomplete);
    }

    /**
     * Demonstrates the bug: the pre-fix shortcut assumed the footer always sits at
     * {@code items.get(size - 2)}. For an incomplete block that assumption is wrong — {@code
     * size - 2} is the header (or some other non-footer item), so the old {@code hasBlockFooter()}
     * guard returned false and {@code previousBlockHash} was never updated. The helper now reads
     * the last item first, handling both shapes correctly.
     */
    @Test
    void oldSizeMinusTwoShortcutWouldHaveMissedIncompleteFooter() {
        final var incompleteNextBlock = blockBuilder()
                .items(List.of(headerItem(100L), footerItem(PREV_BLOCK_ROOT_HASH)))
                .build();
        final var items = incompleteNextBlock.items();

        // Inline the old buggy logic to prove it would have failed for this shape:
        final int oldFooterIndex = items.size() - 2;
        assertFalse(
                items.get(oldFooterIndex).hasBlockFooter(),
                "The old size-2 lookup finds a non-footer item in an incomplete block — this is the bug");

        // The new helper recovers the hash correctly:
        assertEquals(PREV_BLOCK_ROOT_HASH, currentBlockHashFromNextBlockFooter(incompleteNextBlock));
    }

    /**
     * Documents why the {@code else if (i + 1 < n)} guard in
     * {@code StateChangesValidator#validateBlocks} is required. The {@code hashChainBroken} branch
     * forces {@code shouldVerifyProof=false} regardless of position, so the fall-through can be
     * reached when {@code i == n - 1}. Without the guard, {@code blocks.get(i + 1)} throws; with
     * it, the iteration safely leaves {@code previousBlockHash} unchanged for the last block.
     *
     * <p>Note: this is a demonstrative test — it doesn't invoke {@code validateBlocks} itself
     * (which would require the full validator setup). A regression that removes the guard
     * wouldn't fail this test; it would fail at runtime with an OOB. Keep the guard.
     */
    @Test
    void documentsGuardRequirementAgainstOobOnLastBlock() {
        final var blocks = List.of(blockBuilder()
                .items(List.of(headerItem(100L), footerItem(PREV_BLOCK_ROOT_HASH), proofItem(100L)))
                .build());
        final int i = blocks.size() - 1;

        // Inline the old unguarded lookup to prove it would have failed for the trailing block:
        assertThrows(IndexOutOfBoundsException.class, () -> blocks.get(i + 1));
    }

    @Test
    void detectsBlockSignedWithCompressedWrapsProof() {
        final var block = blockBuilder()
                .items(List.of(
                        headerItem(437L),
                        footerItem(PREV_BLOCK_ROOT_HASH),
                        signedProofItem(437L, HintsLibraryImpl.VK_LENGTH + HintsLibraryImpl.SIGNATURE_LENGTH + 11368)))
                .build();

        assertTrue(carriesCompressedWrapsProof(block));
    }

    @Test
    void doesNotDetectCompressedWrapsProofInMockOrMissingProof() {
        final var mockSigned = blockBuilder()
                .items(List.of(headerItem(443L), footerItem(PREV_BLOCK_ROOT_HASH), signedProofItem(443L, 48)))
                .build();
        final var incomplete = blockBuilder()
                .items(List.of(headerItem(444L), footerItem(PREV_BLOCK_ROOT_HASH)))
                .build();

        assertFalse(carriesCompressedWrapsProof(mockSigned));
        assertFalse(carriesCompressedWrapsProof(incomplete));
        assertFalse(carriesCompressedWrapsProof(blockBuilder().build()));
    }

    private static Block.Builder blockBuilder() {
        return Block.newBuilder();
    }

    private static BlockItem headerItem(final long blockNumber) {
        return BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(blockNumber).build())
                .build();
    }

    private static BlockItem footerItem(final Bytes previousBlockRootHash) {
        return BlockItem.newBuilder()
                .blockFooter(BlockFooter.newBuilder()
                        .previousBlockRootHash(previousBlockRootHash)
                        .build())
                .build();
    }

    private static BlockItem proofItem(final long blockNumber) {
        return BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().block(blockNumber).build())
                .build();
    }

    private static BlockItem signedProofItem(final long blockNumber, final int signatureLength) {
        return BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder()
                        .block(blockNumber)
                        .signedBlockProof(TssSignedBlockProof.newBuilder()
                                .blockSignature(Bytes.wrap(new byte[signatureLength]))
                                .build())
                        .build())
                .build();
    }
}
