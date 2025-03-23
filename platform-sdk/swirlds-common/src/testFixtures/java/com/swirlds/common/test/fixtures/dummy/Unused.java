// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.dummy;

import com.swirlds.platform.system.transaction.TransactionWrapperUtils;

/**
 * This class is not used. It keeps a reference to the module com.swirlds.platform.core, because otherwise we
 * would have to remove the dependency and unit tests would fail.
 *
 * <p>Created <a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/18461">this ticket</a> to remember
 * investigating and removing this class.
 *
 * @deprecated This class is not used
 */
@Deprecated(forRemoval = true)
public class Unused {

    public Unused() {
        TransactionWrapperUtils.createAppPayloadWrapper(new byte[0]);
    }
}
