// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.AbstractAssert;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;

/**
 * Assertions for {@link SingleNodeConsensusResult}.
 */
@SuppressWarnings("UnusedReturnValue")
public class SingleNodeStatusProgressionAssert
        extends AbstractAssert<SingleNodeStatusProgressionAssert, SingleNodeStatusProgression> {

    /**
     * Creates a new instance of {@link SingleNodeStatusProgressionAssert}.
     *
     * @param actual the actual {@link SingleNodeStatusProgression} to assert
     */
    public SingleNodeStatusProgressionAssert(@Nullable final SingleNodeStatusProgression actual) {
        super(actual, SingleNodeStatusProgressionAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeStatusProgression}.
     *
     * @param actual the {@link SingleNodeStatusProgression} to assert
     * @return an assertion for the given {@link SingleNodeStatusProgression}
     */
    @NonNull
    public static SingleNodeStatusProgressionAssert assertThat(@Nullable final SingleNodeStatusProgression actual) {
        return new SingleNodeStatusProgressionAssert(actual);
    }

    /**
     * Verifies that the node's statuses went exactly through the specified steps.
     *
     * @param first the first expected step
     * @param rest additional expected steps
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeStatusProgressionAssert hasSteps(
            @NonNull final StatusProgressionStep first, @Nullable final StatusProgressionStep... rest) {
        isNotNull();

        final List<StatusProgressionStep> expectedSteps = new ArrayList<>();
        expectedSteps.add(first);
        if (rest != null) {
            expectedSteps.addAll(Arrays.asList(rest));
        }

        int currentStepIndex = 0;
        final Set<PlatformStatus> observedStatuses = EnumSet.noneOf(PlatformStatus.class);
        for (final Iterator<PlatformStatus> actualStatusesIterator =
                        actual.statusProgression().iterator();
                actualStatusesIterator.hasNext(); ) {
            final PlatformStatus actualStatus = actualStatusesIterator.next();
            final StatusProgressionStep currentStep = expectedSteps.get(currentStepIndex);
            if (actualStatus == currentStep.target()) {
                if (!observedStatuses.containsAll(currentStep.requiredInterim())) {
                    failWithMessage(
                            "Expected required interim statuses %s, but only got %s",
                            currentStep.requiredInterim(), observedStatuses);
                }
                currentStepIndex++;
                if (currentStepIndex >= expectedSteps.size() && actualStatusesIterator.hasNext()) {
                    failWithMessage(
                            "Expected only %s steps, but encountered more statuses %s",
                            expectedSteps.size(), actual.statusProgression());
                }
                observedStatuses.clear();
            } else {
                if (!currentStep.optionalInterim().contains(actualStatus)
                        && !currentStep.requiredInterim().contains(actualStatus)) {
                    failWithMessage("Unexpected status %s in step %s", actualStatus, currentStep);
                }
                observedStatuses.add(actualStatus);
            }
        }

        if (currentStepIndex < expectedSteps.size()) {
            failWithMessage("Expected %s steps, but only got %s", expectedSteps.size(), currentStepIndex);
        }

        return this;
    }
}
