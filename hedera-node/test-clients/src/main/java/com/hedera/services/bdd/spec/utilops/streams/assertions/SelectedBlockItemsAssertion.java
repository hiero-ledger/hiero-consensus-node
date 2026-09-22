// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionalUnitTranslator;
import com.hedera.services.bdd.junit.support.translators.RoleFreeBlockUnitSplit;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Block-stream analog of {@link SelectedItemsAssertion}.
 *
 * <p>Translates each incoming {@link Block} back to record-stream items via
 * {@link BlockTransactionalUnitTranslator}, then applies the same
 * {@link BiPredicate}-and-{@link VisibleItemsValidator} pattern as the
 * record-stream variant. This lets existing selectors written against
 * {@link RecordStreamItem} (e.g. {@code TxnUtils::isSysFileUpdate}) be reused
 * unchanged when running under {@code streamMode=BLOCKS}.
 */
public class SelectedBlockItemsAssertion implements BlockStreamAssertion {
    private static final Logger log = LogManager.getLogger(SelectedBlockItemsAssertion.class);

    private final int expectedCount;
    private final HapiSpec spec;
    private final BiPredicate<HapiSpec, RecordStreamItem> test;
    private final VisibleItemsValidator validator;
    private final List<RecordStreamEntry> selectedEntries = new ArrayList<>();

    // One translator per assertion: BaseTranslator carries alias and nonce state
    // that must persist across blocks for translation to remain consistent.
    private final BlockTransactionalUnitTranslator translator;
    private final RoleFreeBlockUnitSplit split = new RoleFreeBlockUnitSplit();
    private boolean foundGenesis = false;

    public SelectedBlockItemsAssertion(
            final int expectedCount,
            @NonNull final HapiSpec spec,
            @NonNull final BiPredicate<HapiSpec, RecordStreamItem> test,
            @NonNull final VisibleItemsValidator validator) {
        this.expectedCount = expectedCount;
        this.spec = requireNonNull(spec);
        this.test = requireNonNull(test);
        this.validator = requireNonNull(validator);
        final var network = spec.targetNetworkOrThrow();
        this.translator = new BlockTransactionalUnitTranslator(network.shard(), network.realm());
    }

    @Override
    public boolean test(@NonNull final Block block) throws AssertionError {
        requireNonNull(block);
        // Genesis must be scanned before any translation so receipts/aliases match what the
        // record stream would have produced.
        if (!foundGenesis) {
            foundGenesis = translator.scanBlockForGenesis(block);
        }
        for (final var unit : split.split(block)) {
            final List<SingleTransactionRecord> translated;
            try {
                translated = translator.translate(unit.withBatchTransactionParts());
            } catch (final RuntimeException e) {
                // Translation issues for individual units shouldn't fail the whole assertion;
                // keep going so the selector still has a chance to find its matches.
                log.warn("Block-to-record translation failed for a unit; skipping", e);
                continue;
            }
            for (final var record : translated) {
                final var item = toItem(record);
                if (test.test(spec, item)) {
                    selectedEntries.add(RecordStreamEntry.from(item));
                    if (selectedEntries.size() == expectedCount) {
                        runValidator();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void runValidator() {
        try {
            validator.assertValid(
                    spec,
                    Map.of(
                            SelectedItemsAssertion.SELECTED_ITEMS_KEY,
                            new VisibleItems(new AtomicInteger(), selectedEntries)));
        } catch (final Throwable t) {
            if (t instanceof AssertionError ae) {
                throw ae;
            }
            throw new AssertionError("Unhandled exception in validator", t);
        }
    }

    private static RecordStreamItem toItem(@NonNull final SingleTransactionRecord record) {
        // PBJ source types are fully qualified to disambiguate from the already-imported
        // proto types of the same simple name.
        return RecordStreamItem.newBuilder()
                .setTransaction(pbjToProto(
                        record.transaction(), com.hedera.hapi.node.base.Transaction.class, Transaction.class))
                .setRecord(pbjToProto(
                        record.transactionRecord(),
                        com.hedera.hapi.node.transaction.TransactionRecord.class,
                        TransactionRecord.class))
                .build();
    }

    @Override
    public String toString() {
        return "SelectedBlockItems{matched=" + selectedEntries.size() + "/" + expectedCount + "}";
    }
}
