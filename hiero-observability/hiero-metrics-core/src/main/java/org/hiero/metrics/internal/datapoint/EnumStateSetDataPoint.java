// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.hiero.metrics.api.datapoint.StateSetDataPoint;

public final class EnumStateSetDataPoint<E extends Enum<E>> implements StateSetDataPoint<E> {

    private static final VarHandle ARR_HANDLER = MethodHandles.arrayElementVarHandle(boolean[].class);

    private final List<E> initialStates;
    private final Set<E> statesSet;
    private final boolean[] states;

    public EnumStateSetDataPoint(@NonNull Class<E> enumClass) {
        this(List.of(), enumClass);
    }

    public EnumStateSetDataPoint(@NonNull List<E> initialStates, @NonNull Class<E> enumClass) {
        Objects.requireNonNull(initialStates, "initial states list must not be null");
        Objects.requireNonNull(enumClass, "enum class must not be null");

        this.initialStates = List.copyOf(initialStates);
        statesSet = Set.of(enumClass.getEnumConstants());
        states = new boolean[statesSet.size()];

        for (E state : initialStates) {
            states[state.ordinal()] = true;
        }
    }

    @Override
    public void setFalse(E value) {
        ARR_HANDLER.setVolatile(states, value.ordinal(), false);
    }

    @Override
    public void setTrue(E value) {
        ARR_HANDLER.setVolatile(states, value.ordinal(), true);
    }

    @Override
    public boolean getState(E value) {
        return (boolean) ARR_HANDLER.getVolatile(states, value.ordinal());
    }

    @NonNull
    @Override
    public Set<E> getStates() {
        return statesSet;
    }

    @Override
    public void reset() {
        for (E state : statesSet) {
            ARR_HANDLER.setVolatile(states, state.ordinal(), initialStates.contains(state));
        }
    }
}
