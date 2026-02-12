// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.constructable.constructables.scannable.ConstructableExample;
import com.swirlds.common.constructable.constructables.scannable.subpackage.SubpackageConstructable;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ClassIdFormatter;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.constructable.ConstructableRegistryFactory;
import org.hiero.base.constructable.ConstructorRegistry;
import org.hiero.base.constructable.NoArgsConstructor;
import org.hiero.base.constructable.RuntimeConstructable;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConstructableRegistryTest {
    private final ConstructableRegistry mainReg;
    private final ConstructorRegistry<NoArgsConstructor> noArgsRegistry;

    public ConstructableRegistryTest() throws ConstructableRegistryException {
        mainReg = ConstructableRegistryFactory.createConstructableRegistry();

        final long start = System.currentTimeMillis();
        // find all RuntimeConstructable classes and register their constructors
        mainReg.registerConstructable(new ClassConstructorPair(
            ConstructableExample.class, ConstructableExample::new));
        System.out.printf(
                "Time taken to register all RuntimeConstructables: %dms\n", System.currentTimeMillis() - start);
        noArgsRegistry = mainReg.getRegistry(NoArgsConstructor.class);
    }

    @Test
    @Order(1)
    void testNoArgsClass() {
        // checks whether the object will be constructed and if the type is correct
        final RuntimeConstructable r =
                noArgsRegistry.getConstructor(ConstructableExample.CLASS_ID).get();
        assertTrue(r instanceof ConstructableExample);

        // checks the objects class ID
        assertEquals(ConstructableExample.CLASS_ID, r.getClassId());
    }

    @Test
    @Order(2)
    void testClassIdClash() throws ConstructableRegistryException {
        // Test the scenario of a class ID clash
        final long oldClassId = ConstructableExample.CLASS_ID;
        ConstructableExample.CLASS_ID = SubpackageConstructable.CLASS_ID;
        mainReg.registerConstructable(new ClassConstructorPair(SubpackageConstructable.class, SubpackageConstructable::new));
        assertThrows(ConstructableRegistryException.class,
                () -> mainReg.registerConstructable(new ClassConstructorPair(ConstructableExample.class, ConstructableExample::new)));
        // return the old CLASS_ID
        ConstructableExample.CLASS_ID = oldClassId;
        // now it should be fine again
        mainReg.registerConstructable(new ClassConstructorPair(ConstructableExample.class, ConstructableExample::new));
    }

    @Test
    @Order(3)
    void testInvalidClassId() {
        // ask for a class ID that does not exist
        assertNull(noArgsRegistry.getConstructor(0));
    }

    @Test
    @Order(4)
    void testClassIdFormatting() {
        assertEquals("0(0x0)", ClassIdFormatter.classIdString(0), "generated class ID string should match expected");

        assertEquals(
                "123456789(0x75BCD15)",
                ClassIdFormatter.classIdString(123456789),
                "generated class ID string should match expected");

        assertEquals(
                "-123456789(0xFFFFFFFFF8A432EB)",
                ClassIdFormatter.classIdString(-123456789),
                "generated class ID string should match expected");

        assertEquals(
                "org.hiero.base.crypto.Hash:-854880720348154850(0xF422DA83A251741E)",
                ClassIdFormatter.classIdString(new Hash()),
                "generated class ID string should match expected");
    }
}
