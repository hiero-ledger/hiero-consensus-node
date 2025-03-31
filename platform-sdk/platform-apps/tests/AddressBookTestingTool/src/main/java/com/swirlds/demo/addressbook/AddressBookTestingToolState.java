// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.addressbook;
/*
 * This file is public domain.
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.common.constructable.ConstructableIgnored;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.merkle.singleton.StringLeaf;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.utility.ByteUtils;

/**
 * State for the AddressBookTestingTool.
 */
@ConstructableIgnored
public class AddressBookTestingToolState extends MerkleStateRoot<AddressBookTestingToolState>
        implements MerkleNodeState {

    private static final Logger logger = LogManager.getLogger(AddressBookTestingToolState.class);

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private static final long CLASS_ID = 0xf052378c7364ef47L;
    // 0 is PLATFORM_STATE, 1 is ROSTERS, 2 is ROSTER_STATE
    private static final int RUNNING_SUM_INDEX = 3;
    private static final int ROUND_HANDLED_INDEX = 4;

    /**
     * The true "state" of this app. Each transaction is just an integer that gets added to this value.
     */
    private long runningSum = 0;

    /**
     * The number of rounds handled by this app. Is incremented each time
     * {@link AddressBookTestingToolConsensusStateEventHandler#onHandleConsensusRound(Round, AddressBookTestingToolState, Consumer)} is called. Note that this may not actually equal the round
     * number, since we don't call {@link AddressBookTestingToolConsensusStateEventHandler#onHandleConsensusRound(Round, AddressBookTestingToolState, Consumer)} for rounds with no events.
     *
     * <p>
     * Affects the hash of this node.
     */
    private long roundsHandled = 0;

    public AddressBookTestingToolState() {
        logger.info(STARTUP.getMarker(), "New State Constructed.");
    }

    /**
     * Copy constructor.
     */
    private AddressBookTestingToolState(@NonNull final AddressBookTestingToolState that) {
        super(that);
        Objects.requireNonNull(that, "the address book testing tool state to copy cannot be null");
        this.runningSum = that.runningSum;
        this.roundsHandled = that.roundsHandled;
    }

    /**
     * Initializes state fields from the merkle tree children
     */
    void initState() {
        final StringLeaf runningSumLeaf = getChild(RUNNING_SUM_INDEX);
        if (runningSumLeaf != null && runningSumLeaf.getLabel() != null) {
            this.runningSum = Long.parseLong(runningSumLeaf.getLabel());
            logger.info(STARTUP.getMarker(), "State initialized with state long {}.", runningSum);
        }
        final StringLeaf roundsHandledLeaf = getChild(ROUND_HANDLED_INDEX);
        if (roundsHandledLeaf != null && roundsHandledLeaf.getLabel() != null) {
            this.roundsHandled = Long.parseLong(roundsHandledLeaf.getLabel());
            logger.info(STARTUP.getMarker(), "State initialized with {} rounds handled.", roundsHandled);
        }
    }

    void incrementRoundsHandled() {
        roundsHandled++;
        setChild(ROUND_HANDLED_INDEX, new StringLeaf(Long.toString(roundsHandled)));
    }

    void incrementRunningSum(final long value) {
        runningSum += value;
        setChild(RUNNING_SUM_INDEX, new StringLeaf(Long.toString(runningSum)));
    }

    long getRoundsHandled() {
        return roundsHandled;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public synchronized AddressBookTestingToolState copy() {
        throwIfImmutable();
        setImmutable(true);
        return new AddressBookTestingToolState(this);
    }

    /**
     * Checks if the transaction bytes are system ones. The test creates application transactions with max length of 4.
     * System transactions will be always bigger than that.
     *
     * @param transaction the consensus transaction to check
     * @return true if the transaction bytes are system ones, false otherwise
     */
    boolean areTransactionBytesSystemOnes(final Transaction transaction) {
        return transaction.getApplicationTransaction().length() > 4;
    }

    /**
     * Apply a transaction to the state.
     *
     * @param transaction the transaction to apply
     */
    private void handleTransaction(@NonNull final ConsensusTransaction transaction) {
        final int delta =
                ByteUtils.byteArrayToInt(transaction.getApplicationTransaction().toByteArray(), 0);
        runningSum += delta;
        setChild(RUNNING_SUM_INDEX, new StringLeaf(Long.toString(runningSum)));
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.ORIGINAL;
    }

    @Override
    protected AddressBookTestingToolState copyingConstructor() {
        return new AddressBookTestingToolState(this);
    }
}
