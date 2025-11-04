// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

import org.apache.tuweni.bytes.Bytes;

/**
 * The gas requirements of the transaction
 *
 * @param intrinsicGas   the intrinsic gas cost of a transaction
 * @param minimumGasUsed the minimum gas requirement for transaction.
 *                       Calculated as {@code max(intrinsicGas, floorGas)} where `floorGas` defined at <a href="https://eips.ethereum.org/EIPS/eip-7623">EIP-7623</a>.
 *                       See {@link HederaGasCalculatorImpl#transactionGasRequirements(Bytes, boolean, long)}
 */
public record GasRequirements(long intrinsicGas, long minimumGasUsed) {
    // A constant representing no gas requirement.
    // A hook dispatch has no gas requirement, because all gas is charged prior in crypto transfer.
    public static final GasRequirements NONE = new GasRequirements(0L, 0L);

    public static GasRequirements of(long intrinsicGas) {
        return new GasRequirements(intrinsicGas, intrinsicGas);
    }
}
