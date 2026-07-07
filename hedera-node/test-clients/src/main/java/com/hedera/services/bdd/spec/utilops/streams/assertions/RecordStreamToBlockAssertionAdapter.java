// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionalUnitTranslator;
import com.hedera.services.bdd.junit.support.translators.RoleFreeBlockUnitSplit;
import com.hedera.services.stream.proto.RecordStreamItem;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Adapts a {@link RecordStreamAssertion} to work as a {@link BlockStreamAssertion} by translating
 * each incoming {@link Block} into {@link RecordStreamItem}s and feeding them to the wrapped assertion.
 *
 * <p>In lenient mode ({@code suppressAssertionErrors=true}), assertion errors from the delegate are
 * logged but not propagated. This is used for "no failures" assertions where imperfect block-to-record
 * translation fidelity could cause false failures, while still allowing item collection for validators
 * that need the data.
 */
public class RecordStreamToBlockAssertionAdapter implements BlockStreamAssertion {
    private static final Logger log = LogManager.getLogger(RecordStreamToBlockAssertionAdapter.class);

    private final RecordStreamAssertion delegate;
    private final RoleFreeBlockUnitSplit blockSplitter;
    private final BlockTransactionalUnitTranslator blockTranslator;
    private final boolean suppressAssertionErrors;

    public RecordStreamToBlockAssertionAdapter(
            @NonNull final RecordStreamAssertion delegate, final long shard, final long realm) {
        this(delegate, shard, realm, false);
    }

    public RecordStreamToBlockAssertionAdapter(
            @NonNull final RecordStreamAssertion delegate,
            final long shard,
            final long realm,
            final boolean suppressAssertionErrors) {
        this.delegate = requireNonNull(delegate);
        this.blockSplitter = new RoleFreeBlockUnitSplit();
        this.blockTranslator = new BlockTransactionalUnitTranslator(shard, realm);
        this.suppressAssertionErrors = suppressAssertionErrors;
    }

    @Override
    public boolean test(@NonNull final Block block) throws AssertionError {
        requireNonNull(block);
        try {
            final var units = blockSplitter.split(block);
            for (final var unit : units) {
                final var records = blockTranslator.translate(unit.withBatchTransactionParts());
                for (final var record : records) {
                    final var item = toRecordStreamItem(record);
                    if (delegate.isApplicableTo(item)) {
                        if (delegate.test(item)) {
                            return true;
                        }
                    }
                }
            }
        } catch (final AssertionError e) {
            if (suppressAssertionErrors) {
                log.info("Suppressed assertion error from block-to-record translation (lenient mode)", e);
            } else {
                throw e;
            }
        } catch (final Exception e) {
            log.warn("Failed to translate block to record stream items", e);
        }
        return false;
    }

    private static RecordStreamItem toRecordStreamItem(@NonNull final SingleTransactionRecord record) {
        return RecordStreamItem.newBuilder()
                .setTransaction(fromPbj(record.transaction()))
                .setRecord(fromPbj(record.transactionRecord()))
                .build();
    }
}
