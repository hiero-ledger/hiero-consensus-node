/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.totalsupply;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
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

/**
 * Translates {@code totalSupply()} calls to the HTS system contract.
 */
@Singleton
public class TotalSupplyTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for totalSupply() method. */
    public static final SystemContractMethod TOTAL_SUPPLY = SystemContractMethod.declare(
                    "totalSupply()", ReturnTypes.INT)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.ERC20, Category.ERC721, Category.TOKEN_QUERY);

    /**
     * Default constructor for injection.
     */
    @Inject
    public TotalSupplyTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(TOTAL_SUPPLY);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        return attempt.isMethod(TOTAL_SUPPLY);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TotalSupplyCall callFrom(@NonNull final HtsCallAttempt attempt) {
        return new TotalSupplyCall(
                attempt.systemContractGasCalculator(), attempt.enhancement(), attempt.redirectToken());
    }
}
