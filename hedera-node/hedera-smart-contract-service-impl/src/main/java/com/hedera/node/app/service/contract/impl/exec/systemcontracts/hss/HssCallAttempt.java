// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.maybeMissingNumberOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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

    public HssCallAttempt(
            @NonNull final Bytes input,
            @NonNull final CallAttemptOptions<HssCallAttempt> options,
            @NonNull final SignatureVerifier signatureVerifier) {
        super(input, options, REDIRECT_FOR_SCHEDULE_TXN);
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
        if (isLongZero(enhancement().nativeOperations().entityIdFactory(), scheduleAddress)) {
            return enhancement().nativeOperations().getSchedule(numberOfLongZero(scheduleAddress.toArray()));
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
        Key key = enhancement().systemOperations().maybeEthSenderKey();
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
                            enhancement().nativeOperations().entityIdFactory().newContractId(contractNum))
                    .build());
        } else {
            return Set.of(Key.newBuilder()
                    .contractID(
                            enhancement().nativeOperations().entityIdFactory().newContractId(contractNum))
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
