// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

/**
 * @param gasRequirement the gas requirements of the transaction
 * @param relayerAllowanceUsed the gas for the relayer
 */
public record GasCharges(GasRequirements gasRequirement, long relayerAllowanceUsed) {
    // A constant representing no gas charges.
    // A hook dispatch has no gas charges, because all gas is charged prior in crypto transfer.
    public static final GasCharges NONE = new GasCharges(GasRequirements.NONE, 0L);

    public long intrinsicGas() {
        return gasRequirement.intrinsicGas();
    }
}
