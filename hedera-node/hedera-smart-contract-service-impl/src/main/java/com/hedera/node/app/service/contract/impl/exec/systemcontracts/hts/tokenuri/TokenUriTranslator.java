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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenuri;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asExactLongValueOrZero;
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

/**
 * Translates {@code tokenUri()} calls to the HTS system contract.
 */
@Singleton
public class TokenUriTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for tokenURI(uint256) method. */
    public static final SystemContractMethod TOKEN_URI = SystemContractMethod.declare(
                    "tokenURI(uint256)", ReturnTypes.STRING)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.ERC721, Category.TOKEN_QUERY);

    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenUriTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(TOKEN_URI);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(TOKEN_URI);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        final var serialNo = asExactLongValueOrZero(TokenUriTranslator.TOKEN_URI
                .decodeCall(attempt.input().toArrayUnsafe())
                .get(0));
        return new TokenUriCall(
                attempt.systemContractGasCalculator(), attempt.enhancement(), attempt.redirectToken(), serialNo);
    }
}
