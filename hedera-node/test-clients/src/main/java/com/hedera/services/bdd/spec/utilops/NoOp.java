// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import com.hedera.services.bdd.spec.HapiSpec;

public class NoOp extends UtilOp {
    @Override
    protected boolean submitOp(HapiSpec spec) {
        return false;
    }
}
