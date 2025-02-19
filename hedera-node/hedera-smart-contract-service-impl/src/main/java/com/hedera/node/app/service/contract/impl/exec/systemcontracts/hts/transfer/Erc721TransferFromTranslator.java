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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.SpecialRewardReceivers.SPECIAL_REWARD_RECEIVERS;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates ERC-721 {@code transferFrom()} calls to the HTS system contract.
 */
@Singleton
public class Erc721TransferFromTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    /**
     * Selector for transferFrom(address,address,uint256) method.
     */
    public static final SystemContractMethod ERC_721_TRANSFER_FROM = SystemContractMethod.declare(
                    "transferFrom(address,address,uint256)")
            .withVia(CallVia.PROXY)
            .withCategories(Category.ERC721, Category.TRANSFER);

    /**
     * Default constructor for injection.
     */
    @Inject
    public Erc721TransferFromTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(ERC_721_TRANSFER_FROM);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        // Here, for ERC-721 == non-fungible tokens, the token type must exist
        if (!attempt.isTokenRedirect()) return Optional.empty();
        if (attempt.redirectTokenType() != NON_FUNGIBLE_UNIQUE) return Optional.empty();
        return attempt.isMethod(ERC_721_TRANSFER_FROM);
    }

    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        final var call = Erc721TransferFromTranslator.ERC_721_TRANSFER_FROM.decodeCall(
                attempt.input().toArrayUnsafe());
        return new Erc721TransferFromCall(
                ((BigInteger) call.get(2)).longValueExact(),
                call.get(0),
                call.get(1),
                requireNonNull(attempt.redirectToken()).tokenIdOrThrow(),
                attempt.defaultVerificationStrategy(),
                attempt.enhancement(),
                attempt.systemContractGasCalculator(),
                attempt.senderId(),
                attempt.addressIdConverter(),
                SPECIAL_REWARD_RECEIVERS);
    }
}
