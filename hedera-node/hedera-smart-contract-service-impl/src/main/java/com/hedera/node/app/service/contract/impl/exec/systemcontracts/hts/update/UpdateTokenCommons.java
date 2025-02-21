// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Singleton;

@Singleton
public final class UpdateTokenCommons {

    public static final Set<SystemContractMethod> updateMethodsSet = new HashSet<>();

    private UpdateTokenCommons() {}
}
