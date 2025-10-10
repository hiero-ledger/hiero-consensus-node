// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import static com.swirlds.state.lifecycle.StateMetadata.validateIdentifier;
import static com.swirlds.state.lifecycle.StateMetadata.validateServiceName;
import static com.swirlds.state.lifecycle.StateMetadata.validateStateKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.swirlds.state.test.fixtures.StateTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class StateMetadataTest extends StateTestBase {
    @Test
    @DisplayName("Validating a null service name throws an NPE")
    void nullServiceNameThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> validateServiceName(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Validating a service name with no characters throws an exception")
    void emptyServiceNameThrows() {
        assertThatThrownBy(() -> validateServiceName(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service name");
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.state.test.fixtures.TestArgumentUtils#illegalIdentifiers")
    @DisplayName("Service Names with illegal characters throw an exception")
    void invalidServiceNameThrows(final String serviceName) {
        assertThatThrownBy(() -> validateServiceName(serviceName)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.state.test.fixtures.TestArgumentUtils#legalIdentifiers")
    @DisplayName("Service names with legal characters are valid")
    void validServiceNameWorks(final String serviceName) {
        assertThat(validateServiceName(serviceName)).isEqualTo(serviceName);
    }

    @Test
    @DisplayName("Validating a null state key throws an NPE")
    void nullStateKeyThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> validateStateKey(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Validating a state key with no characters throws an exception")
    void emptyStateKeyThrows() {
        assertThatThrownBy(() -> validateStateKey(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state key");
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.state.test.fixtures.TestArgumentUtils#illegalIdentifiers")
    @DisplayName("State keys with illegal characters throw an exception")
    void invalidStateKeyThrows(final String stateKey) {
        assertThatThrownBy(() -> validateStateKey(stateKey)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.state.test.fixtures.TestArgumentUtils#legalIdentifiers")
    @DisplayName("State keys with legal characters are valid")
    void validStateKeyWorks(final String stateKey) {
        assertThat(validateServiceName(stateKey)).isEqualTo(stateKey);
    }

    @Test
    @DisplayName("Validating a null identifier throws an NPE")
    void nullIdentifierThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> validateIdentifier(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Validating an identifier with no characters throws an exception")
    void emptyIdentifierThrows() {
        assertThatThrownBy(() -> validateIdentifier("")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.state.test.fixtures.TestArgumentUtils#illegalIdentifiers")
    @DisplayName("Identifiers with illegal characters throw an exception")
    void invalidIdentifierThrows(final String identifier) {
        assertThatThrownBy(() -> validateIdentifier(identifier)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.state.test.fixtures.TestArgumentUtils#legalIdentifiers")
    @DisplayName("Identifiers with legal characters are valid")
    void validIdentifierWorks(final String identifier) {
        assertThat(validateIdentifier(identifier)).isEqualTo(identifier);
    }

    @Test
    @DisplayName("`computeLabel` with a null service name throws")
    void computeLabel_nullServiceNameThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> StateMetadata.computeLabel(null, FRUIT_STATE_KEY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("`computeLabel` with a null state key throws")
    void computeLabel_nullStateKeyThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> StateMetadata.computeLabel(FIRST_SERVICE, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * NOTE: This test may look silly, because it literally does what is in the source code. But
     * this is actually very important! This computation MUST NOT CHANGE. If any change is made in
     * the sources, it will cause this test to fail. That will cause the engineer to look at this
     * test and SEE THIS NOTE. And then realize, they CANNOT MAKE THIS CHANGE.
     */
    @Test
    @DisplayName("`computeLabel` is always serviceName.stateKey")
    void computeLabel() {
        assertThat(StateMetadata.computeLabel(FIRST_SERVICE, FRUIT_STATE_KEY))
                .isEqualTo(FIRST_SERVICE + "." + FRUIT_STATE_KEY);
    }
}
