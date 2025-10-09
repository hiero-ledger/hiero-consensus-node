// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class LabelNamesAndValuesTest {

    @Test
    public void testEmpty() {
        LabelNamesAndValues labelNamesAndValues = new LabelNamesAndValues();

        assertThat(labelNamesAndValues.size()).isEqualTo(0);
        assertThatThrownBy(() -> labelNamesAndValues.get(0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> labelNamesAndValues.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> labelNamesAndValues.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    public void testSingleValue() {
        LabelNamesAndValues labelNamesAndValues = new LabelNamesAndValues("name", "value");

        assertThat(labelNamesAndValues.size()).isEqualTo(1);
        assertThat(labelNamesAndValues.get(0)).isEqualTo("value");
        assertThatThrownBy(() -> labelNamesAndValues.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> labelNamesAndValues.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    public void testTwoValues() {
        LabelNamesAndValues labelNamesAndValues = new LabelNamesAndValues("name", "value", "1", "2");

        assertThat(labelNamesAndValues.size()).isEqualTo(2);
        assertThat(labelNamesAndValues.get(0)).isEqualTo("value");
        assertThat(labelNamesAndValues.get(1)).isEqualTo("2");
        assertThatThrownBy(() -> labelNamesAndValues.get(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> labelNamesAndValues.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "name1,value1", "name1,name1", "1,2,3,4", "1,2,3,4,5,6,7,8,9,10"})
    public void testEqualsAndHashCodeIsSame(String namesAndValuesString) {
        String[] namesAndValues = namesAndValuesString.split(",");

        LabelNamesAndValues instance1 = new LabelNamesAndValues(namesAndValues);
        LabelNamesAndValues instance2 = new LabelNamesAndValues(namesAndValues);

        int hashCode = instance1.hashCode();
        assertThat(hashCode).as("Hash code is not repetitive").isEqualTo(instance1.hashCode());

        assertThat(instance1).as("Objects are not equal").isEqualTo(instance2);
        assertThat(instance1.hashCode()).as("Hash code is not the same").isEqualTo(instance2.hashCode());
    }
}
