// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.files;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FILE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomFileDeletion implements OpProvider {
    private final RegistrySourcedNameProvider<FileID> files;

    private final ResponseCodeEnum[] permissiblePrechecks = standardPrechecksAnd(FILE_DELETED, INVALID_FILE_ID);
    private final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(FILE_DELETED, INVALID_FILE_ID, INVALID_SIGNATURE);

    public RandomFileDeletion(RegistrySourcedNameProvider<FileID> files) {
        this.files = files;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var tbd = files.getQualifying();
        if (tbd.isEmpty()) {
            return Optional.empty();
        }
        if (tbd.get().endsWith("-bytecode")) {
            return Optional.empty();
        }
        var op = fileDelete(tbd.get())
                .purging()
                .hasPrecheckFrom(permissiblePrechecks)
                .hasKnownStatusFrom(permissibleOutcomes);

        return Optional.of(op);
    }
}
