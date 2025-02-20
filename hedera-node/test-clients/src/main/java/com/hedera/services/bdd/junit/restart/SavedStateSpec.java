// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.restart;

import com.hedera.node.app.fixtures.state.FakeState;
import java.util.function.Consumer;

/**
 * A functional interface to customize the state of a {@link FakeState} object when setting up a {@link RestartHapiTest}.
 */
@FunctionalInterface
public interface SavedStateSpec extends Consumer<FakeState> {}
