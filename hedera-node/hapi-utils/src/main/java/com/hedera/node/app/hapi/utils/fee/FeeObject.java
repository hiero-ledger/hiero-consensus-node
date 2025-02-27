// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

public record FeeObject(long nodeFee, long networkFee, long serviceFee) {

    public long totalFee() {
        return networkFee() + serviceFee() + nodeFee();
    }
}
