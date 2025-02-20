// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusSubmitMessage;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractAutoRenew;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoApproveAllowance;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoDeleteAllowance;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileAppend;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenBurn;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFeeScheduleUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFreezeAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenPause;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUnfreezeAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUnpause;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.UtilPrng;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static java.time.Month.SEPTEMBER;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SubType;
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

    @SuppressWarnings("unchecked")
    static final List<Pair<HederaFunctionality, List<SubType>>> SUPPORTED_FUNCTIONS = List.of(
            Pair.of(ContractAutoRenew, List.of(DEFAULT)),
            /* Crypto */
            Pair.of(
                    CryptoTransfer,
                    List.of(
                            DEFAULT,
                            TOKEN_FUNGIBLE_COMMON,
                            TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                            TOKEN_NON_FUNGIBLE_UNIQUE,
                            TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES)),
            Pair.of(CryptoCreate, List.of(DEFAULT)),
            Pair.of(CryptoUpdate, List.of(DEFAULT)),
            Pair.of(CryptoApproveAllowance, List.of(DEFAULT)),
            Pair.of(CryptoDeleteAllowance, List.of(DEFAULT)),
            /* File */
            Pair.of(FileAppend, List.of(DEFAULT)),
            /* Token */
            Pair.of(
                    TokenCreate,
                    List.of(
                            TOKEN_FUNGIBLE_COMMON,
                            TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                            TOKEN_NON_FUNGIBLE_UNIQUE,
                            TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES)),
            Pair.of(TokenMint, List.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE)),
            Pair.of(TokenBurn, List.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE)),
            Pair.of(TokenAccountWipe, List.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE)),
            Pair.of(TokenFeeScheduleUpdate, List.of(DEFAULT)),
            Pair.of(TokenFreezeAccount, List.of(DEFAULT)),
            Pair.of(TokenUnfreezeAccount, List.of(DEFAULT)),
            Pair.of(TokenPause, List.of(DEFAULT)),
            Pair.of(TokenUnpause, List.of(DEFAULT)),
            /* Consensus */
            Pair.of(ConsensusSubmitMessage, List.of(DEFAULT)),
            /* Schedule */
            Pair.of(ScheduleCreate, List.of(DEFAULT, SCHEDULE_CREATE_CONTRACT_CALL)),
            Pair.of(UtilPrng, List.of(DEFAULT)));
}
