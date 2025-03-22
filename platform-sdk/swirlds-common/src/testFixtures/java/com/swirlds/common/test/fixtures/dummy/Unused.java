// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.dummy;

import com.swirlds.platform.system.transaction.TransactionWrapperUtils;

/**
 * This class is not used. It keeps a reference to the module com.swirlds.platform.core, because otherwise we
 * would have to remove the dependency and unit tests would fail.
 */
public class Unused {

    public Unused() {
        TransactionWrapperUtils.createAppPayloadWrapper(new byte[0]);
    }
}
