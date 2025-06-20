// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

/**
 * @param intrinsicGas the intrinsic gas cost of a transaction
 * @param relayerAllowanceUsed the gas for the relayer
 */
public record GasCharges(long intrinsicGas, long relayerAllowanceUsed) {}
