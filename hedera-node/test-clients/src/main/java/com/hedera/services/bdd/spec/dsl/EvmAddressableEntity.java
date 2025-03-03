// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Defines an entity that can be addressed within the EVM.
 */
public interface EvmAddressableEntity {
    /**
     * Returns the address of the entity on the given network.
     *
     * @param network the network
     * @return the address
     */
    Address addressOn(@NonNull HederaNetwork network);
}
