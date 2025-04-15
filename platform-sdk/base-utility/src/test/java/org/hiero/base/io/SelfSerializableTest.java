// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.test.fixtures.io.InputOutputStream;
import java.io.IOException;
import org.hiero.base.io.exceptions.InvalidVersionException;
import org.hiero.base.utility.test.fixtures.io.SelfSerializableExample;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SelfSerializableTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Tag(TestComponentTags.IO)
    @DisplayName("Serialize Deserialize")
    void serializeDeserialize(boolean includeClassId) throws IOException {
        SelfSerializableExample serializable = new SelfSerializableExample(123, "a simple string");

        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeSerializable(serializable, includeClassId);
        io.startReading();
        SelfSerializableExample fromStream =
                io.getInput().readSerializable(includeClassId, SelfSerializableExample::new);

        assertEquals(serializable, fromStream);
        io.close();
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("Serialize Deserialize")
    void deserializeInvalidVersions() throws IOException {

        SelfSerializableExample serializable = new SelfSerializableExample(123, "a simple string");

        // Deserialize with a version number below the minimum
        serializable.setVersion(0);
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeSerializable(serializable, true);
        io.startReading();
        assertThrows(InvalidVersionException.class, () -> io.getInput()
                .readSerializable(true, SelfSerializableExample::new));

        // Deserialize with a version number above the current
        serializable.setVersion(1234);
        InputOutputStream io2 = new InputOutputStream();
        io2.getOutput().writeSerializable(serializable, true);
        io2.startReading();
        assertThrows(InvalidVersionException.class, () -> io2.getInput()
                .readSerializable(true, SelfSerializableExample::new));
    }
}
