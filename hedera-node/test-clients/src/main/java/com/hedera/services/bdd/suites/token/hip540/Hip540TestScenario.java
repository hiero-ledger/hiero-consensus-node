// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.hip540;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomAlphaNumeric;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.token.hip540.ManagementAction.REMOVE;
import static com.hedera.services.bdd.suites.token.hip540.ManagementAction.REPLACE;
import static com.hederahashgraph.api.proto.java.TokenKeyValidation.NO_VALIDATION;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.queries.token.HapiGetTokenInfo;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenUpdate;
import com.hedera.services.bdd.spec.utilops.mod.ExpectedResponse;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.TokenKeyValidation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Characterizes a test scenario for HIP-540.
 *
 * @param targetKey which key is being managed
 * @param adminKeyState the state of the admin key
 * @param targetKeyState the state of the target key
 * @param action the management action to take
 * @param keyValidation whether to apply key validation
 * @param authorizingSignatures which signatures to use
 * @param expectedResponse the expected outcome
 */
public record Hip540TestScenario(
        @NonNull NonAdminTokenKey targetKey,
        @NonNull KeyState adminKeyState,
        @NonNull KeyState targetKeyState,
        @NonNull ManagementAction action,
        @Nullable TokenKeyValidation keyValidation,
        @NonNull Set<AuthorizingSignature> authorizingSignatures,
        @NonNull ExpectedResponse expectedResponse,
        @NonNull String registryNameSalt,
        @NonNull String testName) {

    public Hip540TestScenario(
            @NonNull NonAdminTokenKey targetKey,
            @NonNull KeyState adminKeyState,
            @NonNull KeyState targetKeyState,
            @NonNull ManagementAction action,
            @Nullable TokenKeyValidation keyValidation,
            @NonNull Set<AuthorizingSignature> authorizingSignatures,
            @NonNull ExpectedResponse expectedResponse,
            @NonNull String testName) {
        this(
                targetKey,
                adminKeyState,
                targetKeyState,
                action,
                keyValidation,
                authorizingSignatures,
                expectedResponse,
                randomAlphaNumeric(5),
                testName);
    }

    public Hip540TestScenario {
        requireNonNull(targetKey);
        requireNonNull(adminKeyState);
        requireNonNull(targetKeyState);
        requireNonNull(action);
        requireNonNull(authorizingSignatures);
        requireNonNull(expectedResponse);
        requireNonNull(registryNameSalt);
        requireNonNull(testName);
    }

    private static final String ADMIN_KEY = "adminKey";
    private static final String ROLE_KEY = "roleKey";
    private static final String NEW_ROLE_KEY = "newRoleKey";
    private static final String TOKEN_UNDER_TEST = "token";
    // This key is structurally valid, but effectively unusable because there is no
    // known way to invert the SHA-512 hash of its associated curve point
    private static final Key ZEROED_OUT_KEY = Key.newBuilder()
            .setEd25519(ByteString.fromHex("0000000000000000000000000000000000000000000000000000000000000000"))
            .build();
    public static final Key IMMUTABILITY_SENTINEL_KEY =
            Key.newBuilder().setKeyList(KeyList.getDefaultInstance()).build();
    // This key is truly invalid, as all Ed25519 public keys must be 32 bytes long
    private static final Key STRUCTURALLY_INVALID_KEY =
            Key.newBuilder().setEd25519(ByteString.fromHex("ff")).build();

    private String adminKey() {
        return ADMIN_KEY + registryNameSalt;
    }

    private String roleKey() {
        return ROLE_KEY + registryNameSalt;
    }

    private String newRoleKey() {
        return NEW_ROLE_KEY + registryNameSalt;
    }

    private String tokenUnderTest() {
        return TOKEN_UNDER_TEST + registryNameSalt;
    }

    public HapiSpecOperation asOperation() {
        final List<HapiSpecOperation> ops = new ArrayList<>();
        ops.add(logIt("HIP-540 test scenario - " + this));
        if (adminKeyState == KeyState.USABLE) {
            ops.add(newKeyNamed(adminKey()));
        }
        if (targetKeyState == KeyState.USABLE) {
            ops.add(newKeyNamed(roleKey()));
        } else if (targetKeyState == KeyState.ZEROED_OUT) {
            ops.add(withOpContext((spec, opLog) -> spec.registry().saveKey(roleKey(), ZEROED_OUT_KEY)));
        }
        switch (action) {
            case ADD, REPLACE -> ops.add(newKeyNamed(newRoleKey()));
            case REMOVE -> ops.add(
                    withOpContext((spec, opLog) -> spec.registry().saveKey(newRoleKey(), IMMUTABILITY_SENTINEL_KEY)));
            case ZERO_OUT -> ops.add(
                    withOpContext((spec, opLog) -> spec.registry().saveKey(newRoleKey(), ZEROED_OUT_KEY)));
            case REPLACE_WITH_INVALID -> ops.add(
                    withOpContext((spec, opLog) -> spec.registry().saveKey(newRoleKey(), STRUCTURALLY_INVALID_KEY)));
        }
        ops.add(creation());
        ops.add(update());
        if (expectedResponse.isSuccess()) {
            ops.add(confirmation());
        }
        return blockingOrder(ops.toArray(HapiSpecOperation[]::new));
    }

    private HapiGetTokenInfo confirmation() {
        final var op = getTokenInfo(tokenUnderTest());
        if (expectedResponse.isSuccess()) {
            if (action == REMOVE) {
                switch (targetKey) {
                    case WIPE_KEY -> op.hasEmptyWipeKey();
                    case KYC_KEY -> op.hasEmptyKycKey();
                    case SUPPLY_KEY -> op.hasEmptySupplyKey();
                    case FREEZE_KEY -> op.hasEmptyFreezeKey();
                    case FEE_SCHEDULE_KEY -> op.hasEmptyFeeScheduleKey();
                    case PAUSE_KEY -> op.hasEmptyPauseKey();
                    case METADATA_KEY -> op.hasEmptyMetadataKey();
                }
            } else {
                switch (targetKey) {
                    case WIPE_KEY -> op.hasWipeKey(newRoleKey()).searchKeysGlobally();
                    case KYC_KEY -> op.hasKycKey(newRoleKey()).searchKeysGlobally();
                    case SUPPLY_KEY -> op.hasSupplyKey(newRoleKey()).searchKeysGlobally();
                    case FREEZE_KEY -> op.hasFreezeKey(newRoleKey()).searchKeysGlobally();
                    case FEE_SCHEDULE_KEY -> op.hasFeeScheduleKey(newRoleKey()).searchKeysGlobally();
                    case PAUSE_KEY -> op.hasPauseKey(newRoleKey()).searchKeysGlobally();
                    case METADATA_KEY -> op.hasMetadataKey(newRoleKey()).searchKeysGlobally();
                }
            }
        }
        return op;
    }

    private HapiTokenUpdate update() {
        final var op = tokenUpdate(tokenUnderTest());
        addReplacementTo(op);
        addSignaturesTo(op);
        if (keyValidation == NO_VALIDATION) {
            op.applyNoValidationToKeys();
        }
        return op;
    }

    private void addReplacementTo(@NonNull final HapiTokenUpdate update) {
        switch (targetKey) {
            case WIPE_KEY -> update.wipeKey(newRoleKey());
            case KYC_KEY -> update.kycKey(newRoleKey());
            case SUPPLY_KEY -> update.supplyKey(newRoleKey());
            case FREEZE_KEY -> update.freezeKey(newRoleKey());
            case FEE_SCHEDULE_KEY -> update.feeScheduleKey(newRoleKey());
            case PAUSE_KEY -> update.pauseKey(newRoleKey());
            case METADATA_KEY -> update.metadataKey(newRoleKey());
        }
    }

    private void addSignaturesTo(@NonNull final HapiTokenUpdate update) {
        final List<String> signatures = new ArrayList<>();
        signatures.add(DEFAULT_PAYER);
        authorizingSignatures.forEach(sig -> {
            switch (sig) {
                case EXTANT_ADMIN -> signatures.add(adminKey());
                case EXTANT_NON_ADMIN -> signatures.add(roleKey());
                case NEW_NON_ADMIN -> signatures.add(newRoleKey());
            }
        });
        update.signedBy(signatures.toArray(String[]::new));
        expectedResponse.customize(update);
    }

    private HapiTokenCreate creation() {
        final var op = tokenCreate(tokenUnderTest());
        if (adminKeyState != KeyState.MISSING) {
            op.adminKey(adminKey());
        }
        if (targetKeyState != KeyState.MISSING) {
            switch (targetKey) {
                case WIPE_KEY -> op.wipeKey(roleKey());
                case KYC_KEY -> op.kycKey(roleKey());
                case SUPPLY_KEY -> op.supplyKey(roleKey());
                case FREEZE_KEY -> op.freezeKey(roleKey());
                case FEE_SCHEDULE_KEY -> op.feeScheduleKey(roleKey());
                case PAUSE_KEY -> op.pauseKey(roleKey());
                case METADATA_KEY -> op.metadataKey(roleKey());
            }
        }
        return op;
    }

    @Override
    public String toString() {
        return "Hip540TestScenario {" + "\n testName="
                + testName + ",\n  targetKey="
                + targetKey + ",\n  adminKeyState="
                + adminKeyState + ",\n  targetKeyState="
                + targetKeyState + ",\n  action="
                + action + ",\n  keyValidation="
                + keyValidation + ",\n  authorizingSignatures="
                + authorizingSignatures + ",\n  expectedResponse="
                + expectedResponse + '}';
    }
}
