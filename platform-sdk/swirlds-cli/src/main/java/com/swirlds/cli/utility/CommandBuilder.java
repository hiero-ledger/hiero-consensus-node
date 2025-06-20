// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.utility;

import com.swirlds.common.formatting.TextEffect;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;

/**
 * A utility for building command lines automatically from classes annotated with {@link SubcommandOf}.
 */
public final class CommandBuilder {

    private static final CommandLine.Help.ColorScheme COLORFUL_SCHEME = new CommandLine.Help.ColorScheme.Builder()
            .commands(CommandLine.Help.Ansi.Style.bold)
            .options(CommandLine.Help.Ansi.Style.fg_yellow)
            .parameters(CommandLine.Help.Ansi.Style.fg_yellow)
            .optionParams(CommandLine.Help.Ansi.Style.italic)
            .errors(CommandLine.Help.Ansi.Style.fg_red, CommandLine.Help.Ansi.Style.bold)
            .stackTraces(CommandLine.Help.Ansi.Style.italic)
            .build();

    private static final CommandLine.Help.ColorScheme BORING_SCHEME =
            new CommandLine.Help.ColorScheme.Builder().build();

    /**
     * Describes a subcommand.
     *
     * @param subcommandClass
     * 		the class of the subcommand
     * @param parentClass
     * 		the parent command of the subcommand
     */
    public record Subcommand(Class<?> subcommandClass, Class<?> parentClass) {}

    /**
     * Whitelisted packages.
     */
    private static final List<String> whitelistedPackages = new ArrayList<>();

    /**
     * A list of subcommands. Cache the result in a static variable, since we never expect this to change
     * (absent class-loader shenanigans we don't have to worry about supporting).
     */
    private static List<Subcommand> subcommands;

    /**
     * A map of subcommand classes to their parent classes. Cache the result in a static variable, since we never
     * expect this to change (absent class-loader shenanigans we don't have to worry about supporting).
     */
    private static Map<Class<?> /* subcommand class */, Class<?> /* parent class */> parentMap;

    private CommandBuilder() {}

    /**
     * Get the current color scheme.
     */
    public static CommandLine.Help.ColorScheme getColorScheme() {
        if (TextEffect.areTextEffectsEnabled()) {
            return COLORFUL_SCHEME;
        } else {
            return BORING_SCHEME;
        }
    }

    /**
     * Walk the class graph and find all classes that implement {@link SubcommandOf}.
     *
     * @return a list of all classes annotated as sub-commands
     */
    private static synchronized List<Subcommand> findSubcommands() {

        if (subcommands != null) {
            return subcommands;
        }

        final ClassGraph classGraph = new ClassGraph()
                .enableClassInfo()
                .enableAnnotationInfo()
                .whitelistPackages(whitelistedPackages.toArray(new String[0]));

        final List<Subcommand> list = new ArrayList<>();

        try (final ScanResult scanResult = classGraph.scan(Runtime.getRuntime().availableProcessors())) {
            for (final ClassInfo classInfo : scanResult.getClassesWithAnnotation(SubcommandOf.class.getName())) {
                final Class<?> subcommandClass = classInfo.loadClass();
                final SubcommandOf subcommandOf = (SubcommandOf) classInfo
                        .getAnnotationInfo(SubcommandOf.class.getName())
                        .loadClassAndInstantiate();
                final Class<?> parentClass = subcommandOf.value();

                list.add(new Subcommand(subcommandClass, parentClass));
            }
        }

        subcommands = Collections.unmodifiableList(list);
        return subcommands;
    }

    /**
     * Build a map of subcommands to their parents.
     *
     * @return a map of subcommands to their parents
     */
    private static synchronized Map<Class<?> /* subcommand class */, Class<?> /* parent class */> buildParentMap() {
        if (parentMap != null) {
            return parentMap;
        }

        final Map<Class<?>, Class<?>> map = new HashMap<>();
        subcommands.forEach(subcommand -> map.put(subcommand.subcommandClass(), subcommand.parentClass()));

        parentMap = Collections.unmodifiableMap(map);
        return parentMap;
    }

    /**
     * Build a new command line with the appropriate color scheme.
     */
    private static CommandLine buildCommandLineWithColorScheme(final Class<?> clazz) {
        final CommandLine commandLine = new CommandLine(clazz);
        commandLine.setColorScheme(getColorScheme());
        return commandLine;
    }

    /**
     * Build command lines for all commands/subcommands and return a map between
     * each class and its associated command line object.
     *
     * @return a map from command class to corresponding CommandLine object
     */
    private static Map<Class<?> /* subcommand class */, CommandLine /* subcommand CommandLine */> buildCommandLines() {
        final Map<Class<?>, CommandLine> map = new HashMap<>();

        for (final Subcommand subcommand : findSubcommands()) {
            map.computeIfAbsent(subcommand.parentClass(), CommandBuilder::buildCommandLineWithColorScheme);
        }

        findSubcommands()
                .forEach(subcommand -> map.put(
                        subcommand.subcommandClass(), buildCommandLineWithColorScheme(subcommand.subcommandClass())));
        return map;
    }

    /**
     * Link all subcommands with their parent command lines.
     *
     * @param commandLineMap
     * 		a map of command to corresponding CommandLine object
     */
    private static void linkCommandLines(
            Map<Class<?> /* subcommand class */, CommandLine /* subcommand CommandLine */> commandLineMap) {

        final Map<Class<?>, Class<?>> pMap = buildParentMap();

        pMap.keySet().stream().sorted(Comparator.comparing(Class::toString)).forEachOrdered(command -> {
            final CommandLine parentCommandLine = commandLineMap.get(pMap.get(command));
            if (parentCommandLine == null) {
                return;
            }

            parentCommandLine.addSubcommand(commandLineMap.get(command));
        });
    }

    /**
     * Add a whitelisted package, used when scanning the class graph for CLI commands. Must be called before
     * the first call to {@link #buildCommandLine(Class)}.
     *
     * @param packagePrefix
     * 		a package prefix to whitelist
     */
    public static synchronized void whitelistCliPackage(final String packagePrefix) {
        if (subcommands != null) {
            throw new IllegalStateException("Cannot whitelist package after subcommands have been found");
        }
        whitelistedPackages.add(packagePrefix);
    }

    /**
     * Walk the class graph and register any and all subcommands.
     */
    public static CommandLine buildCommandLine(final Class<?> rootCommandClass) {
        final Map<Class<?>, CommandLine> commandLineMap = buildCommandLines();
        linkCommandLines(commandLineMap);
        final CommandLine commandLine = commandLineMap.get(rootCommandClass);
        if (commandLine == null) {
            throw new IllegalStateException("No command line found for " + rootCommandClass);
        }
        return commandLineMap.get(rootCommandClass);
    }
}
