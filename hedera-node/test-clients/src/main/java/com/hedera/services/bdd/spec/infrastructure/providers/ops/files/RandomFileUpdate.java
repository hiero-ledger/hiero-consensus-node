// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.files;

import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FILE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static java.util.Collections.swap;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

public class RandomFileUpdate implements OpProvider {
    private SplittableRandom r = new SplittableRandom();

    private enum Targets {
        CONTENTS,
        EXPIRY,
        WACL
    }

    private final ResponseCodeEnum[] permissiblePrechecks =
            standardPrechecksAnd(FILE_DELETED, INVALID_FILE_ID, INSUFFICIENT_TX_FEE);

    private final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(FILE_DELETED, INVALID_FILE_ID, INSUFFICIENT_TX_FEE, INVALID_SIGNATURE);

    private final EntityNameProvider files;

    public RandomFileUpdate(EntityNameProvider files) {
        this.files = files;
    }

    @Override
    public List<SpecOperation> suggestedInitializers() {
        return List.of(
                newKeyNamed("NEW-WACL-1").shape(listOf(1)),
                newKeyNamed("NEW-WACL-2").shape(listOf(2)),
                newKeyNamed("NEW-WACL-3").shape(listOf(3)),
                newKeyNamed("NEW-WACL-4").shape(listOf(4)),
                newKeyNamed("NEW-WACL-5").shape(listOf(5)));
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var target = files.getQualifying();
        if (target.isEmpty()) {
            return Optional.empty();
        }

        if (target.get().endsWith("-bytecode")) {
            return Optional.empty();
        }

        var op = TxnVerbs.fileUpdate(target.get())
                .hasPrecheckFrom(permissiblePrechecks)
                .hasKnownStatusFrom(permissibleOutcomes);

        var couldUpdate = new ArrayList<>(List.of(Targets.values()));
        int n = couldUpdate.size();
        int howMany = r.nextInt(n) + 1;
        for (int i = 0; i < howMany; i++) {
            swap(couldUpdate, i, i + r.nextInt(n - i));
        }
        for (int i = 0; i < howMany; i++) {
            switch (couldUpdate.get(i)) {
                case EXPIRY:
                    op.extendingExpiryBy(1_234L);
                    break;
                case WACL:
                    op.wacl(miscNewWacl());
                    break;
                case CONTENTS:
                    op.contents(miscNewContents());
                    break;
            }
        }

        return Optional.of(op);
    }

    private byte[] miscNewContents() {
        return RandomFile.contentChoices[r.nextInt(RandomFile.contentChoices.length)];
    }

    private String miscNewWacl() {
        return String.format("NEW-WACL-%d", r.nextInt(5) + 1);
    }
}
