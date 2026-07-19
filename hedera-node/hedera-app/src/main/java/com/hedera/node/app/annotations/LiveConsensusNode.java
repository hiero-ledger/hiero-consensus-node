// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.annotations;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.inject.Qualifier;

/**
 * Qualifies a {@code boolean} that is {@code true} on every live consensus node (regardless of {@code InitTrigger} —
 * genesis, restart, reconnect, etc.) and {@code false} only in the in-process standalone transaction executor.
 *
 * <p>This is the signal for logic that must behave differently on a real consensus node than in the standalone executor
 * (e.g. the Mirror Node gas-estimation / eth_call executor, which legitimately dispatches NODE-category transactions
 * with a caller-chosen payer). It is provided as a per-component constant — {@code true} by {@code WorkflowsInjectionModule}
 * (real node) and {@code false} by {@code StandaloneModule} (executor) — so it is decoupled from incidental state such as
 * whether a {@code BlockRecordManager} happens to be present.
 */
@Target({ElementType.METHOD, ElementType.PARAMETER})
@Qualifier
@Retention(RUNTIME)
public @interface LiveConsensusNode {}
