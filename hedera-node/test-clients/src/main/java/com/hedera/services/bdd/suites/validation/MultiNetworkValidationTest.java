// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.validation;

import static com.hedera.services.bdd.junit.TestTags.MULTINETWORK;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateAllLogsAfter;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateStreams;
import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

import com.hedera.services.bdd.junit.extensions.ClassesRunConcurrentlyCondition;
import com.hedera.services.bdd.junit.extensions.MultiNetworkValidation;
import com.hedera.services.bdd.junit.extensions.SpecNamingExtension;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;

/**
 * Validates the logs and then the streams of every multi-network network, one container per network group, as each
 * group finishes its tests; the group is torn down right after. Runs alongside the groups, so it needs test classes
 * to run concurrently. See {@link MultiNetworkValidation}.
 */
@Tag(MULTINETWORK)
@ExtendWith(ClassesRunConcurrentlyCondition.class)
@Order(Integer.MAX_VALUE)
public class MultiNetworkValidationTest {
    private static final Duration VALIDATION_DELAY = Duration.ofSeconds(1);

    @TestFactory
    @ExtendWith(SpecNamingExtension.class)
    // A group's container must close only after its tests ran, since closing it tears the group down
    @Execution(SAME_THREAD)
    final Stream<DynamicContainer> logsAndStreamsAreValid() {
        return MultiNetworkValidation.groupsToValidate(
                () -> validateAllLogsAfter(VALIDATION_DELAY), () -> validateStreams());
    }
}
