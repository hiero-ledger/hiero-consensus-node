// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create;

import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Singleton;

@Singleton
public final class CreateCommons {

    /**
     * A set of `Function` objects representing various create functions for fungible and non-fungible tokens. This
     * set is used in {@link com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor}
     * to determine if a given call attempt is a creation call, because we do not allow sending value to Hedera
     * system contracts except in the case of token creation
     */
    public static final Set<SystemContractMethod> createMethodsSet = new HashSet<>();

    private CreateCommons() {}
}
