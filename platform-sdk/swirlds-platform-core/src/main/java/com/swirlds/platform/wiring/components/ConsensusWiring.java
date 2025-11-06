// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring.components;

import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.platform.components.consensus.ConsensusEngine;
import com.swirlds.platform.components.consensus.ConsensusEngineOutput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * The wiring for the {@link ConsensusEngine}.
 *
 * @param componentWiring       the wiring
 * @param consensusRoundsOutputWire the output wire that returns rounds
 */
public record ConsensusWiring(
        @NonNull ComponentWiring<ConsensusEngine, ConsensusEngineOutput> componentWiring,
        @NonNull OutputWire<List<ConsensusRound>> consensusRoundsOutputWire) {

    /**
     * Creates the consensusWiring instance
     * @param model  the wiring model
     * @param consensusEngineConfig the configuration to use
     * @return the new instance
     */
    @NonNull
    public static ConsensusWiring create(
            @NonNull final WiringModel model, @NonNull final TaskSchedulerConfiguration consensusEngineConfig) {
        final ComponentWiring<ConsensusEngine, ConsensusEngineOutput> consensusEngineWiring =
                new ComponentWiring<>(model, ConsensusEngine.class, consensusEngineConfig);
        final OutputWire<List<ConsensusRound>> consensusRoundsOutputWire = consensusEngineWiring
                .getOutputWire()
                .buildTransformer("ConsensusRounds", "consensusEngineOutput", ConsensusEngineOutput::consensusRounds);

        return new ConsensusWiring(consensusEngineWiring, consensusRoundsOutputWire);
    }

    /**
     * @see ComponentWiring#getInputWire(Consumer)
     */
    public <T> InputWire<T> getInputWire(final BiConsumer<ConsensusEngine, T> handler) {
        return componentWiring.getInputWire(handler);
    }

    /**
     * @see ComponentWiring#bind(Supplier)
     */
    public void bind(final Supplier<ConsensusEngine> engineSupplier) {
        componentWiring.bind(engineSupplier);
    }

    /**
     * @see ComponentWiring#getOutputWire()
     */
    public OutputWire<ConsensusEngineOutput> getOutputWire() {
        return componentWiring.getOutputWire();
    }

    /**
     * @see ComponentWiring#flush()
     */
    public void flush() {
        componentWiring.flush();
    }

    /**
     * @see ComponentWiring#startSquelching()
     */
    public void startSquelching() {
        componentWiring.startSquelching();
    }

    /**
     * @see ComponentWiring#stopSquelching()
     */
    public void stopSquelching() {
        componentWiring.stopSquelching();
    }
}
