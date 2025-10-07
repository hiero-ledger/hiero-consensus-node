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

    private int currentBranch;

    public BranchingEventSource() {
        this(false);
    }

    public BranchingEventSource(final boolean useFakeHashes) {
        this(useFakeHashes, null);
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
     *
     * @return the maximum number of branched branches
     */
    public int getMaximumBranchCount() {
        return maximumBranchCount;
    }

    /**
     * Set the maximum number of branched branches that this source maintains.
     *
     * @param maximumBranchCount the maximum number of branches
     * @return this object
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
     * @return the probability of a new branch
     */
    public double getBranchProbability() {
        return branchProbability;
    }

    /**
     * Set the probability that any particular event will form a new branched branch.
     *
     * @param branchProbability A probability as a fraction of 1.0.
     * @return this object
     */
    public BranchingEventSource setBranchProbability(final double branchProbability) {
        this.branchProbability = branchProbability;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BranchingEventSource copy() {
        return new BranchingEventSource(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected EventImpl buildNextEvent(final Random random) {

        // Do a lazy initialization of branches
        branches = new ArrayList<>(maximumBranchCount);

        if (branches.isEmpty()) {
            branches.add(new LinkedList<>());
            currentBranch = 0;
        } else {
            // Choose a random branch
            currentBranch = random.nextInt(branches.size());
        }

        final LinkedList<EventImpl> events = branches.get(currentBranch);

        return buildEvent(random, getSelfParent(events, random), getOtherParent(events, random));
    }

    /**
     * Decide if the next event created should branch.
     *
     * @param random a source of randomness
     * @return true if the next event should create a new branch
     */
    private boolean shouldBranch(final Random random) {
        return maximumBranchCount > 1 && random.nextDouble() < branchProbability;
    }

    /**
     * Branch. This creates a new branch, replacing a random branch if the maximum number of
     * branches is exceeded.
     *
     * @param random a source of randomness
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

            branches.set(newEventIndex, new LinkedList<>());
            currentBranch = newEventIndex;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventImpl generateEvent(final Random random) {
        final EventImpl event = buildNextEvent(random);

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

        return event;
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