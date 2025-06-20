// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.files;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FILE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomFileInfo implements OpProvider {
    private final ResponseCodeEnum[] permissibleCostAnswerPrechecks =
            standardQueryPrechecksAnd(FILE_DELETED, INSUFFICIENT_TX_FEE, INVALID_FILE_ID);

    private final ResponseCodeEnum[] permissibleAnswerOnlyPrechecks =
            standardQueryPrechecksAnd(FILE_DELETED, INSUFFICIENT_TX_FEE, INVALID_FILE_ID);

    private final EntityNameProvider files;

    public RandomFileInfo(EntityNameProvider files) {
        this.files = files;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var target = files.getQualifying();
        if (target.isEmpty()) {
            return Optional.empty();
        }

        var op = QueryVerbs.getFileInfo(target.get())
                .hasCostAnswerPrecheckFrom(permissibleCostAnswerPrechecks)
                .hasAnswerOnlyPrecheckFrom(permissibleAnswerOnlyPrechecks);

        return Optional.of(op);
    }
}
