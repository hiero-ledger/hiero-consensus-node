// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Predicate;
import org.opentest4j.AssertionFailedError;

/**
 * Implementation support for a {@link RecordStreamAssertion} that filters to a single
 * record stream item, and passes as long as a given assertion does not throw an exception.
 */
public class TransactionBodyAssertion extends BaseIdScreenedAssertion {
    private final Predicate<TransactionID> idFilter;
    private final AssertingBiConsumer<HapiSpec, TransactionBody> bodyAssertion;

    public TransactionBodyAssertion(
            @NonNull final String specTxnId,
            @NonNull final HapiSpec spec,
            @NonNull final Predicate<TransactionID> idFilter,
            @NonNull final AssertingBiConsumer<HapiSpec, TransactionBody> bodyAssertion) {
        super(specTxnId, spec);
        this.idFilter = idFilter;
        this.bodyAssertion = bodyAssertion;
    }

    @Override
    protected boolean filter(@NonNull final TransactionID txnId) {
        return idFilter.test(txnId);
    }

    @Override
    public boolean test(@NonNull final RecordStreamItem item) throws AssertionError {
        try {
            final var txn = CommonUtils.extractTransactionBody(item.getTransaction());
            bodyAssertion.accept(spec, txn);
            return true;
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionFailedError("Transaction body could not be parsed from item " + item);
        }
    }
}
