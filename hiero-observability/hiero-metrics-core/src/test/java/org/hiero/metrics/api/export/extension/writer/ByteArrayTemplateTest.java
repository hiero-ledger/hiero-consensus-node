// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Iterator;
import org.junit.jupiter.api.Test;

public class ByteArrayTemplateTest {

    @Test
    void testEmpty() {
        Iterator<byte[]> iterator = ByteArrayTemplate.builder().build().iterator();
        assertThat(iterator.hasNext()).isFalse();
    }

    @Test
    void testEmptyAppends() {
        Iterator<byte[]> iterator = ByteArrayTemplate.builder()
                .append(new byte[0])
                .appendUtf8("")
                .build()
                .iterator();
        assertThat(iterator.hasNext()).isFalse();
    }

    @Test
    void testNoPlaceholders() {
        ByteArrayTemplate template = ByteArrayTemplate.builder()
                .appendUtf8("Hello, ")
                .append(new byte[] {87, 111, 114, 108, 100, 33}) // "World!"
                .build();

        verifyIterator(template.iterator(), "Hello, World!");
    }

    @Test
    void testOnlyPlaceholders() {
        ByteArrayTemplate template =
                ByteArrayTemplate.builder().addPlaceholder().addPlaceholder().build();

        Iterator<byte[]> iterator = template.iterator("First".getBytes(), "Second".getBytes());

        verifyIterator(iterator, "First", "Second");
    }

    @Test
    void testStaticAndPlaceholders() {
        ByteArrayTemplate template = ByteArrayTemplate.builder()
                .addPlaceholder()
                .appendUtf8("AA")
                .addPlaceholder()
                .addPlaceholder()
                .appendUtf8("BB")
                .appendUtf8("CC")
                .addPlaceholder()
                .build();

        Iterator<byte[]> iterator = template.iterator("1".getBytes(), "2".getBytes(), "3".getBytes(), "4".getBytes());
        verifyIterator(iterator, "1", "AA", "2", "3", "BBCC", "4");

        iterator = template.iterator(4, "1".getBytes(), "2".getBytes(), "3".getBytes(), "4".getBytes(), "5".getBytes());
        verifyIterator(iterator, "1", "AA", "2", "3", "BBCC", "4");
    }

    @Test
    void testWrongPlaceholderCount() {
        ByteArrayTemplate template =
                ByteArrayTemplate.builder().appendUtf8("data").addPlaceholder().build();

        assertThatThrownBy(template::iterator)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Number of variables is not equal to number of placeholders");

        assertThatThrownBy(() -> template.iterator("1".getBytes(), "2".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Number of variables is not equal to number of placeholders");

        assertThatThrownBy(() -> template.iterator(2, "1".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vars count is greater than variables array length");
    }

    private void verifyIterator(Iterator<byte[]> iterator, String... expectedParts) {
        for (String expectedPart : expectedParts) {
            assertThat(iterator.hasNext()).isTrue();
            assertThat(new String(iterator.next())).isEqualTo(expectedPart);
        }
        assertThat(iterator.hasNext()).isFalse();
    }
}
