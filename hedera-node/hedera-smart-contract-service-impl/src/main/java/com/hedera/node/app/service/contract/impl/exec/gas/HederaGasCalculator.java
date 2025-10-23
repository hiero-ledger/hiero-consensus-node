// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

public interface HederaGasCalculator extends GasCalculator {
    long transactionIntrinsicGasCost(final Bytes payload, final boolean isContractCreate, final long baselineCost);
}
