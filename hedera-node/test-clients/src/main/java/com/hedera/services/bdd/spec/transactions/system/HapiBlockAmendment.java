// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.system;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.node.network.BlockAmendment;
import com.hedera.hapi.node.network.BlockAmendmentOperation;
import com.hedera.hapi.node.network.BlockAmendmentTransactionBody;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Submits a {@code BlockAmendment} transaction, which amends the block stream record of one or more past blocks.
 *
 * <p>Each call to {@link #andAmending(long)} starts the amendment of another block; {@link #withOperations} and
 * {@link #voidingPreviousAmendments()} apply to the block most recently named. Operations are given as PBJ types,
 * optionally computed from the spec at submission time (for example, from block contents read from a node's block
 * stream).
 */
public class HapiBlockAmendment extends HapiTxnOp<HapiBlockAmendment> {
    /** The amendment of one block, with its parts computed from the spec at submission time. */
    private static final class AmendedBlock {
        private final ToLongFunction<HapiSpec> blockNumberFn;
        private Function<HapiSpec, List<BlockAmendmentOperation>> operationsFn = spec -> List.of();
        private boolean voidPreviousAmendments = false;

        private AmendedBlock(@NonNull final ToLongFunction<HapiSpec> blockNumberFn) {
            this.blockNumberFn = requireNonNull(blockNumberFn);
        }

        private BlockAmendment toPbj(@NonNull final HapiSpec spec) {
            return BlockAmendment.newBuilder()
                    .blockNumber(blockNumberFn.applyAsLong(spec))
                    .operations(operationsFn.apply(spec))
                    .voidPreviousAmendments(voidPreviousAmendments)
                    .build();
        }
    }

    private final List<AmendedBlock> amendedBlocks = new ArrayList<>();

    /**
     * Creates a transaction that amends no block until {@link #andAmending(long)} is called.
     */
    public HapiBlockAmendment() {
        // No amended blocks yet
    }

    public HapiBlockAmendment(final long blockNumber) {
        andAmending(blockNumber);
    }

    public HapiBlockAmendment(@NonNull final ToLongFunction<HapiSpec> blockNumberFn) {
        andAmending(blockNumberFn);
    }

    /**
     * Starts the amendment of another block.
     *
     * @param blockNumber the number of the block
     * @return this
     */
    public HapiBlockAmendment andAmending(final long blockNumber) {
        return andAmending(spec -> blockNumber);
    }

    /**
     * Starts the amendment of another block, whose number is computed from the spec at submission time.
     *
     * @param blockNumberFn the function computing the number of the block
     * @return this
     */
    public HapiBlockAmendment andAmending(@NonNull final ToLongFunction<HapiSpec> blockNumberFn) {
        amendedBlocks.add(new AmendedBlock(blockNumberFn));
        return this;
    }

    /**
     * Sets the operations of the amendment of the block most recently named.
     *
     * @param operations the operations
     * @return this
     */
    public HapiBlockAmendment withOperations(@NonNull final BlockAmendmentOperation... operations) {
        final var ops = Arrays.asList(operations);
        return withOperations(spec -> ops);
    }

    /**
     * Sets the operations of the amendment of the block most recently named, computed from the spec at submission
     * time.
     *
     * @param operationsFn the function computing the operations
     * @return this
     */
    public HapiBlockAmendment withOperations(
            @NonNull final Function<HapiSpec, List<BlockAmendmentOperation>> operationsFn) {
        currentBlock().operationsFn = requireNonNull(operationsFn);
        return this;
    }

    /**
     * Voids every earlier amendment of the block most recently named, so the operations of this amendment of that
     * block apply to the original block.
     *
     * @return this
     */
    public HapiBlockAmendment voidingPreviousAmendments() {
        currentBlock().voidPreviousAmendments = true;
        return this;
    }

    private AmendedBlock currentBlock() {
        if (amendedBlocks.isEmpty()) {
            throw new IllegalStateException("No block to amend; call andAmending() first");
        }
        return amendedBlocks.getLast();
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.BlockAmendment;
    }

    @Override
    protected HapiBlockAmendment self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(@NonNull final HapiSpec spec) throws Throwable {
        final var pbjBody = BlockAmendmentTransactionBody.newBuilder()
                .amendments(amendedBlocks.stream()
                        .map(amendedBlock -> amendedBlock.toPbj(spec))
                        .toList())
                .build();
        final var opBody = spec.txns()
                .<com.hederahashgraph.api.proto.java.BlockAmendmentTransactionBody,
                        com.hederahashgraph.api.proto.java.BlockAmendmentTransactionBody.Builder>
                        body(
                                com.hederahashgraph.api.proto.java.BlockAmendmentTransactionBody.class,
                                b -> b.mergeFrom(toProtoc(pbjBody)));
        return b -> b.setBlockAmendment(opBody);
    }

    private static com.hederahashgraph.api.proto.java.BlockAmendmentTransactionBody toProtoc(
            @NonNull final BlockAmendmentTransactionBody pbjBody) {
        try {
            return com.hederahashgraph.api.proto.java.BlockAmendmentTransactionBody.parseFrom(
                    BlockAmendmentTransactionBody.PROTOBUF.toBytes(pbjBody).toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper();
    }
}
