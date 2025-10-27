// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.AccountID;
import java.util.function.Supplier;

/**
 * An interface that defines EVM frame state factory instances
 */
@FunctionalInterface
public interface EvmFrameStateFactory extends Supplier<EvmFrameState> {
    /**
     * If the factory is for a hook execution, returns the account id of the hook's rent payer.
     * @throws UnsupportedOperationException if the factory is not for a hook execution
     */
    default AccountID hookRentPayerId() {
        throw new UnsupportedOperationException();
    }
}
