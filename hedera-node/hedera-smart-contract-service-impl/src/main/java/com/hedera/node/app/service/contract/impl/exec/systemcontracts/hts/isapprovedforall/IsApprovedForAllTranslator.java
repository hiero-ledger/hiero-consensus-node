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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isapprovedforall;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.fromHeadlongAddress;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code isApprovedForAll} calls to the HTS system contract.
 */
@Singleton
public class IsApprovedForAllTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for isApprovedForAll(address,address,address) method. */
    public static final SystemContractMethod CLASSIC_IS_APPROVED_FOR_ALL = SystemContractMethod.declare(
                    "isApprovedForAll(address,address,address)", "(int64,bool)")
            .withCategories(Category.TOKEN_QUERY, Category.APPROVAL);
    /** Selector for isApprovedForAll(address,address) method. */
    public static final SystemContractMethod ERC_IS_APPROVED_FOR_ALL = SystemContractMethod.declare(
                    "isApprovedForAll(address,address)", "(bool)")
            .withVia(CallVia.PROXY)
            .withCategories(Category.TOKEN_QUERY, Category.APPROVAL);

    /**
     * Default constructor for injection.
     */
    @Inject
    public IsApprovedForAllTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(CLASSIC_IS_APPROVED_FOR_ALL, ERC_IS_APPROVED_FOR_ALL);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(CLASSIC_IS_APPROVED_FOR_ALL, ERC_IS_APPROVED_FOR_ALL);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IsApprovedForAllCall callFrom(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(ERC_IS_APPROVED_FOR_ALL)) {
            final var args = ERC_IS_APPROVED_FOR_ALL.decodeCall(attempt.input().toArrayUnsafe());
            return new IsApprovedForAllCall(
                    attempt.systemContractGasCalculator(),
                    attempt.enhancement(),
                    attempt.redirectToken(),
                    args.get(0),
                    args.get(1),
                    true);
        } else {
            final var args =
                    CLASSIC_IS_APPROVED_FOR_ALL.decodeCall(attempt.input().toArrayUnsafe());
            final var token = attempt.linkedToken(fromHeadlongAddress(args.get(0)));
            return new IsApprovedForAllCall(
                    attempt.systemContractGasCalculator(),
                    attempt.enhancement(),
                    token,
                    args.get(1),
                    args.get(2),
                    false);
        }
    }
}
