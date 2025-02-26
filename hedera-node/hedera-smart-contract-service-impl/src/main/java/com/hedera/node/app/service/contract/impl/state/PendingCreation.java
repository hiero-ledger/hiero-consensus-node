// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.aliasFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.datatypes.Address;

public record PendingCreation(
        @NonNull Address address, long number, long parentNumber, @Nullable ContractCreateTransactionBody body) {
    public PendingCreation {
        requireNonNull(address);
    }

    public boolean isHapiCreation() {
        return body != null;
    }

    @Nullable
    public Bytes aliasIfApplicable(@NonNull final EntityIdFactory entityIdFactory) {
        return isLongZero(entityIdFactory, address) ? null : aliasFrom(address);
    }
}
