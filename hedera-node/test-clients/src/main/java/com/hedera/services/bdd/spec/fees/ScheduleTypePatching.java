// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.fees;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenBurn;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;

import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FeeSchedule;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TransactionFeeSchedule;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper class to avoid problems when restarting from a saved state from the last release, whose
 * fee schedules are missing one or more price types that are required in the current release.
 */
public class ScheduleTypePatching {
    private static final EnumSet<SubType> ONLY_DEFAULT = EnumSet.of(DEFAULT);
    private static final Map<HederaFunctionality, EnumSet<SubType>> FUNCTIONS_WITH_REQUIRED_SUBTYPES;

    static {
        FUNCTIONS_WITH_REQUIRED_SUBTYPES = new EnumMap<>(HederaFunctionality.class);
        List.of(TokenMint, TokenBurn, TokenAccountWipe)
                .forEach(function -> FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(
                        function, EnumSet.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE)));
        FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(
                TokenCreate,
                EnumSet.of(
                        TOKEN_FUNGIBLE_COMMON, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                        TOKEN_NON_FUNGIBLE_UNIQUE, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES));
        FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(
                CryptoTransfer,
                EnumSet.of(
                        DEFAULT,
                        TOKEN_FUNGIBLE_COMMON,
                        TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                        TOKEN_NON_FUNGIBLE_UNIQUE,
                        TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES));
        FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(ScheduleCreate, EnumSet.of(DEFAULT, SCHEDULE_CREATE_CONTRACT_CALL));
    }

    public FeeSchedule withPatchedTypesIfNecessary(final FeeSchedule possiblyUntypedSchedule) {
        final var usableSchedule = FeeSchedule.newBuilder();
        for (final var tfs : possiblyUntypedSchedule.getTransactionFeeScheduleList()) {
            final var usableTfs = TransactionFeeSchedule.newBuilder();
            final var fn = tfs.getHederaFunctionality();
            usableTfs.mergeFrom(tfs);
            final Set<SubType> requiredTypes = FUNCTIONS_WITH_REQUIRED_SUBTYPES.getOrDefault(fn, ONLY_DEFAULT);
            ensurePatchedFeeScheduleHasRequiredTypes(tfs, usableTfs, requiredTypes);
            usableSchedule.addTransactionFeeSchedule(usableTfs);
        }
        return usableSchedule.build();
    }

    private void ensurePatchedFeeScheduleHasRequiredTypes(
            final TransactionFeeSchedule origTfs,
            final TransactionFeeSchedule.Builder patchedTfs,
            final Set<SubType> requiredTypes) {
        /* The deprecated prices are the final fallback; if even they are not set, the function will be free */
        final var oldDefaultPrices = origTfs.getFeeData();
        FeeData explicitDefaultPrices = null;

        /* First determine what types are already present; and what default prices to use, if any */
        final List<SubType> listedTypes = new ArrayList<>();
        for (final var typedPrices : origTfs.getFeesList()) {
            final var type = typedPrices.getSubType();
            listedTypes.add(type);
            if (type == DEFAULT) {
                explicitDefaultPrices = typedPrices;
            }
        }

        final Set<SubType> presentTypes =
                listedTypes.isEmpty() ? EnumSet.noneOf(SubType.class) : EnumSet.copyOf(listedTypes);
        for (final var type : requiredTypes) {
            if (!presentTypes.contains(type)) {
                if (explicitDefaultPrices != null) {
                    patchedTfs.addFees(
                            explicitDefaultPrices.toBuilder().setSubType(type).build());
                } else {
                    patchedTfs.addFees(
                            oldDefaultPrices.toBuilder().setSubType(type).build());
                }
            }
        }
    }
}
