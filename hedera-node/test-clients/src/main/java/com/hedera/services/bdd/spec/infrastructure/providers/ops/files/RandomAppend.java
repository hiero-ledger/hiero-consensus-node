// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.files;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FILE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomAppend implements OpProvider {
    private static final byte[] MORE_BYTES = "This is really something else!".getBytes();

    private final ResponseCodeEnum[] permissiblePrechecks =
            standardPrechecksAnd(FILE_DELETED, INVALID_FILE_ID, INSUFFICIENT_TX_FEE);

    private final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(FILE_DELETED, INVALID_FILE_ID, INSUFFICIENT_TX_FEE, FAIL_INVALID);

    private final EntityNameProvider files;

    public RandomAppend(EntityNameProvider files) {
        this.files = files;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var target = files.getQualifying();
        if (target.isEmpty()) {
            return Optional.empty();
        }

        var op = TxnVerbs.fileAppend(target.get())
                .hasPrecheckFrom(permissiblePrechecks)
                .hasKnownStatusFrom(permissibleOutcomes)
                .content(MORE_BYTES);

        return Optional.of(op);
    }
}
