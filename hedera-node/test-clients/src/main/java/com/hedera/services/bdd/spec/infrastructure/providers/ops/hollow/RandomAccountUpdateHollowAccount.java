// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccountUpdate;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoUpdate;
import java.util.Optional;

public class RandomAccountUpdateHollowAccount extends RandomAccountUpdate {
    private final String[] signers;

    public RandomAccountUpdateHollowAccount(EntityNameProvider keys, EntityNameProvider accounts, String... signers) {
        super(keys, accounts);
        this.signers = signers;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var target = accounts.getQualifying();
        if (target.isEmpty()) {
            return Optional.empty();
        }
        final var newKey = keys.getQualifying();
        HapiCryptoUpdate op = cryptoUpdate(target.get())
                .key(newKey.get())
                .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
                .hasKnownStatusFrom(INVALID_SIGNATURE)
                .signedBy(signers);

        return Optional.of(op);
    }
}
