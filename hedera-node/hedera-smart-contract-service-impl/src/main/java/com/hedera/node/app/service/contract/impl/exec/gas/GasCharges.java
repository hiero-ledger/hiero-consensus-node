// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

import org.apache.tuweni.bytes.Bytes;

/**
 * The gas charges of the transaction
 *
 *
 * @param intrinsicGas         the intrinsic gas cost of a transaction
 * @param minimumGasUsed       the minimum gas used for transaction.
 *                             Calculated as {@code max(intrinsicGas, floorGas)} where `floorGas` defined at <a href="https://eips.ethereum.org/EIPS/eip-7623">EIP-7623</a>.
 *                             See {@link HederaGasCalculatorImpl#transactionGasRequirements(Bytes, boolean, long)}
 * @param relayerAllowanceUsed the gas for the relayer
 */
public record GasCharges(long intrinsicGas, long minimumGasUsed, long relayerAllowanceUsed) {
    // A constant representing no gas charges.
    // A hook dispatch has no gas charges, because all gas is charged prior in crypto transfer.
    public static final GasCharges NONE = new GasCharges(0L, 0L, 0L);
}
