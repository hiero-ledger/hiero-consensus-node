// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RandomAtomicBatch implements OpProvider {

    private final OpProvider[] ops;
    final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(INNER_TRANSACTION_FAILED);

    public RandomAtomicBatch(OpProvider... ops) {
        this.ops = ops;
    }

    // Gather all initializers from the inner operations
    @Override
    public List<SpecOperation> suggestedInitializers() {
        var innerTransactionsInitializers = new ArrayList<SpecOperation>();
        for (var op : ops) {
            innerTransactionsInitializers.addAll(op.suggestedInitializers());
        }
        return innerTransactionsInitializers;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        var opsToInclude = new ArrayList<HapiTxnOp>();

        // Iterate through the provided operations and collect those that are instances of HapiTxnOp
        for (var o : ops) {
            var op = o.get();
            if (op.isPresent() && op.get() instanceof HapiTxnOp<?>) {
                opsToInclude.add((HapiTxnOp<?>) op.get());
            }
        }

        // Iterate through the provided operations and add batchKeys to each
        var opsToIncludeArray = new HapiTxnOp<?>[opsToInclude.size()];
        for (int i = 0; i < opsToInclude.size(); i++) {
            opsToInclude.get(i).batchKey(UNIQUE_PAYER_ACCOUNT);
            opsToIncludeArray[i] = opsToInclude.get(i);
        }

        var atomicBatch = TxnVerbs.atomicBatch(opsToIncludeArray)
                .payingWith(UNIQUE_PAYER_ACCOUNT)
                .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
                .hasKnownStatusFrom(permissibleOutcomes);

        return Optional.of(atomicBatch);
    }
}
