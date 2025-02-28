// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.maybeMissingNumberOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.datatypes.Address;

/**
 * Implements the default {@link VerificationStrategies} for use in signature activation tests.
 */
public class DefaultVerificationStrategies implements VerificationStrategies {
    /**
     * Returns a {@link VerificationStrategy} that will activate <b>only</b> delegatable contract id and
     * contract id keys (the latter if delegatable permissions are not required).
     *
     * <p>This is the standard strategy under the approval-based security model, where a contract gains
     * authorization for an entity only by having its id or address added to that entity's controlling
     * key structure.
     *
     * @param sender the contract whose keys are to be activated
     * @param requiresDelegatePermission whether the strategy should require a delegatable contract id key
     * @param nativeOperations the operations to use for looking up the contract's number
     * @return a {@link VerificationStrategy} that will activate only delegatable contract id and contract id keys
     */
    public VerificationStrategy activatingOnlyContractKeysFor(
            @NonNull final Address sender,
            final boolean requiresDelegatePermission,
            @NonNull final HederaNativeOperations nativeOperations) {
        final var contractNum = maybeMissingNumberOf(sender, nativeOperations);
        if (contractNum == MISSING_ENTITY_NUMBER) {
            throw new IllegalArgumentException("Cannot verify against missing contract " + sender);
        }
        return new ActiveContractVerificationStrategy(
                nativeOperations.entityIdFactory().newContractId(contractNum),
                tuweniToPbjBytes(sender),
                requiresDelegatePermission,
                ActiveContractVerificationStrategy.UseTopLevelSigs.NO);
    }
}
