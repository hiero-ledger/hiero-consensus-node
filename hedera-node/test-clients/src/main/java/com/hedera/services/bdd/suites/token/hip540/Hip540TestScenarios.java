// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.hip540;

import static com.hedera.services.bdd.suites.token.hip540.AuthorizingSignature.EXTANT_ADMIN;
import static com.hedera.services.bdd.suites.token.hip540.AuthorizingSignature.EXTANT_NON_ADMIN;
import static com.hedera.services.bdd.suites.token.hip540.AuthorizingSignature.NEW_NON_ADMIN;
import static com.hedera.services.bdd.suites.token.hip540.KeyState.MISSING;
import static com.hedera.services.bdd.suites.token.hip540.KeyState.USABLE;
import static com.hedera.services.bdd.suites.token.hip540.ManagementAction.REPLACE;
import static com.hedera.services.bdd.suites.token.hip540.ManagementAction.REPLACE_WITH_INVALID;
import static com.hedera.services.bdd.suites.token.hip540.ManagementAction.ZERO_OUT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.TokenKeyValidation.FULL_VALIDATION;
import static com.hederahashgraph.api.proto.java.TokenKeyValidation.NO_VALIDATION;

import com.hedera.services.bdd.spec.utilops.mod.ExpectedResponse;
import com.hederahashgraph.api.proto.java.TokenKeyValidation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * A collection of all HIP-540 test scenarios.
 */
public class Hip540TestScenarios {
    private Hip540TestScenarios() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /** The list of all HIP-540 test scenarios. */
    public static final List<Hip540TestScenario> ALL_HIP_540_SCENARIOS = new ArrayList<>();

    static {
        Arrays.stream(NonAdminTokenKey.values())
                .flatMap(Hip540TestScenarios::allScenariosFor)
                .forEach(ALL_HIP_540_SCENARIOS::add);
    }

    private static Stream<Hip540TestScenario> allScenariosFor(@NonNull final NonAdminTokenKey targetKey) {
        final List<Hip540TestScenario> scenarios = new ArrayList<>();
        addPositiveNonAdminScenarios(targetKey, scenarios);
        addNegativeNonAdminScenarios(targetKey, scenarios);
        addPositiveAdminOnlyScenarios(targetKey, scenarios);
        addNegativeAdminOnlyScenarios(targetKey, scenarios);
        return scenarios.stream();
    }

    /**
     * Without an admin key, we are able to:
     * <ol>
     *     <li>Replace the target with a usable key with its signature <b>and</b>
     *     its replacement's signature given {@link TokenKeyValidation#FULL_VALIDATION}.</li>
     *     <li>Replace the target with a usable key its signature <b>without</b>
     *     its replacement's signature using {@link TokenKeyValidation#NO_VALIDATION}.</li>
     *     <li>Zero out the target with its signature using {@link TokenKeyValidation#NO_VALIDATION}.</li>
     * </ol>
     *
     * @param targetKey the target key to test
     * @param scenarios the list of scenarios to add to
     */
    private static void addPositiveNonAdminScenarios(
            @NonNull final NonAdminTokenKey targetKey, @NonNull final List<Hip540TestScenario> scenarios) {
        scenarios.addAll(List.of(
                new Hip540TestScenario(
                        targetKey,
                        MISSING,
                        USABLE,
                        REPLACE,
                        FULL_VALIDATION,
                        EnumSet.of(EXTANT_NON_ADMIN, NEW_NON_ADMIN),
                        ExpectedResponse.atConsensus(SUCCESS),
                        "noAdminKeyReplaceAUsableRoleKeyWithUsableKeyFullValidationSucceeds"),
                new Hip540TestScenario(
                        targetKey,
                        MISSING,
                        USABLE,
                        REPLACE,
                        NO_VALIDATION,
                        EnumSet.of(EXTANT_NON_ADMIN),
                        ExpectedResponse.atConsensus(SUCCESS),
                        "noAdminKeyReplaceAUsableRoleKeyWithUsableKeyNoValidationSucceeds"),
                new Hip540TestScenario(
                        targetKey,
                        MISSING,
                        USABLE,
                        ZERO_OUT,
                        NO_VALIDATION,
                        EnumSet.of(EXTANT_NON_ADMIN),
                        ExpectedResponse.atConsensus(SUCCESS),
                        "noAdminKeyZeroOutAUsableRoleKeyNoValidationSucceeds")));
    }

    /**
     * Without an admin key, we are <b>not</b> able to:
     * <ol>
     *     <Li>Remove the target even with its signature.</Li>
     *     <Li>Remove a target key that is already missing.</Li>
     *     <Li>Replace a target key without its signature.</Li>
     *     <Li>Zero out a target key when using {@link TokenKeyValidation#FULL_VALIDATION},
     *     even when signing with the existing key.</Li>
     *     <Li>Replace a target key with a structurally invalid key,
     *     even when using  {@link TokenKeyValidation#NO_VALIDATION},.</li>
     * </ol>
     *
     * @param targetKey the target key to test
     * @param scenarios the list of scenarios to add to
     */
    private static void addNegativeNonAdminScenarios(
            @NonNull final NonAdminTokenKey targetKey, @NonNull final List<Hip540TestScenario> scenarios) {
        scenarios.addAll(List.of(
                new Hip540TestScenario(
                        targetKey,
                        MISSING,
                        USABLE,
                        ManagementAction.REMOVE,
                        null,
                        EnumSet.of(EXTANT_NON_ADMIN),
                        ExpectedResponse.atConsensus(TOKEN_IS_IMMUTABLE),
                        "noAdminKeyRemoveAUsableRoleKeyFails"),
                new Hip540TestScenario(
                        targetKey,
                        MISSING,
                        USABLE,
                        REPLACE,
                        FULL_VALIDATION,
                        EnumSet.of(NEW_NON_ADMIN),
                        ExpectedResponse.atConsensus(INVALID_SIGNATURE),
                        "noAdminKeyReplaceAUsableRoleKeyFullValidationWithoutNewKeySignatureFails"),
                new Hip540TestScenario(
                        targetKey,
                        MISSING,
                        USABLE,
                        ZERO_OUT,
                        FULL_VALIDATION,
                        EnumSet.of(EXTANT_NON_ADMIN),
                        ExpectedResponse.atConsensus(INVALID_SIGNATURE),
                        "noAdminKeyZeroOutAUsableRoleKeyFullValidationFails"),
                new Hip540TestScenario(
                        targetKey,
                        MISSING,
                        USABLE,
                        REPLACE_WITH_INVALID,
                        FULL_VALIDATION,
                        EnumSet.of(EXTANT_NON_ADMIN),
                        ExpectedResponse.atIngest(targetKey.invalidKeyStatus()),
                        "noAdminKeyReplaceAUsableRoleKeyWithInvalidKeyFullValidationFails")));
    }

    /**
     * With an admin key, we are able to:
     * <ol>
     *     <li>Remove the target key without its signature.</li>
     *     <li>Replace the target with a usable key without its signature <b>or</b>
     *     its replacement's signature, even using {@link TokenKeyValidation#FULL_VALIDATION}.</li>
     *     <li>Zero out the target key without its signature.
     * </ol>
     *
     * @param targetKey the target key to test
     * @param scenarios the list of scenarios to add to
     */
    private static void addPositiveAdminOnlyScenarios(
            @NonNull final NonAdminTokenKey targetKey, @NonNull final List<Hip540TestScenario> scenarios) {
        scenarios.addAll(List.of(
                new Hip540TestScenario(
                        targetKey,
                        USABLE,
                        USABLE,
                        ManagementAction.REMOVE,
                        null,
                        EnumSet.of(EXTANT_ADMIN),
                        ExpectedResponse.atConsensus(SUCCESS),
                        "withAdminKeyRemoveAUsableRoleKeySucceeds"),
                new Hip540TestScenario(
                        targetKey,
                        USABLE,
                        USABLE,
                        REPLACE,
                        FULL_VALIDATION,
                        EnumSet.of(EXTANT_ADMIN),
                        ExpectedResponse.atConsensus(SUCCESS),
                        "withAdminKeyReplaceAUsableRoleKeyFullValidationSucceeds"),
                new Hip540TestScenario(
                        targetKey,
                        USABLE,
                        USABLE,
                        ZERO_OUT,
                        FULL_VALIDATION,
                        EnumSet.of(EXTANT_ADMIN),
                        ExpectedResponse.atConsensus(SUCCESS),
                        "withAdminKeyZeroOutAUsableRoleKeyFullValidationSucceeds")));
    }

    /**
     * Even with an admin key, we are still <b>not</b> able to:
     * <ol>
     *     <li>Add any key that does not already exist.</li>
     *     <li>Remove a key that does not already exist.</li>
     * </ol>
     *
     * @param targetKey the target key to test
     * @param scenarios the list of scenarios to add to
     */
    private static void addNegativeAdminOnlyScenarios(
            @NonNull final NonAdminTokenKey targetKey, @NonNull final List<Hip540TestScenario> scenarios) {
        scenarios.addAll(List.of(
                new Hip540TestScenario(
                        targetKey,
                        USABLE,
                        MISSING,
                        ManagementAction.ADD,
                        NO_VALIDATION,
                        EnumSet.of(EXTANT_ADMIN),
                        ExpectedResponse.atConsensus(targetKey.tokenHasNoKeyStatus()),
                        "withAdminKeyAndMissingRoleKeyAddAUsableRoleKeyFails"),
                new Hip540TestScenario(
                        targetKey,
                        USABLE,
                        MISSING,
                        ManagementAction.REMOVE,
                        null,
                        EnumSet.of(EXTANT_ADMIN),
                        ExpectedResponse.atConsensus(targetKey.tokenHasNoKeyStatus()),
                        "withAdminKeyAndMissingRoleKeyRemoveRoleKeyFails")));
    }
}
