// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.concreteKeyOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.hapi.utils.keys.KeyMaterializer;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Minimal KeySource backed by the account store
 */
record KeySourceFromStore(WritableAccountStore store) implements KeyMaterializer.Source {
    @Override
    public Key materializedKeyOrThrow(@NonNull final AccountID id) {
        final var a = requireNonNull(store.get(id));
        return concreteKeyOf(a);
    }

    @Override
    public Key materializedKeyOrThrow(@NonNull final ContractID id) {
        final var a = requireNonNull(store.getContractById(id));
        return concreteKeyOf(a);
    }
}
