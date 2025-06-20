// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NameConvertTest {

    @Test
    void testNameConverter() {
        assertThat(NameConverter.fix("Hello_World:42")).isEqualTo("Hello_World:42");
        assertThat(NameConverter.fix("")).isEmpty();
        assertThat(NameConverter.fix(".- /%")).isEqualTo(":___per_Percent");
        assertThatThrownBy(() -> NameConverter.fix(null)).isInstanceOf(NullPointerException.class);
    }
}
