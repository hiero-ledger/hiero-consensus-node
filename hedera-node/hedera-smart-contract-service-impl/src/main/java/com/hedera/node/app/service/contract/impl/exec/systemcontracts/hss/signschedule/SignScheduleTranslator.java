// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.signschedule;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static com.hedera.node.app.service.contract.impl.utils.SignatureMapUtils.preprocessEcdsaSignatures;
import static com.hedera.node.app.service.contract.impl.utils.SystemContractUtils.messageFromScheduleId;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.pbj.runtime.io.buffer.Bytes.wrap;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.scheduled.ScheduleSignTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.DispatchForResponseCodeHssCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType;
import com.hedera.node.app.spi.signatures.SignatureVerifier.SimpleKeyStatus;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code signSchedule()} calls to the HSS system contract.
 */
@Singleton
public class SignScheduleTranslator extends AbstractCallTranslator<HssCallAttempt> {

    public static final SystemContractMethod SIGN_SCHEDULE = SystemContractMethod.declare(
                    "signSchedule(address,bytes)", ReturnTypes.INT_64)
            .withCategories(Category.SCHEDULE);
    private static final int SCHEDULE_ID_INDEX = 0;
    private static final int SIGNATURE_MAP_INDEX = 1;

    public static final SystemContractMethod SIGN_SCHEDULE_PROXY = SystemContractMethod.declare(
                    "signSchedule()", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withCategories(Category.SCHEDULE);

    public static final SystemContractMethod AUTHORIZE_SCHEDULE = SystemContractMethod.declare(
                    "authorizeSchedule(address)", ReturnTypes.INT_64)
            .withCategories(Category.SCHEDULE);

    @Inject
    public SignScheduleTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HSS, systemContractMethodRegistry, contractMetrics);

        registerMethods(SIGN_SCHEDULE, AUTHORIZE_SCHEDULE, SIGN_SCHEDULE_PROXY);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HssCallAttempt attempt) {
        requireNonNull(attempt);
        final var signScheduleEnabled =
                attempt.configuration().getConfigData(ContractsConfig.class).systemContractSignScheduleEnabled();
        final var signScheduleFromContractEnabled = attempt.configuration()
                .getConfigData(ContractsConfig.class)
                .systemContractSignScheduleFromContractEnabled();
        final var authorizeScheduleEnabled =
                attempt.configuration().getConfigData(ContractsConfig.class).systemContractAuthorizeScheduleEnabled();

        if (attempt.isSelectorIfConfigEnabled(signScheduleEnabled, SIGN_SCHEDULE_PROXY))
            return Optional.of(SIGN_SCHEDULE_PROXY);
        if (attempt.isSelectorIfConfigEnabled(authorizeScheduleEnabled, AUTHORIZE_SCHEDULE))
            return Optional.of(AUTHORIZE_SCHEDULE);
        if (attempt.isSelectorIfConfigEnabled(signScheduleFromContractEnabled, SIGN_SCHEDULE))
            return Optional.of(SIGN_SCHEDULE);
        return Optional.empty();
    }

    @Override
    public Call callFrom(@NonNull HssCallAttempt attempt) {
        final var body = bodyFor(scheduleIdFor(attempt));
        return new DispatchForResponseCodeHssCall(
                attempt, body, SignScheduleTranslator::gasRequirement, keySetFor(attempt));
    }

    /**
     * Calculates the gas requirement for a {@code signSchedule()} call.
     *
     * @param body the transaction body
     * @param systemContractGasCalculator the gas calculator
     * @param enhancement the enhancement
     * @param payerId the payer ID
     * @return the gas requirement
     */
    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.SCHEDULE_SIGN, payerId);
    }

    /**
     * Creates a transaction body for a {@code signSchedule()} or call.
     *
     * @param scheduleID the schedule ID
     * @return the transaction body
     */
    @VisibleForTesting
    public TransactionBody bodyFor(@NonNull ScheduleID scheduleID) {
        requireNonNull(scheduleID);
        return TransactionBody.newBuilder()
                .scheduleSign(ScheduleSignTransactionBody.newBuilder()
                        .scheduleID(scheduleID)
                        .build())
                .build();
    }

    /**
     * Extracts the schedule ID from a {@code authorizeSchedule()} call or return the redirect schedule ID
     * if the call via the proxy contract
     *
     * @param attempt the call attempt
     * @return the schedule ID
     */
    @VisibleForTesting
    public ScheduleID scheduleIdFor(@NonNull HssCallAttempt attempt) {
        requireNonNull(attempt);
        if (attempt.isSelector(SIGN_SCHEDULE_PROXY)) {
            return getScheduleIDForSignScheduleProxy(attempt);
        } else if (attempt.isSelector(SIGN_SCHEDULE)) {
            return getScheduleIDForSignSchedule(attempt);
        } else if (attempt.isSelector(AUTHORIZE_SCHEDULE)) {
            return getScheduleIDForAuthorizeSchedule(attempt);
        }
        throw new IllegalStateException("Unexpected function selector");
    }

    private static ScheduleID getScheduleIDForSignSchedule(@NonNull HssCallAttempt attempt) {
        final var call = SIGN_SCHEDULE.decodeCall(attempt.inputBytes());
        return getScheduleIDFromCall(attempt, call);
    }

    private static ScheduleID getScheduleIDForAuthorizeSchedule(@NonNull HssCallAttempt attempt) {
        final var call = AUTHORIZE_SCHEDULE.decodeCall(attempt.inputBytes());
        return getScheduleIDFromCall(attempt, call);
    }

    private static @Nullable ScheduleID getScheduleIDFromCall(@NonNull HssCallAttempt attempt, Tuple call) {
        final Address scheduleAddress = call.get(SCHEDULE_ID_INDEX);
        final var number = numberOfLongZero(explicitFromHeadlong(scheduleAddress));
        final var schedule = attempt.nativeOperations()
                .getSchedule(attempt.nativeOperations().entityIdFactory().newScheduleId(number));
        validateTrue(schedule != null, INVALID_SCHEDULE_ID);
        return schedule.scheduleId();
    }

    private static ScheduleID getScheduleIDForSignScheduleProxy(@NonNull HssCallAttempt attempt) {
        final var scheduleID = attempt.redirectScheduleId();
        validateTrue(scheduleID != null, INVALID_SCHEDULE_ID);
        return attempt.redirectScheduleId();
    }

    /**
     * Extracts the key set for a {@code signSchedule(address, bytes)} call.  Otherwise, delegates to the call attempt.
     *
     * @param attempt the call attempt
     * @return the key set
     */
    private Set<Key> keySetFor(@NonNull HssCallAttempt attempt) {
        requireNonNull(attempt);

        // Check for the signSchedule(address, bytes) call.  This form of key set extraction will never be used
        // for the HIP 756 calls and thus we treat it separately.
        if (attempt.isSelector(SIGN_SCHEDULE)) {
            return getKeyForSignSchedule(attempt);
        }
        return attempt.keySetFor();
    }

    @VisibleForTesting
    @NonNull
    public static Set<Key> getKeyForSignSchedule(@NonNull HssCallAttempt attempt) {
        requireNonNull(attempt);
        final Set<Key> keys = new HashSet<>();
        final var call = SIGN_SCHEDULE.decodeCall(attempt.inputBytes());
        final var scheduleId = requireNonNull(getScheduleIDFromCall(attempt, call));

        final var message = messageFromScheduleId(scheduleId);

        final var signatureBlob = (byte[]) call.get(SIGNATURE_MAP_INDEX);
        try {
            final var chainId =
                    attempt.configuration().getConfigData(ContractsConfig.class).chainId();
            final var sigMap = preprocessEcdsaSignatures(
                    requireNonNull(SignatureMap.PROTOBUF.parse(wrap(signatureBlob))), chainId);
            for (var sigPair : sigMap.sigPair()) {
                // For ED25519 and ECDSA keys, verify the key and add it to the key set if verified
                if (sigPair.hasEd25519()) {
                    var key = Key.newBuilder().ed25519(sigPair.pubKeyPrefix()).build();
                    if (isVerifiedSignature(attempt, key, message, sigMap)) {
                        keys.add(key);
                    }
                }
                if (sigPair.hasEcdsaSecp256k1()) {
                    var key = Key.newBuilder()
                            .ecdsaSecp256k1(sigPair.pubKeyPrefix())
                            .build();
                    if (isVerifiedSignature(attempt, key, message, sigMap)) {
                        keys.add(key);
                    }
                }
            }
        } catch (@NonNull final ParseException | NullPointerException | IllegalArgumentException ex) {
            throw new HandleException(INVALID_TRANSACTION_BODY);
        }
        return keys;
    }

    /**
     * Verifies the signature for a given key.
     * @param attempt the call attempt
     * @param key the key to verify
     * @param message the message to verify - concatenation of realm, shard, and schedule numbers
     * @param sigMap the signature map used for verification
     * @return true if the signature is verified, false otherwise
     */
    private static boolean isVerifiedSignature(
            @NonNull HssCallAttempt attempt, Key key, Bytes message, SignatureMap sigMap) {
        return attempt.signatureVerifier()
                .verifySignature(key, message, MessageType.RAW, sigMap, ky -> SimpleKeyStatus.ONLY_IF_CRYPTO_SIG_VALID);
    }
}
