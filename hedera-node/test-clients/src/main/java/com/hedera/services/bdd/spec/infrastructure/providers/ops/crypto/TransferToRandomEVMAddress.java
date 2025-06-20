// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import java.util.Optional;

public class TransferToRandomEVMAddress implements OpProvider {
    private final EntityNameProvider keys;

    public TransferToRandomEVMAddress(EntityNameProvider keys) {
        this.keys = keys;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var involvedKey = keys.getQualifying();
        if (involvedKey.isEmpty()) {
            return Optional.empty();
        }

        var to = involvedKey.get();
        HapiCryptoTransfer op = cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, to, 5, true))
                .hasKnownStatusFrom(SUCCESS, INVALID_SIGNATURE)
                .payingWith(UNIQUE_PAYER_ACCOUNT);

        return Optional.of(op);
    }
}
