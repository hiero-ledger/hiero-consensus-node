// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.NON_CANONICAL_REFERENCE_NUMBER;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitAddressOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.maybeMissingNumberOf;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;

public class DefaultEvmReferenceResolver implements EvmReferenceResolver {

    @Override
    public long resolve(@NonNull Address address, @NonNull HederaNativeOperations nativeOperations) {
        final var explicit = explicitFromHeadlong(address);
        final var number = maybeMissingNumberOf(explicit, nativeOperations);
        if (number == MISSING_ENTITY_NUMBER) {
            return MISSING_ENTITY_NUMBER;
        } else {
            final var account = nativeOperations.getAccount(
                    nativeOperations.entityIdFactory().newAccountId(number));
            if (account == null || account.deleted()) {
                return MISSING_ENTITY_NUMBER;
            } else if (!Arrays.equals(explicit, explicitAddressOf(account))) {
                return NON_CANONICAL_REFERENCE_NUMBER;
            }
            return number;
        }
    }
}
