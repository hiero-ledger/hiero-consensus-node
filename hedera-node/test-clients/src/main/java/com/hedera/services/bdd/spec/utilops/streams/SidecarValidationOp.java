// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hedera.services.bdd.spec.verification.traceability.SidecarWatcher;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link UtilOp} that initializes a {@link SidecarWatcher} for the
 * given {@link HapiSpec} and registers it with
 * {@link HapiSpec#setSidecarWatcher(SidecarWatcher)}.
 */
public class SidecarValidationOp extends UtilOp {
    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        spec.setSidecarWatcher(SidecarWatcher.forSpec(spec));
        return false;
    }
}
