// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.source;

import com.swirlds.common.test.fixtures.TransactionGenerator;
import com.swirlds.platform.internal.EventImpl;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Random;

/**
 * An AbstractEventSource that will periodically fork.
 */
public class ForkingEventSource extends AbstractEventSource {

    /**
     * The maximum number of branches to maintain.
     */
    private int maximumBranchCount;

    /**
     * For any particular event, the probability (out of 1.0) that the event will start a new forked branch.
     */
    private double forkProbability;

    /**
     * An collection of branches. Each branch contains a number of recent events on that branch.
     */
    private ArrayList<LinkedList<EventImpl>> branches;

    /**
     * The index of the event that was last given out as the "latest" event.
     */
    private int currentBranch;

    public ForkingEventSource() {
        this(true, DEFAULT_TRANSACTION_GENERATOR);
    }

    public ForkingEventSource(final boolean useFakeHashes) {
        this(useFakeHashes, DEFAULT_TRANSACTION_GENERATOR);
    }

    public ForkingEventSource(final boolean useFakeHashes, final TransactionGenerator transactionGenerator) {
        super(useFakeHashes, transactionGenerator);
        maximumBranchCount = 3;
        forkProbability = 0.01;
        setMaximumBranchCount(maximumBranchCount);
    }

    private ForkingEventSource(final ForkingEventSource that) {
        super(that);
        setMaximumBranchCount(that.maximumBranchCount);
        this.forkProbability = that.forkProbability;
    }

    /**
     * Get the maximum number of forked branches that this source maintains.
     */
    public int getMaximumBranchCount() {
        return maximumBranchCount;
    }

    /**
     * Set the maximum number of forced branches that this source maintains.
     *
     * Undefined behavior if set after events have already been generated.
     *
     * @return this
     */
    public ForkingEventSource setMaximumBranchCount(final int maximumBranchCount) {
        if (maximumBranchCount < 1) {
            throw new IllegalArgumentException("Requires at least one branch");
        }
        this.maximumBranchCount = maximumBranchCount;
        this.branches = new ArrayList<>(maximumBranchCount);
        return this;
    }

    /**
     * Get the probability that any particular event will form a new forked branch.
     *
     * @return A probability as a fraction of 1.0.
     */
    public double getForkProbability() {
        return forkProbability;
    }

    /***
     * Set the probability that any particular event will form a new forked branch.
     * @param forkProbability A probability as a fraction of 1.0.
     * @return this
     */
    public ForkingEventSource setForkProbability(final double forkProbability) {
        this.forkProbability = forkProbability;
        return this;
    }

    @Override
    public ForkingEventSource copy() {
        return new ForkingEventSource(this);
    }

    @Override
    public void reset() {
        super.reset();
        branches = new ArrayList<>(maximumBranchCount);
    }

    @Override
    public EventImpl getRecentEvent(final Random random, final int index) {
        if (branches.size() == 0) {
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
     * Decide if the next event created should fork.
     */
    private boolean shouldFork(final Random random) {
        return maximumBranchCount > 1 && random.nextDouble() < forkProbability;
    }

    /**
     * Fork. This creates a new branch, replacing a random branch if the maximum number of
     * branches is exceeded.
     */
    private void fork(final Random random) {
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
        if (shouldFork(random)) {
            fork(random);
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
}
