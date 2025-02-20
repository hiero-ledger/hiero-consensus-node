// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public abstract class AbstractFailableNonStaticCall extends AbstractFailableCall {
    public AbstractFailableNonStaticCall(@NonNull final Set<ClassicFailureMode> failureModes) {
        super(failureModes);
    }

    @Override
    public boolean staticCallOk() {
        return false;
    }
}
