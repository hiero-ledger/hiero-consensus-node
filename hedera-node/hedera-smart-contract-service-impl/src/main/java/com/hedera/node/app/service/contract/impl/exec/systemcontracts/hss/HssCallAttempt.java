// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.maybeMissingNumberOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

/**
 * Manages the call attempted by a {@link Bytes} payload received by the {@link HssSystemContract}.
 * Translates a valid attempt into an appropriate {@link AbstractCall} subclass, giving the {@link Call}
 * everything it will need to execute.
 */
public class HssCallAttempt extends AbstractCallAttempt<HssCallAttempt> {
    /** Selector for redirectForScheduleTxn(address,bytes) method. */
    public static final Function REDIRECT_FOR_SCHEDULE_TXN = new Function("redirectForScheduleTxn(address,bytes)");

    @Nullable
    private final Schedule redirectScheduleTxn;

    @NonNull
    private final SignatureVerifier signatureVerifier;

    // too many parameters
    @SuppressWarnings("java:S107")
    public HssCallAttempt(
            @NonNull final ContractID contractID,
            @NonNull final Bytes input,
            @NonNull final Address senderAddress,
            final boolean onlyDelegatableContractKeysActive,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final Configuration configuration,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final VerificationStrategies verificationStrategies,
            @NonNull final SignatureVerifier signatureVerifier,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final List<CallTranslator<HssCallAttempt>> callTranslators,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            final boolean isStaticCall) {
        super(
                contractID,
                input,
                senderAddress,
                senderAddress,
                onlyDelegatableContractKeysActive,
                enhancement,
                configuration,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                callTranslators,
                isStaticCall,
                systemContractMethodRegistry,
                REDIRECT_FOR_SCHEDULE_TXN);
        if (isRedirect()) {
            this.redirectScheduleTxn = linkedSchedule(requireNonNull(redirectAddress));
        } else {
            this.redirectScheduleTxn = null;
        }
        this.signatureVerifier = signatureVerifier;
    }

    @Override
    protected SystemContract systemContractKind() {
        return SystemContractMethod.SystemContract.HSS;
    }

    @Override
    protected HssCallAttempt self() {
        return this;
    }

    /**
     * Returns whether this is a schedule transaction redirect.
     *
     * @return whether this is a schedule transaction redirect
     * @throws IllegalStateException if this is not a valid call
     */
    public boolean isScheduleRedirect() {
        return isRedirect();
    }

    /**
     * Returns the schedule transaction that is the target of this redirect, if it existed.
     *
     * @return the schedule transaction that is the target of this redirect, or null if it didn't exist
     * @throws IllegalStateException if this is not an schedule transaction redirect
     */
    public @Nullable Schedule redirectScheduleTxn() {
        if (!isRedirect()) {
            throw new IllegalStateException("Not an schedule transaction redirect");
        }
        return redirectScheduleTxn;
    }

    /**
     * Returns the id of the {@link Schedule} that is the target of this redirect, if it existed.
     *
     * @return the id of the schedule that is the target of this redirect, or null if it didn't exist
     * @throws IllegalStateException if this is not a schedule redirect
     */
    public @Nullable ScheduleID redirectScheduleId() {
        if (!isRedirect()) {
            throw new IllegalStateException("Not a schedule redirect");
        }
        return redirectScheduleTxn == null ? null : redirectScheduleTxn.scheduleId();
    }

    /**
     * Returns the {@link Schedule} at the given Besu address, if it exists.
     *
     * @param scheduleAddress the Besu address of the schedule to look up
     * @return the schedule that is the target of this redirect, or null if it didn't exist
     */
    public @Nullable Schedule linkedSchedule(@NonNull final Address scheduleAddress) {
        requireNonNull(scheduleAddress);
        return linkedSchedule(scheduleAddress.toArray());
    }

    /**
     * Returns the {@link Schedule} at the given EVM address, if it exists.
     *
     * @param evmAddress the headlong address of the schedule to look up. This should be encoded as a long zero
     * @return the schedule that is the target of this redirect, or null if it didn't exist
     */
    public @Nullable Schedule linkedSchedule(@NonNull final byte[] evmAddress) {
        requireNonNull(evmAddress);
        if (isLongZeroAddress(evmAddress)) {
            return enhancement.nativeOperations().getSchedule(numberOfLongZero(evmAddress));
        }
        return null;
    }

    /**
     * Extracts the key set for scheduled calls.
     *
     * @return the key set
     */
    public Set<Key> keySetFor() {
        final var sender = nativeOperations().getAccount(senderId());
        requireNonNull(sender);
        if (sender.smartContract()) {
            return getKeysForContractSender();
        } else {
            return getKeysForEOASender();
        }
    }

    @NonNull
    private Set<Key> getKeysForEOASender() {
        // For a top-level EthereumTransaction, use the Ethereum sender key; otherwise,
        // use the full set of simple keys authorizing the ContractCall dispatching this
        // HSS call attempt
        Key key = enhancement.systemOperations().maybeEthSenderKey();
        if (key != null) {
            return Set.of(key);
        }
        return nativeOperations().authorizingSimpleKeys();
    }

    @NonNull
    public Set<Key> getKeysForContractSender() {
        final var contractNum = maybeMissingNumberOf(senderAddress(), nativeOperations());
        if (isOnlyDelegatableContractKeysActive()) {
            return Set.of(Key.newBuilder()
                    .delegatableContractId(
                            ContractID.newBuilder().contractNum(contractNum).build())
                    .build());
        } else {
            return Set.of(Key.newBuilder()
                    .contractID(ContractID.newBuilder().contractNum(contractNum).build())
                    .build());
        }
    }

    /*
     * Returns the {@link SignatureVerifier} used for this call.
     *
     * @return the {@link SignatureVerifier} used for this call
     */
    public @NonNull SignatureVerifier signatureVerifier() {
        return signatureVerifier;
    }
}
