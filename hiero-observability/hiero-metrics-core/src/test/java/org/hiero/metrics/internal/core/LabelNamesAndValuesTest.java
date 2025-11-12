// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class LabelNamesAndValuesTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void testEmpty(int index) {
        LabelNamesAndValues labelNamesAndValues = new LabelNamesAndValues();

        assertThat(labelNamesAndValues.size()).isEqualTo(0);
        assertThatThrownBy(() -> labelNamesAndValues.get(index)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void testSingleValue() {
        LabelNamesAndValues labelNamesAndValues = new LabelNamesAndValues("name", "value");

        assertThat(labelNamesAndValues.size()).isEqualTo(1);
        assertThat(labelNamesAndValues.get(0)).isEqualTo("value");
        assertThatThrownBy(() -> labelNamesAndValues.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> labelNamesAndValues.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void testTwoValues() {
        LabelNamesAndValues labelNamesAndValues = new LabelNamesAndValues("name", "value", "1", "2");

        assertThat(labelNamesAndValues.size()).isEqualTo(2);
        assertThat(labelNamesAndValues.get(0)).isEqualTo("value");
        assertThat(labelNamesAndValues.get(1)).isEqualTo("2");
        assertThatThrownBy(() -> labelNamesAndValues.get(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> labelNamesAndValues.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "name1,value1", "name1,name1", "1,2,3,4", "1,2,3,4,5,6,7,8,9,10"})
    void testEqualsAndHashCodeIsSame(String namesAndValuesString) {
        String[] namesAndValues = namesAndValuesString.split(",");

        LabelNamesAndValues instance1 = new LabelNamesAndValues(namesAndValues);
        LabelNamesAndValues instance2 = new LabelNamesAndValues(namesAndValues);

        int hashCode = instance1.hashCode();
        assertThat(hashCode).as("Hash code is repetitive").isEqualTo(instance1.hashCode());

        assertThat(instance1).as("Objects are equal").isEqualTo(instance2);
        assertThat(instance1.hashCode()).as("Hash code is the same").isEqualTo(instance2.hashCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"!=name,value", "name,value1!=name,value2", "name,value!=name,value,name1,value1"})
    void testEqualsAndHashCodeIsDifferent(String comparison) {
        String[] pair = comparison.split("!=");

        LabelValues values1 = new LabelNamesAndValues(pair[0].split(","));
        LabelValues values2 = new LabelNamesAndValues(pair[1].split(","));

        assertThat(values1).isNotEqualTo(values2);
        assertThat(values1.hashCode()).isNotEqualTo(values2.hashCode());
    }

    @Test
    void testToString() {
        LabelNamesAndValues labelNamesAndValues = new LabelNamesAndValues("name1", "1", "name2", "2");
        assertThat(labelNamesAndValues.toString()).isEqualTo("LabelsValues[1,2]");
    }
}
