// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public record HydratedEthTxData(
        @Nullable EthTxData ethTxData, @NonNull ResponseCodeEnum status, boolean hydratedFromFile) {
    public HydratedEthTxData {
        requireNonNull(status);
        if (status == OK) {
            requireNonNull(ethTxData);
        }
    }

    public static HydratedEthTxData successFrom(@NonNull final EthTxData ethTxData, final boolean hydratedFromFile) {
        return new HydratedEthTxData(ethTxData, OK, hydratedFromFile);
    }

    public static HydratedEthTxData failureFrom(@NonNull final ResponseCodeEnum status) {
        return new HydratedEthTxData(null, status, false);
    }

    public boolean isAvailable() {
        return ethTxData != null;
    }

    public @NonNull EthTxData ethTxDataOrThrow() {
        return requireNonNull(ethTxData);
    }
}
