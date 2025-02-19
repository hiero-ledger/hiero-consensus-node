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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.addressToScheduleID;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class GetScheduledInfoTranslator extends AbstractCallTranslator<HssCallAttempt> {

    public static final SystemContractMethod GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO = SystemContractMethod.declare(
                    "getScheduledCreateFungibleTokenInfo(address)", ReturnTypes.RESPONSE_CODE_FUNGIBLE_TOKEN_INFO)
            .withCategories(Category.SCHEDULE)
            .withVariant(Variant.FT);
    public static final SystemContractMethod GET_SCHEDULED_CREATE_NON_FUNGIBLE_TOKEN_INFO =
            SystemContractMethod.declare(
                            "getScheduledCreateNonFungibleTokenInfo(address)",
                            ReturnTypes.RESPONSE_CODE_NON_FUNGIBLE_TOKEN_INFO)
                    .withCategories(Category.SCHEDULE)
                    .withVariant(Variant.NFT);

    // Tuple index for the schedule address
    private static final int SCHEDULE_ADDRESS = 0;

    @Inject
    public GetScheduledInfoTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger
        super(SystemContract.HSS, systemContractMethodRegistry, contractMetrics);

        registerMethods(GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO, GET_SCHEDULED_CREATE_NON_FUNGIBLE_TOKEN_INFO);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HssCallAttempt attempt) {
        requireNonNull(attempt);

        return attempt.isMethod(GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO, GET_SCHEDULED_CREATE_NON_FUNGIBLE_TOKEN_INFO);
    }

    @Override
    public Call callFrom(@NonNull final HssCallAttempt attempt) {
        return attempt.isSelector(GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO)
                ? getFungibleTokenCreateCall(attempt)
                : getNonFungibleTokenCreateCall(attempt);
    }

    private Call getFungibleTokenCreateCall(@NonNull final HssCallAttempt attempt) {
        final var call = GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO.decodeCall(attempt.inputBytes());
        final Address scheduleAddress = call.get(SCHEDULE_ADDRESS);
        return new GetScheduledFungibleTokenCreateCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                attempt.configuration(),
                addressToScheduleID(scheduleAddress));
    }

    private Call getNonFungibleTokenCreateCall(@NonNull final HssCallAttempt attempt) {
        final var call = GET_SCHEDULED_CREATE_NON_FUNGIBLE_TOKEN_INFO.decodeCall(attempt.inputBytes());
        final Address scheduleAddress = call.get(SCHEDULE_ADDRESS);
        return new GetScheduledNonFungibleTokenCreateCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                attempt.configuration(),
                addressToScheduleID(scheduleAddress));
    }
}
