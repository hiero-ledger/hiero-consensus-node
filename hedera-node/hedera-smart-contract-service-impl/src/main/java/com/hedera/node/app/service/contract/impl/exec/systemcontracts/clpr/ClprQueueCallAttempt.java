// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;

/**
 * Manages the call attempted by a {@link Bytes} payload received by the CLPR queue system contract.
 */
public class ClprQueueCallAttempt extends AbstractCallAttempt<ClprQueueCallAttempt> {
    /** Selector placeholder for compatibility with base redirect handling. */
    public static final Function REDIRECT_FOR_CLPR_QUEUE = new Function("redirectForClprQueue(address,bytes)");

    public static final SystemContractMethod UNSUPPORTED_SELECTOR_METHOD = SystemContractMethod.declare(
                    "unsupportedSelector(bytes4)", ReturnTypes.INT_64)
            .withContract(SystemContractMethod.SystemContract.CLPR);

    public ClprQueueCallAttempt(
            @NonNull final Bytes input, @NonNull final CallAttemptOptions<ClprQueueCallAttempt> options) {
        super(input, options, REDIRECT_FOR_CLPR_QUEUE);
    }

    @Override
    protected SystemContractMethod.SystemContract systemContractKind() {
        return SystemContractMethod.SystemContract.CLPR;
    }

    @Override
    protected ClprQueueCallAttempt self() {
        return this;
    }

    @Override
    public @NonNull Call asExecutableCall() {
        final var self = self();
        for (final var translator : options.callTranslators()) {
            final var call = translator.translateCallAttempt(self);
            if (call != null) {
                return call;
            }
        }
        final var call = new ClprQueueRevertCall(
                options.gasCalculator(), options.enhancement(), "CLPR_QUEUE_UNSUPPORTED_SELECTOR");
        call.setSystemContractMethod(requireNonNull(UNSUPPORTED_SELECTOR_METHOD));
        return call;
    }
}
