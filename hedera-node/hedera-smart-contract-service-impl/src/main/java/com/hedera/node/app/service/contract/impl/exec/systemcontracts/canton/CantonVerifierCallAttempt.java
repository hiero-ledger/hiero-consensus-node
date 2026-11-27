// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.CantonVerifierSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;

/**
 * Manages the call attempted by a {@link Bytes} payload received by the
 * {@link CantonVerifierSystemContract}.
 */
public class CantonVerifierCallAttempt extends AbstractCallAttempt<CantonVerifierCallAttempt> {
    // CantonVerifier has no redirect support — use a dummy function that will never match
    private static final Function NO_REDIRECT = new Function("noRedirect(address,bytes)");

    public CantonVerifierCallAttempt(
            @NonNull final Bytes input, @NonNull final CallAttemptOptions<CantonVerifierCallAttempt> options) {
        super(input, options, Set.of(options.recipientAddress()), NO_REDIRECT);
    }

    protected SystemContract systemContractKind() {
        return SystemContractMethod.SystemContract.CANTON_VERIFIER;
    }

    @Override
    protected CantonVerifierCallAttempt self() {
        return this;
    }
}
