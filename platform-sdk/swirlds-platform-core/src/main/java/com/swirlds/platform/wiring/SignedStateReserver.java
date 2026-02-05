// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;

import com.swirlds.component.framework.transformers.AdvancedTransformation;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileWriter;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.StackTrace;

/**
 * Manages reservations of a signed state when it needs to be passed to one or more input wires.
 * <p>
 * The contract for managing reservations across vertexes in the wiring is as follows:
 * <ul>
 *     <li>Each vertex, on input, will receive a state reserved for that vertex</li>
 *     <li>The vertex which should either release that state, or return it</li>
 * </ul>
 * The reserver enforces this contract by reserving the state for each input wire, and then releasing the reservation
 * made for the reserver.
 * <p>
 * For each input wire, {@link #transform(ReservedSignedState)} will be called once, reserving the state for that input
 * wire. After a reservation is made for each input wire, {@link #inputCleanup(ReservedSignedState)} will be called once to
 * release the original reservation.
 *
 * @param name the name of the reserver
 */
public record SignedStateReserver(@NonNull String name)
        implements AdvancedTransformation<ReservedSignedState, ReservedSignedState> {

    private static final Logger logger = LogManager.getLogger(SignedStateReserver.class);

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ReservedSignedState transform(@NonNull final ReservedSignedState reservedSignedState) {
        if (name.equals("completeStatesReserver")) {
            logger.info(STATE_TO_DISK.getMarker(), "transform(): Getting and reserving state with round={}, reservation reason={}, state to disk reason={}, isFreeze={}",
                    reservedSignedState.get().getRound(), reservedSignedState.reason, reservedSignedState.get().getStateToDiskReason(), reservedSignedState.get().isFreezeState());
        }
        return reservedSignedState.getAndReserve(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void inputCleanup(@NonNull final ReservedSignedState reservedSignedState) {
        if (name.equals("completeStatesReserver")) {
            logger.info(STATE_TO_DISK.getMarker(), "inputCleanup(): Releasing state with round={}, reservation reason={}, state to disk reason={}, isFreeze={}",
                    reservedSignedState.get().getRound(), reservedSignedState.reason, reservedSignedState.get().getStateToDiskReason(), reservedSignedState.get().isFreezeState());
        }
        reservedSignedState.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outputCleanup(@NonNull final ReservedSignedState reservedSignedState) {
        if (name.equals("completeStatesReserver")) {
            logger.info(STATE_TO_DISK.getMarker(), "outputCleanup(): Releasing state with round={}, reservation reason={}, state to disk reason={}, isFreeze={}",
                    reservedSignedState.get().getRound(), reservedSignedState.reason, reservedSignedState.get().getStateToDiskReason(), reservedSignedState.get().isFreezeState());
        }
        reservedSignedState.close();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getTransformerName() {
        return name;
    }

    @NonNull
    @Override
    public String getTransformerInputName() {
        return "state to reserve";
    }
}
