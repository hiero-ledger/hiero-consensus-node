// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.source;

import com.swirlds.common.test.fixtures.TransactionGenerator;
import com.swirlds.platform.internal.EventImpl;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * An AbstractEventSource that will periodically branch.
 */
public class BranchingEventSource extends AbstractEventSource {

    /**
     * The maximum number of branches to maintain.
     */
    private int maximumBranchCount;

    /**
     * For any particular event, the probability (out of 1.0) that the event will start a new branched branch.
     */
    private double branchProbability;

    /**
     * An collection of branches. Each branch contains a number of recent events on that branch.
     */
    private List<LinkedList<EventImpl>> branches;

    /**
     * The index of the event that was last given out as the "latest" event.
     */
    private int currentBranch;

    public BranchingEventSource() {
        this(true, DEFAULT_TRANSACTION_GENERATOR);
    }

    public BranchingEventSource(final boolean useFakeHashes) {
        this(useFakeHashes, DEFAULT_TRANSACTION_GENERATOR);
    }

    public BranchingEventSource(final boolean useFakeHashes, final TransactionGenerator transactionGenerator) {
        super(useFakeHashes, transactionGenerator);
        maximumBranchCount = 3;
        branchProbability = 0.01;
        setMaximumBranchCount(maximumBranchCount);
    }

    private BranchingEventSource(final BranchingEventSource that) {
        super(that);
        setMaximumBranchCount(that.maximumBranchCount);
        this.branchProbability = that.branchProbability;
    }

    /**
     * Get the maximum number of branched branches that this source maintains.
     */
    public int getMaximumBranchCount() {
        return maximumBranchCount;
    }

    /**
     * Set the maximum number of branches that this source maintains.
     *
     * Undefined behavior if set after events have already been generated.
     *
     * @return this
     */
    public BranchingEventSource setMaximumBranchCount(final int maximumBranchCount) {
        if (maximumBranchCount < 1) {
            throw new IllegalArgumentException("Requires at least one branch");
        }
        this.maximumBranchCount = maximumBranchCount;
        this.branches = new ArrayList<>(maximumBranchCount);
        return this;
    }

    /**
     * Get the probability that any particular event will form a new branched branch.
     *
     * @return A probability as a fraction of 1.0.
     */
    public double getBranchProbability() {
        return branchProbability;
    }

    /***
     * Set the probability that any particular event will form a new branched branch.
     * @param branchProbability A probability as a fraction of 1.0.
     * @return this
     */
    public BranchingEventSource setBranchProbability(final double branchProbability) {
        this.branchProbability = branchProbability;
        return this;
    }

    @Override
    public BranchingEventSource copy() {
        return new BranchingEventSource(this);
    }

    @Override
    public void reset() {
        super.reset();
        branches = new ArrayList<>(maximumBranchCount);
    }

    @Override
    public EventImpl getRecentEvent(final Random random, final int index) {
        if (branches.isEmpty()) {
            return null;
        }

        currentBranch = random.nextInt(branches.size());
        final LinkedList<EventImpl> events = branches.get(currentBranch);

        if (events.size() == 0) {
            return null;
        }

        if (index >= events.size()) {
            return events.getLast();
        }

        return events.get(index);
    }

    /**
     * Decide if the next event created should branch.
     */
    private boolean shouldBranch(final Random random) {
        return maximumBranchCount > 1 && random.nextDouble() < branchProbability;
    }

    /**
     * Branch. This creates a new branch, replacing a random branch if the maximum number of
     * branches is exceeded.
     */
    private void branch(final Random random) {
        if (branches.size() < maximumBranchCount) {
            // Add the new branch
            currentBranch = branches.size();
            branches.add(new LinkedList<>());
        } else {
            // Replace a random old branch with the new branch
            int newEventIndex;
            do {
                newEventIndex = random.nextInt(branches.size());
            } while (newEventIndex == currentBranch);

            currentBranch = newEventIndex;
        }
    }

    @Override
    public void setLatestEvent(final Random random, final EventImpl event) {
        if (shouldBranch(random)) {
            branch(random);
        }

        // Make sure there is at least one branch
        if (branches.size() == 0) {
            branches.add(new LinkedList<>());
            currentBranch = 0;
        }

        final LinkedList<EventImpl> branch = branches.get(currentBranch);
        branch.addFirst(event);

        pruneEventList(branch);
    }

    /**
     * Get the list of all branches for this branching source.
     *
     * @return A list of all branches. Each branch is a list of events.
     */
    public List<LinkedList<EventImpl>> getBranches() {
        return branches;
    }
}
