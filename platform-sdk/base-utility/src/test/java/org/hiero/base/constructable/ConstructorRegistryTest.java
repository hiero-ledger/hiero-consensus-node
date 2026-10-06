// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable;

import org.hiero.base.constructable.constructables.NoArgsConstructable;
import org.hiero.base.constructable.constructables.NoArgsConstructableWithAnnotation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ConstructorRegistryTest {
    @Test
    void noArgsConstructorTest() throws ConstructableRegistryException {
        // given
        final ConstructorRegistry<NoArgsConstructor> cr =
                ConstructableRegistryFactory.createConstructorRegistry(NoArgsConstructor.class);
        cr.registerConstructable(NoArgsConstructable.class);
        cr.registerConstructable(NoArgsConstructableWithAnnotation.class);
        // when
        final NoArgsConstructor constructor = cr.getConstructor(NoArgsConstructable.CLASS_ID);
        final RuntimeConstructable constructable = constructor.get();
        final NoArgsConstructor constructorAnnotated = cr.getConstructor(NoArgsConstructableWithAnnotation.CLASS_ID);
        final RuntimeConstructable constructableAnnotated = constructorAnnotated.get();
        // then
        Assertions.assertEquals(NoArgsConstructableWithAnnotation.class, constructableAnnotated.getClass());
        Assertions.assertEquals(NoArgsConstructableWithAnnotation.CLASS_ID, constructableAnnotated.getClassId());
        Assertions.assertEquals(NoArgsConstructable.class, constructable.getClass());
        Assertions.assertEquals(NoArgsConstructable.CLASS_ID, constructable.getClassId());
    }
}
