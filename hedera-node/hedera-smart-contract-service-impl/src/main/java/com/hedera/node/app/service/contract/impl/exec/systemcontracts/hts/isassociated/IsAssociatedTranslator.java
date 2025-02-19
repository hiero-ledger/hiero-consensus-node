/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Modifier;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class IsAssociatedTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for isAssociated() method. */
    public static final SystemContractMethod IS_ASSOCIATED = SystemContractMethod.declare(
                    "isAssociated()", ReturnTypes.BOOL)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.TOKEN_QUERY, Category.ASSOCIATION);

    /**
     * Default constructor for injection.
     */
    @Inject
    public IsAssociatedTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(IS_ASSOCIATED);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        if (!attempt.isTokenRedirect()) return Optional.empty();
        return attempt.isMethod(IS_ASSOCIATED);
    }

    @Override
    public final Call callFrom(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return new IsAssociatedCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                attempt.senderId(),
                attempt.redirectToken());
    }
}
