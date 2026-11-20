// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.BesuQBFTVerifierSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;

/**
 * Manages the call attempted by a {@link Bytes} payload received by the
 * {@link BesuQBFTVerifierSystemContract}.
 */
public class BesuQBFTVerifierCallAttempt extends AbstractCallAttempt<BesuQBFTVerifierCallAttempt> {
    // BesuQBFTVerifier has no redirect support — use a dummy function that will never match
    private static final Function NO_REDIRECT = new Function("noRedirect(address,bytes)");

    public BesuQBFTVerifierCallAttempt(
            @NonNull final Bytes input, @NonNull final CallAttemptOptions<BesuQBFTVerifierCallAttempt> options) {
        super(input, options, Set.of(options.recipientAddress()), NO_REDIRECT);
    }

    protected SystemContract systemContractKind() {
        return SystemContractMethod.SystemContract.BESU_QBFT_VERIFIER;
    }

    @Override
    protected BesuQBFTVerifierCallAttempt self() {
        return this;
    }
}
