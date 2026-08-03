// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import com.swirlds.base.time.Time;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.model.diagram.ModelEdgeSubstitution;
import org.hiero.consensus.wiring.framework.model.diagram.ModelGroup;
import org.hiero.consensus.wiring.framework.model.diagram.ModelManualLink;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerType;
import org.hiero.consensus.wiring.framework.wires.SolderType;
import picocli.CommandLine;

@CommandLine.Command(
        name = "legend",
        mixinStandardHelpOptions = true,
        description = "Generate a legend for the mermaid style diagrams of platform wiring.")
@SubcommandOf(DiagramCommand.class)
public final class DiagramLegendCommand extends AbstractCommand {
    private DiagramLegendCommand() {}

    /**
     * Entry point.
     */
    @Override
    public Integer call() throws IOException {

        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskScheduler<Integer> sequentialScheduler = model.<Integer>schedulerBuilder("SequentialScheduler")
                .withType(TaskSchedulerType.SEQUENTIAL)
                .withUnhandledTaskCapacity(1)
                .build();
        final TaskScheduler<Void> sequentialThreadScheduler = model.<Void>schedulerBuilder("SequentialThreadScheduler")
                .withType(TaskSchedulerType.SEQUENTIAL_THREAD)
                .withUnhandledTaskCapacity(1)
                .build();
        final TaskScheduler<Void> directScheduler = model.<Void>schedulerBuilder("DirectScheduler")
                .withType(TaskSchedulerType.DIRECT)
                .build();
        final TaskScheduler<Void> directThreadsafeScheduler = model.<Void>schedulerBuilder("DirectThreadsafeScheduler")
                .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                .build();
        final TaskScheduler<Void> concurrentScheduler = model.<Void>schedulerBuilder("ConcurrentScheduler")
                .withType(TaskSchedulerType.CONCURRENT)
                .build();

        final String wireSubstitutionString = "wire substitution (for readability)";
        sequentialScheduler.getOutputWire().solderTo(sequentialThreadScheduler.buildInputWire(wireSubstitutionString));
        directScheduler.getOutputWire().solderTo(directThreadsafeScheduler.buildInputWire("wire with backpressure"));
        sequentialThreadScheduler
                .getOutputWire()
                .solderTo(directScheduler.buildInputWire("wire without backpressure"), SolderType.OFFER);

        final String diagramString = model.generateWiringDiagram(
                List.of(new ModelGroup(
                        "Arbitrary Grouping",
                        Set.of(
                                sequentialScheduler.getName(),
                                sequentialThreadScheduler.getName(),
                                directScheduler.getName(),
                                directThreadsafeScheduler.getName(),
                                concurrentScheduler.getName()),
                        false)),
                List.of(new ModelEdgeSubstitution(sequentialScheduler.getName(), wireSubstitutionString, "✩")),
                List.of(new ModelManualLink(
                        concurrentScheduler.getName(),
                        "manual diagram link (not actually a wire)",
                        sequentialScheduler.getName())),
                false);
        final String encodedDiagramString = Base64.getEncoder().encodeToString(diagramString.getBytes());

        final String editorUrl = "https://mermaid.ink/svg/" + encodedDiagramString + "?bgColor=e8e8e8";

        System.out.println(diagramString);
        System.out.println();
        System.out.println(editorUrl);
        return 0;
    }
}
