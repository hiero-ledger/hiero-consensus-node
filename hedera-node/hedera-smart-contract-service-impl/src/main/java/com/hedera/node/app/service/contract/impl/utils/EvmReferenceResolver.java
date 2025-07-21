// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;

public interface EvmReferenceResolver {
    long resolve(Address address, HederaNativeOperations nativeOperations);
}
