// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.model.diagram.ModelEdgeSubstitution;
import com.swirlds.component.framework.model.diagram.ModelGroup;
import com.swirlds.component.framework.model.diagram.ModelManualLink;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.builder.ApplicationCallbacks;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.util.VirtualTerminal;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.platform.wiring.PlatformWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import picocli.CommandLine;

@CommandLine.Command(
        name = "diagram",
        mixinStandardHelpOptions = true,
        description = "Generate a mermaid style diagram of platform wiring. Requires mermaid CLI to be installed.")
@SubcommandOf(PlatformCli.class)
public final class DiagramCommand extends AbstractCommand {

    private List<String> groupStrings = List.of();
    private Set<String> collapsedGroups = Set.of();
    private List<String> substitutionStrings = List.of();
    private List<String> manualLinks = List.of();
    private Path outputFilePath = Path.of("wiring-diagram.svg");
    private boolean verbose = false;
    private boolean lessMystery = false;

    private DiagramCommand() {}

    @CommandLine.Option(
            names = {"-g", "--group"},
            description = "Specify an un-collapsed grouping. Format is 'GROUP_NAME:COMPONENT_NAME[,COMPONENT_NAME]*'.")
    private void setGroupStrings(@NonNull final List<String> groupStrings) {
        this.groupStrings = groupStrings;
    }

    @CommandLine.Option(
            names = {"-c", "--collapse"},
            description = "Specify the name of a group that should be collapsed.")
    private void setCollapsedGroups(@NonNull final Set<String> collapsedGroups) {
        this.collapsedGroups = collapsedGroups;
    }

    @CommandLine.Option(
            names = {"-s", "--substitute"},
            description = "Substitute a type of edge with a symbol. Useful for spammy edges. "
                    + "Format: SOURCE_COMPONENT:EDGE_DESCRIPTION:SYMBOL")
    private void setCollapsedGroups(@NonNull final List<String> substitutionStrings) {
        this.substitutionStrings = substitutionStrings;
    }

    @CommandLine.Option(
            names = {"-l", "--link"},
            description = "Manually link two components in the diagram. "
                    + "Format: SOURCE_COMPONENT:EDGE_LABEL:TARGET_COMPONENT")
    private void setManualLinks(@NonNull final List<String> manualLinks) {
        this.manualLinks = manualLinks;
    }

    @CommandLine.Option(
            names = {"-o", "--output"},
            description = "Specify the path to the output file. Defaults to 'wiring-diagram.svg'.")
    private void setOutputFile(@NonNull final String outputFilePath) {
        this.outputFilePath = Path.of(outputFilePath).normalize();
    }

    @CommandLine.Option(
            names = {"-v", "--verbose"},
            description = "Print the diagram to the console.")
    private void setVerbose(final boolean verbose) {
        this.verbose = verbose;
    }

    @CommandLine.Option(
            names = {"-m", "--less-mystery"},
            description = "Do not hide mystery edges. May create a noisy diagram if there are a lot of mystery edges.")
    private void setLessMystery(final boolean lessMystery) {
        this.lessMystery = lessMystery;
    }

    /**
     * Entry point.
     */
    @Override
    public Integer call() throws IOException {
        final Configuration configuration = DefaultConfiguration.buildBasicConfiguration(ConfigurationBuilder.create());
        final PlatformContext platformContext = PlatformContext.create(configuration);

        final ApplicationCallbacks callbacks = new ApplicationCallbacks(x -> {}, x -> {}, x -> {});

        final WiringModel model = WiringModelBuilder.create(platformContext.getMetrics(), platformContext.getTime())
                .build();

        final PlatformComponents platformComponents = PlatformComponents.create(platformContext, model, callbacks);

        PlatformWiring.wire(platformContext, new NoOpExecutionLayer(), platformComponents);

        final String diagramString =
                model.generateWiringDiagram(parseGroups(), parseSubstitutions(), parseManualLinks(), !lessMystery);

        final VirtualTerminal terminal = new VirtualTerminal()
                .setProgressIndicatorEnabled(false)
                .setThrowOnError(true)
                .setPrintStdout(true)
                .setPrintStderr(true);

        final Path temporaryMermaidFile = Path.of("platformWiring.mmd");

        if (Files.exists(temporaryMermaidFile)) {
            Files.delete(temporaryMermaidFile);
        }

        Files.createFile(temporaryMermaidFile);
        Files.writeString(temporaryMermaidFile, diagramString);

        terminal.run(
                "mmdc",
                "-i",
                temporaryMermaidFile.toString(),
                "-o",
                outputFilePath.toString(),
                "--backgroundColor",
                "555555");
        Files.delete(temporaryMermaidFile);

        if (verbose) {
            System.out.println(diagramString);
        }
        return 0;
    }

    /**
     * Parse groups from the command line arguments.
     *
     * @return a list of zero or more groups
     */
    @NonNull
    private List<ModelGroup> parseGroups() {
        final List<ModelGroup> groups = new ArrayList<>();

        for (final String group : groupStrings) {
            final String[] parts = group.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid group string: " + group);
            }
            final String groupName = parts[0];
            final String[] elements = parts[1].split(",");
            groups.add(new ModelGroup(groupName, Set.of(elements), collapsedGroups.contains(groupName)));
        }

        return groups;
    }

    /**
     * Parse substitutions from the command line arguments.
     *
     * @return a list of zero or more substitutions
     */
    @NonNull
    private List<ModelEdgeSubstitution> parseSubstitutions() {
        final List<ModelEdgeSubstitution> substitutions = new ArrayList<>();
        for (final String substitutionString : substitutionStrings) {
            final String[] parts = substitutionString.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid substitution string: " + substitutionString);
            }
            final String sourceComponent = parts[0];
            final String edgeDescription = parts[1];
            final String symbol = parts[2];
            substitutions.add(new ModelEdgeSubstitution(sourceComponent, edgeDescription, symbol));
        }

        return substitutions;
    }

    /**
     * Parse manual links from the command line arguments.
     *
     * @return a list of zero or more manual links
     */
    @NonNull
    private List<ModelManualLink> parseManualLinks() {
        final List<ModelManualLink> links = new ArrayList<>();
        for (final String linkString : manualLinks) {
            final String[] parts = linkString.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid link string: " + linkString);
            }
            final String sourceComponent = parts[0];
            final String edgeLabel = parts[1];
            final String targetComponent = parts[2];
            links.add(new ModelManualLink(sourceComponent, edgeLabel, targetComponent));
        }

        return links;
    }
}
