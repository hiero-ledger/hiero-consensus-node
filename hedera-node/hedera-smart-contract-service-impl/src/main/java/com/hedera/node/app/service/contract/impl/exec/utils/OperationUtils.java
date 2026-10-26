// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

public class OperationUtils {
    public static boolean isDeficientGas(@NonNull final MessageFrame frame, @NonNull final long cost) {
        return frame.getRemainingGas() < cost;
    }

    /**
     * Returns true if an access to the given address is priced warm as per EIP-2929; that is, if the
     * address was already accessed in the transaction (or pre-warmed at its start, as the coinbase and
     * any access list entries are), or is a precompile. Does <b>not</b> warm the address.
     *
     * @param frame the current message frame
     * @param address the address being accessed
     * @param gasCalculator the gas calculator, which knows the precompile address range
     * @return true if the access is priced warm
     */
    public static boolean isWarmAccess(
            @NonNull final MessageFrame frame,
            @NonNull final Address address,
            @NonNull final GasCalculator gasCalculator) {
        return frame.isAddressWarm(address) || gasCalculator.isPrecompile(address);
    }
}
