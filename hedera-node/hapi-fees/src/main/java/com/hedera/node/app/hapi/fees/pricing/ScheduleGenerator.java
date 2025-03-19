// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_AUTO_RENEW;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ACCOUNT_WIPE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_BURN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_PAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNPAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.UTIL_PRNG;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.hapi.node.base.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static java.time.Month.SEPTEMBER;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;

public class ScheduleGenerator {
    private static final String FEE_SCHEDULE_FEES_KEY = "fees";
    private static final String FEE_SCHEDULE_TYPE_KEY = "subType";
    private static final String FEE_SCHEDULE_ENTRY_KEY = "transactionFeeSchedule";
    private static final String FEE_SCHEDULE_FUNCTION_KEY = "hederaFunctionality";

    private static final Instant CURRENT_SCHEDULE_EXPIRY =
            LocalDateTime.of(2021, SEPTEMBER, 2, 0, 0).plusMonths(1).toInstant(ZoneOffset.UTC);
    private static final Instant NEXT_SCHEDULE_EXPIRY =
            LocalDateTime.of(2021, SEPTEMBER, 2, 0, 0).plusMonths(2).toInstant(ZoneOffset.UTC);

    private static final FeeSchedules feeSchedules = new FeeSchedules();

    String feeSchedulesFor(final List<Pair<HederaFunctionality, List<SubType>>> data) throws IOException {
        final List<Map<String, Object>> currentFeeSchedules = new ArrayList<>();
        final List<Map<String, Object>> nextFeeSchedules = new ArrayList<>();

        for (final var datum : data) {
            final var function = datum.getKey();
            final var subTypes = datum.getValue();
            final var tfs = pricesAsTfs(function, subTypes);
            currentFeeSchedules.add(tfs);
            nextFeeSchedules.add(tfs);
        }
        currentFeeSchedules.add(Map.of("expiryTime", CURRENT_SCHEDULE_EXPIRY.getEpochSecond()));
        nextFeeSchedules.add(Map.of("expiryTime", NEXT_SCHEDULE_EXPIRY.getEpochSecond()));

        final List<Map<String, Object>> everything =
                List.of(Map.of("currentFeeSchedule", currentFeeSchedules), Map.of("nextFeeSchedule", nextFeeSchedules));
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(everything);
    }

    private Map<String, Object> pricesAsTfs(final HederaFunctionality function, final List<SubType> subTypes)
            throws IOException {
        final Map<String, Object> transactionFeeSchedule = new HashMap<>();

        final Map<String, Object> details = new LinkedHashMap<>();
        details.put(FEE_SCHEDULE_FUNCTION_KEY, function.toString());

        final List<Map<String, Object>> allTypedPrices = new ArrayList<>();
        for (final var subType : subTypes) {
            final Map<String, Object> typedPrices = new LinkedHashMap<>();
            typedPrices.put(FEE_SCHEDULE_TYPE_KEY, subType.toString());

            final Map<ResourceProvider, Map<UsableResource, Long>> prices =
                    feeSchedules.canonicalPricesFor(function, subType);
            for (final var provider : ResourceProvider.class.getEnumConstants()) {
                final Map<String, Long> constrainedPrices = new LinkedHashMap<>();
                final var providerPrices = prices.get(provider);
                for (final var resource : UsableResource.class.getEnumConstants()) {
                    final var price = providerPrices.get(resource);
                    constrainedPrices.put(resource.toString().toLowerCase(), price);
                }
                constrainedPrices.put("min", 0L);
                constrainedPrices.put("max", 1000000000000000L);
                typedPrices.put(provider.jsonKey(), constrainedPrices);
            }

            allTypedPrices.add(typedPrices);
        }
        details.put(FEE_SCHEDULE_FEES_KEY, allTypedPrices);
        transactionFeeSchedule.put(FEE_SCHEDULE_ENTRY_KEY, details);
        return transactionFeeSchedule;
    }

    static final List<Pair<HederaFunctionality, List<SubType>>> SUPPORTED_FUNCTIONS = List.of(
            Pair.of(CONTRACT_AUTO_RENEW, List.of(DEFAULT)),
            /* Crypto */
            Pair.of(
                    CRYPTO_TRANSFER,
                    List.of(
                            DEFAULT,
                            TOKEN_FUNGIBLE_COMMON,
                            TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                            TOKEN_NON_FUNGIBLE_UNIQUE,
                            TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES)),
            Pair.of(CRYPTO_CREATE, List.of(DEFAULT)),
            Pair.of(CRYPTO_UPDATE, List.of(DEFAULT)),
            Pair.of(CRYPTO_APPROVE_ALLOWANCE, List.of(DEFAULT)),
            Pair.of(CRYPTO_DELETE_ALLOWANCE, List.of(DEFAULT)),
            /* File */
            Pair.of(FILE_APPEND, List.of(DEFAULT)),
            /* Token */
            Pair.of(
                    TOKEN_CREATE,
                    List.of(
                            TOKEN_FUNGIBLE_COMMON,
                            TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                            TOKEN_NON_FUNGIBLE_UNIQUE,
                            TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES)),
            Pair.of(TOKEN_MINT, List.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE)),
            Pair.of(TOKEN_BURN, List.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE)),
            Pair.of(TOKEN_ACCOUNT_WIPE, List.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE)),
            Pair.of(TOKEN_FEE_SCHEDULE_UPDATE, List.of(DEFAULT)),
            Pair.of(TOKEN_FREEZE_ACCOUNT, List.of(DEFAULT)),
            Pair.of(TOKEN_UNFREEZE_ACCOUNT, List.of(DEFAULT)),
            Pair.of(TOKEN_PAUSE, List.of(DEFAULT)),
            Pair.of(TOKEN_UNPAUSE, List.of(DEFAULT)),
            /* Consensus */
            Pair.of(CONSENSUS_SUBMIT_MESSAGE, List.of(DEFAULT)),
            /* Schedule */
            Pair.of(SCHEDULE_CREATE, List.of(DEFAULT, SCHEDULE_CREATE_CONTRACT_CALL)),
            Pair.of(UTIL_PRNG, List.of(DEFAULT)));
}
