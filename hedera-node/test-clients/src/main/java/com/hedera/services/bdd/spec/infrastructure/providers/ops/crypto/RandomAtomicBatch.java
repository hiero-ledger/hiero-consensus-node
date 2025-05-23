// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

public class RandomAtomicBatch implements OpProvider {

    private OpProvider[] ops;

    // TODO: fix
    private final ResponseCodeEnum[] permissibleOutcomes = OpProvider.standardOutcomesAnd(INNER_TRANSACTION_FAILED);
    private final ResponseCodeEnum[] permissiblePrechecks = OpProvider.standardPrechecksAnd();

    public RandomAtomicBatch(OpProvider... ops) {
        this.ops = ops;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        var opsToInclude = new ArrayList<HapiTxnOp>();

        for (var o : ops) {
            var op = o.get();
            if (op.isPresent() && op.get() instanceof HapiTxnOp<?>) {
                opsToInclude.add((HapiTxnOp<?>) op.get());
            }
        }

        var opsToIncludeArray = new HapiTxnOp<?>[opsToInclude.size()];
        for (int i = 0; i < opsToInclude.size(); i++) {
            opsToInclude.get(i).batchKey(UNIQUE_PAYER_ACCOUNT);
            opsToIncludeArray[i] = opsToInclude.get(i);
        }

        var atomicBatch = TxnVerbs.atomicBatch(opsToIncludeArray)
            .payingWith(UNIQUE_PAYER_ACCOUNT)
            .hasPrecheckFrom(flattenResponseCodes(
                STANDARD_PERMISSIBLE_PRECHECKS,
                permissiblePrechecks,
                RandomAccount.permissiblePrechecks,
                RandomAccountDeletion.permissiblePrechecks,
                RandomAccountDeletionWithReceiver.permissiblePrechecks,
                // RandomAccountInfo.permissiblePrechecks, TODO: what to do with these?
                RandomTransferFromSigner.permissiblePrechecks,
                RandomTransferToSigner.permissiblePrechecks))
            .hasKnownStatusFrom(flattenResponseCodes(
                STANDARD_PERMISSIBLE_OUTCOMES,
                permissibleOutcomes,
                RandomAccount.permissibleOutcomes,
                RandomAccountDeletion.permissibleOutcomes,
                RandomAccountDeletionWithReceiver.permissibleOutcomes,
                RandomAccountUpdate.permissibleOutcomes,
                RandomTransfer.permissibleOutcomes,
                // RandomAccountInfo.permissiblePrechecks, TODO: what to do with these?
                RandomTransferFromSigner.permissibleOutcomes,
                RandomTransferToSigner.permissibleOutcomes));

        return Optional.of(atomicBatch);
    }

    // TODO: test this
    private static ResponseCodeEnum[] flattenResponseCodes(ResponseCodeEnum[]... listOfResponseCodeEnums) {
        var flattened = new ArrayList<ResponseCodeEnum>();
        for (var responseCodeEnum : listOfResponseCodeEnums) {
            flattened.addAll(Arrays.asList(responseCodeEnum));
        }
        return flattened.toArray(new ResponseCodeEnum[0]);
    }
}
