// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.statevalidation.gcp.GcpPathHelper;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the whole module against formatting and parsing that silently picks up the JVM default
 * locale.
 *
 * <p>Most of the affected call sites sit inside commands and workflows that a unit test cannot reach,
 * so this scans the module's own compiled classes instead. Every watched method has an overload that
 * takes an explicit {@link java.util.Locale}, and a class references a method only when it actually
 * calls it, so reading the constant pool detects every remaining use without executing anything.
 *
 * <p>A call is accepted when a {@code Locale} appears among its <em>parameters</em>. Checking the
 * parameters rather than the whole descriptor matters for {@code Locale.getDefault()}, which returns
 * a {@code Locale} but takes none: passing it explicitly is still default-locale behaviour, only
 * better disguised.
 */
class DefaultLocaleFormattingTest {

    /**
     * Methods whose behaviour depends on the default locale unless one is passed, grouped by owner.
     * Listing method names rather than full descriptors covers every overload, including ones added
     * by a later JDK.
     */
    private static final Map<String, Set<String>> LOCALE_SENSITIVE = Map.ofEntries(
            // Text and case
            Map.entry("java/lang/String", Set.of("format", "formatted", "toUpperCase", "toLowerCase")),
            Map.entry("java/io/PrintStream", Set.of("printf", "format")),
            Map.entry("java/io/PrintWriter", Set.of("printf", "format")),
            Map.entry("java/util/Formatter", Set.of("<init>")),
            // Dates and times
            Map.entry(
                    "java/time/format/DateTimeFormatter",
                    Set.of("ofPattern", "ofLocalizedDate", "ofLocalizedTime", "ofLocalizedDateTime")),
            Map.entry("java/text/SimpleDateFormat", Set.of("<init>")),
            Map.entry(
                    "java/text/DateFormat",
                    Set.of("getInstance", "getDateInstance", "getTimeInstance", "getDateTimeInstance")),
            // Numbers
            Map.entry(
                    "java/text/NumberFormat",
                    Set.of(
                            "getInstance",
                            "getNumberInstance",
                            "getIntegerInstance",
                            "getCurrencyInstance",
                            "getPercentInstance",
                            "getCompactNumberInstance")),
            Map.entry("java/text/DecimalFormat", Set.of("<init>")),
            Map.entry("java/text/DecimalFormatSymbols", Set.of("<init>", "getInstance")),
            // Messages, ordering and segmentation
            Map.entry("java/text/MessageFormat", Set.of("<init>", "format")),
            Map.entry("java/text/Collator", Set.of("getInstance")),
            Map.entry(
                    "java/text/BreakIterator",
                    Set.of("getWordInstance", "getLineInstance", "getSentenceInstance", "getCharacterInstance")),
            // Reading the ambient default, which is the same behaviour with an explicit-looking argument
            Map.entry("java/util/Locale", Set.of("getDefault")));

    /**
     * Methods that encode or decode with the platform default charset unless one is passed. Since
     * JEP 400 that default is UTF-8, but {@code -Dfile.encoding} still overrides it, and a narrower
     * charset silently replaces unmappable characters with {@code ?}.
     */
    private static final Map<String, Set<String>> CHARSET_SENSITIVE = Map.ofEntries(
            Map.entry("java/io/FileWriter", Set.of("<init>")),
            Map.entry("java/io/FileReader", Set.of("<init>")),
            Map.entry("java/io/InputStreamReader", Set.of("<init>")),
            Map.entry("java/io/OutputStreamWriter", Set.of("<init>")),
            Map.entry("java/io/PrintStream", Set.of("<init>")),
            Map.entry("java/io/PrintWriter", Set.of("<init>")),
            Map.entry("java/io/ByteArrayOutputStream", Set.of("toString")),
            Map.entry("java/lang/String", Set.of("<init>", "getBytes")),
            Map.entry("java/nio/charset/Charset", Set.of("defaultCharset")));

    @Test
    @DisplayName("no class in the module formats or converts without an explicit Locale or Charset")
    void noClassDependsOnAPlatformDefault() throws IOException {
        final Path classesDir = mainClassesDirectory();
        final List<String> offenders = new ArrayList<>();

        try (Stream<Path> classFiles = Files.walk(classesDir)) {
            for (final Path classFile : classFiles
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .toList()) {
                for (final String reference : platformDefaultCallsIn(classFile)) {
                    offenders.add(classesDir.relativize(classFile) + " -> " + reference);
                }
            }
        }

        assertThat(offenders)
                .as("call sites that must pass an explicit Locale (Locale.ROOT) or Charset (UTF_8)")
                .isEmpty();
    }

    /**
     * Locates the module's compiled main classes, which is where the production code this test guards
     * ends up. The test itself lives in a separate output directory and is deliberately not scanned.
     */
    private static Path mainClassesDirectory() {
        final var location =
                GcpPathHelper.class.getProtectionDomain().getCodeSource().getLocation();
        final Path fromCodeSource = Path.of(location.getPath());
        return Files.isDirectory(fromCodeSource) ? fromCodeSource : Path.of("build", "classes", "java", "main");
    }

    /**
     * Returns the watched methods this class calls without handing them a {@code Locale}, read from
     * its constant pool by the JDK's own class-file parser so that this test carries no assumptions
     * about the file format.
     */
    private static List<String> platformDefaultCallsIn(final Path classFile) throws IOException {
        final List<String> offenders = new ArrayList<>();
        for (final PoolEntry entry : ClassFile.of().parse(classFile).constantPool()) {
            if (entry instanceof MemberRefEntry member) {
                final String owner = member.owner().asInternalName();
                final String name = member.name().stringValue();
                final String descriptor = member.type().stringValue();
                if (!descriptor.startsWith("(")) {
                    continue; // a field reference, which shares this entry type but has no parameters
                }
                final String parameters = descriptor.substring(1, descriptor.indexOf(')'));
                final boolean offends = (LOCALE_SENSITIVE
                                        .getOrDefault(owner, Set.of())
                                        .contains(name)
                                && !parameters.contains("Ljava/util/Locale;"))
                        || (CHARSET_SENSITIVE.getOrDefault(owner, Set.of()).contains(name)
                                && decidesTheCharset(owner, name, parameters));
                if (offends) {
                    offenders.add(owner + "." + name + ":" + descriptor);
                }
            }
        }
        return offenders;
    }

    /**
     * True when the call actually picks a charset and was not given one. Overloads that wrap an
     * existing {@link java.io.Writer} or {@link java.io.Reader} decide nothing, and for
     * {@code String} only the {@code byte[]} conversions are involved.
     */
    private static boolean decidesTheCharset(final String owner, final String name, final String parameters) {
        if (parameters.contains("Ljava/nio/charset/Charset;")
                || parameters.contains("Ljava/nio/charset/CharsetDecoder;")
                || parameters.contains("Ljava/nio/charset/CharsetEncoder;")) {
            return false;
        }
        if (parameters.contains("Ljava/io/Writer;") || parameters.contains("Ljava/io/Reader;")) {
            return false;
        }
        if ("java/lang/String".equals(owner)) {
            return "getBytes".equals(name) ? parameters.isEmpty() : parameters.startsWith("[B");
        }
        if ("java/io/ByteArrayOutputStream".equals(owner)) {
            return parameters.isEmpty();
        }
        return true;
    }
}
