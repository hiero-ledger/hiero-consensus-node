/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
