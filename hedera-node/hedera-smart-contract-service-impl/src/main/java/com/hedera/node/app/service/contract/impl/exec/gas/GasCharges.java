// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

/**
 * @param intrinsicGas the intrinsic gas cost of a transaction
 * @param relayerAllowanceUsed the gas for the relayer
 */
public record GasCharges(long intrinsicGas, long relayerAllowanceUsed) {
    // A constant representing no gas charges.
    // A hook dispatch has no gas charges, because all gas is charged prior in crypto transfer.
    public static final GasCharges NONE = new GasCharges(0L, 0L);
}
