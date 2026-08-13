// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor.antlr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.config.processor.ConfigDataPropertyDefinition;
import com.swirlds.config.processor.ConfigDataRecordDefinition;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins how {@link AntlrConfigRecordParser} reports the type of a property, which ends up in the generated
 * documentation. The parser only sees one source file, so it has to resolve a type name through the imports of that
 * file rather than through the element model of the compiler.
 */
class AntlrConfigRecordParserTest {

    private static final String SOURCE = """
            package test.cfg;

            import com.swirlds.config.api.ConfigData;
            import java.time.Duration;
            import java.util.List;
            import java.util.Set;

            @ConfigData("test")
            public record TypesConfig(
                    int primitive,
                    String javaLangType,
                    Duration importedType,
                    List<String> genericType,
                    Set<Duration> genericTypeOfImportedType,
                    java.nio.file.Path alreadyQualifiedType,
                    SamePackageRecord samePackageType) {}
            """;

    @Test
    void primitiveTypeIsReportedAsWritten() {
        assertEquals("int", typesByProperty().get("test.primitive"));
    }

    @Test
    void javaLangTypeIsQualified() {
        // a java.lang type needs no import, so it can only be resolved by looking it up
        assertEquals("java.lang.String", typesByProperty().get("test.javaLangType"));
    }

    @Test
    void importedTypeIsResolvedThroughTheImports() {
        assertEquals("java.time.Duration", typesByProperty().get("test.importedType"));
    }

    @Test
    void alreadyQualifiedTypeIsLeftAlone() {
        assertEquals("java.nio.file.Path", typesByProperty().get("test.alreadyQualifiedType"));
    }

    @Test
    void rawTypeOfAGenericTypeIsResolved() {
        // the text of the component is "List<String>", which matches no import, so the type arguments have to be split
        // off before the raw type is resolved. The arguments themselves are reported as they are written.
        assertEquals("java.util.List<String>", typesByProperty().get("test.genericType"));
        assertEquals("java.util.Set<Duration>", typesByProperty().get("test.genericTypeOfImportedType"));
    }

    @Test
    void typeThatIsNeitherImportedNorJavaLangIsLeftAlone() {
        // a record declared in the same package needs no import. Reporting it as "java.lang.SamePackageRecord" would be
        // plainly wrong, which is what made a nested config data object in the same package unusable.
        assertEquals("SamePackageRecord", typesByProperty().get("test.samePackageType"));
    }

    private static Map<String, String> typesByProperty() {
        final List<ConfigDataRecordDefinition> definitions = AntlrConfigRecordParser.parse(SOURCE);
        assertEquals(1, definitions.size(), "exactly one config data record is declared");
        return definitions.getFirst().propertyDefinitions().stream()
                .collect(Collectors.toMap(
                        ConfigDataPropertyDefinition::name, ConfigDataPropertyDefinition::type, (a, b) -> a));
    }
}
