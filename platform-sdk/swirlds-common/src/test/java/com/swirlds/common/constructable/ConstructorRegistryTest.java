// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable;

import com.swirlds.common.constructable.constructables.NoArgsConstructable;
import com.swirlds.common.constructable.constructables.NoArgsConstructableWithAnnotation;
import com.swirlds.common.constructable.constructables.scannable.ConstructableRecord;
import com.swirlds.common.constructable.constructables.scannable.PrimitiveAndObjectConstructable;
import com.swirlds.common.constructable.constructables.scannable.StringConstructable;
import com.swirlds.common.constructable.constructors.BadReturnTypeConstructor;
import com.swirlds.common.constructable.constructors.MultipleMethodsConstructor;
import com.swirlds.common.constructable.constructors.NotInterfaceConstructor;
import com.swirlds.common.constructable.constructors.PrimitiveAndObjectConstructor;
import com.swirlds.common.constructable.constructors.RecordConstructor;
import com.swirlds.common.constructable.constructors.StringConstructor;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.constructable.ConstructableRegistryFactory;
import org.hiero.base.constructable.ConstructorRegistry;
import org.hiero.base.constructable.NoArgsConstructor;
import org.hiero.base.constructable.RuntimeConstructable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ConstructorRegistryTest {
    @Test
    void stringConstructorTest() throws ConstructableRegistryException {
        // given
        final ConstructorRegistry<StringConstructor> cr =
                ConstructableRegistryFactory.createConstructorRegistry(StringConstructor.class);
        cr.registerConstructable(StringConstructable.class);
        final StringConstructor constructor = cr.getConstructor(StringConstructable.CLASS_ID);
        // when
        final String string = "a random string";
        final StringConstructable gc = constructor.construct(string);
        // then
        Assertions.assertEquals(string, gc.getString());
    }

    @Test
    void recordTest() throws ConstructableRegistryException {
        // given
        final ConstructorRegistry<RecordConstructor> cr =
                ConstructableRegistryFactory.createConstructorRegistry(RecordConstructor.class);
        cr.registerConstructable(ConstructableRecord.class);
        final RecordConstructor constructor = cr.getConstructor(ConstructableRecord.CLASS_ID);
        // when
        final String string = "a random string";
        final ConstructableRecord gc = constructor.construct(string);
        // then
        Assertions.assertEquals(string, gc.string());
    }

    @Test
    void customConstructorTest() throws ConstructableRegistryException {
        // given
        final ConstructorRegistry<StringConstructor> cr =
                ConstructableRegistryFactory.createConstructorRegistry(StringConstructor.class);
        // when
        final String provided = "provided string";
        final String overridden = "overridden string";
        cr.registerConstructable(StringConstructable.class, s -> new StringConstructable(overridden));
        final StringConstructor constructor = cr.getConstructor(StringConstructable.CLASS_ID);
        final StringConstructable gc = constructor.construct(provided);
        // then
        Assertions.assertEquals(overridden, gc.getString());
    }

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

    @Test
    void primitiveArgsConstructorTest() throws ConstructableRegistryException {
        // given
        final ConstructorRegistry<PrimitiveAndObjectConstructor> cr =
                ConstructableRegistryFactory.createConstructorRegistry(PrimitiveAndObjectConstructor.class);
        cr.registerConstructable(PrimitiveAndObjectConstructable.class);
        // when
        final Long first = 1L;
        final int second = 2;
        final PrimitiveAndObjectConstructable constructable =
                cr.getConstructor(PrimitiveAndObjectConstructable.CLASS_ID).create(first, second);
        // then
        Assertions.assertEquals(first, constructable.getFirst());
        Assertions.assertEquals(second, constructable.getSecond());
    }

    @Test
    void badConstructorType() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ConstructableRegistryFactory.createConstructorRegistry(NotInterfaceConstructor.class));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ConstructableRegistryFactory.createConstructorRegistry(MultipleMethodsConstructor.class));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ConstructableRegistryFactory.createConstructorRegistry(BadReturnTypeConstructor.class));
    }
}
