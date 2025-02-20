// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import com.hederahashgraph.api.proto.java.SubType;

/**
 * A functional interface for creating an object of type {@code R}.
 * @param <R> the type of object to create
 */
@FunctionalInterface
public interface TokenOpsProducer<R> {
    /**
     * Creates an object of type {@code R}.
     * @param bpt the base price of the transaction
     * @param subType the subType of the transaction
     * @param transferRecordRb the record bytes for the transfer
     * @param serialNumsCount the serial number count
     * @return
     */
    R create(int bpt, SubType subType, long transferRecordRb, int serialNumsCount);
}
