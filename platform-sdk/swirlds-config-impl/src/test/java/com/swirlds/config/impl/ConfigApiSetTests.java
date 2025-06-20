// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigApiSetTests {

    @Test
    void readSetProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource().withIntegerValues("testNumbers", List.of(1, 2, 3)))
                .build();

        // when
        final Set<Integer> values = configuration.getValueSet("testNumbers", Integer.class);

        // then
        assertEquals(3, values.size(), "A property that is defined as set should be parsed correctly");
        assertTrue(values.contains(1), "A property that is defined as set should contain the defined values");
        assertTrue(values.contains(2), "A property that is defined as set should contain the defined values");
        assertTrue(values.contains(3), "A property that is defined as set should contain the defined values");
    }

    @Test
    void readSetPropertyWithOneEntry() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testNumbers", 123))
                .build();

        // when
        final Set<Integer> values = configuration.getValueSet("testNumbers", Integer.class);

        // then
        assertEquals(1, values.size(), "A property that is defined as set should be parsed correctly");
        assertTrue(values.contains(123), "A property that is defined as set should contain the defined values");
    }

    @Test
    void readBadSetProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testNumbers", "1,2,   3,4"))
                .build();

        // then
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.getValueSet("testNumbers", Integer.class),
                "given set property should not be parsed correctly");
    }

    @Test
    void readDefaultSetProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // when
        final Set<Integer> values = configuration.getValueSet("testNumbers", Integer.class, Set.of(6, 7, 8));

        // then
        assertEquals(3, values.size(), "The default value should be used since no value is defined by the config");
        assertTrue(values.contains(6), "Should be part of the set since it is part of the default");
        assertTrue(values.contains(7), "Should be part of the set since it is part of the default");
        assertTrue(values.contains(8), "Should be part of the set since it is part of the default");
    }

    @Test
    void readNullDefaultSetProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // when
        final Set<Integer> values = configuration.getValueSet("testNumbers", Integer.class, null);

        // then
        assertNull(values, "Null should be a valid default value");
    }

    @Test
    void checkSetPropertyImmutable() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testNumbers", "1,2,3"))
                .build();

        // when
        final Set<Integer> values = configuration.getValueSet("testNumbers", Integer.class);

        // then
        assertThrows(
                UnsupportedOperationException.class, () -> values.add(10), "Set properties should always be immutable");
    }

    @Test
    void testNotDefinedEmptySet() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // then
        assertThrows(NoSuchElementException.class, () -> configuration.getValueSet("sample.list"));
        assertThrows(NoSuchElementException.class, () -> configuration.getValueSet("sample.list", String.class));
        assertThrows(NoSuchElementException.class, () -> configuration.getValueSet("sample.list", Integer.class));
    }

    /** Verify if the iteration order of the set is the same as the order of items in the list. */
    private static <T> void verifyIterationOrder(final Set<T> set, final List<T> list) {
        assertEquals(list.size(), set.size(), "The list size should be equal to the set size");

        final Iterator<T> setIterator = set.iterator();
        final Iterator<T> listIterator = list.iterator();

        final List<T> actualOrder = new ArrayList<>(list.size());

        while (listIterator.hasNext()) {
            assertEquals(listIterator.hasNext(), setIterator.hasNext());

            final T next = setIterator.next();
            actualOrder.add(next);

            assertEquals(
                    listIterator.next(),
                    next,
                    "The set iteration order should be stable. Expected: " + list + ", actual so far: " + actualOrder);
        }
        assertFalse(setIterator.hasNext());
    }

    @Test
    void checkIntegerSetStable() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testNumbers", "3,1,2"))
                .build();

        // when
        final Set<Integer> values = configuration.getValueSet("testNumbers", Integer.class);

        // then
        verifyIterationOrder(values, List.of(1, 2, 3));
    }

    @Test
    void checkStringSetStable() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testStrings", "x,a,d"))
                .build();

        // when
        final Set<String> values = configuration.getValueSet("testStrings", String.class);

        // then
        verifyIterationOrder(values, List.of("a", "d", "x"));
    }

    @Test
    void checkEnumSetStable() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testEnums", "x,a,d"))
                .build();
        enum TestEnum {
            d,
            x,
            a
        }

        // when
        final Set<TestEnum> values = configuration.getValueSet("testEnums", TestEnum.class);

        // then
        verifyIterationOrder(values, List.of(TestEnum.d, TestEnum.x, TestEnum.a));
    }

    @ConfigData("settest")
    public record SetTestConfig(@ConfigProperty(value = "testSet", defaultValue = "666,404,500") Set<Long> testSet) {}

    @Test
    void checkSetInRecord() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("settest.testSet", "333,111,222"))
                .withConfigDataType(SetTestConfig.class)
                .build();

        // when
        final SetTestConfig setTestConfig = configuration.getConfigData(SetTestConfig.class);

        // then
        verifyIterationOrder(setTestConfig.testSet(), List.of(111L, 222L, 333L));
    }

    @Test
    void checkInetAddressSet() throws UnknownHostException {
        // InetAddress is not Comparable:

        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("setinetaddresstest.testInetAddressSet", "1.1.1.1,2.2.2.2"))
                .build();

        final List<InetAddress> expectedOrder = List.of(
                InetAddress.getByAddress(new byte[] {1, 1, 1, 1}), InetAddress.getByAddress(new byte[] {2, 2, 2, 2}));

        // Case #1: as a List
        // when
        final List<InetAddress> list =
                configuration.getValues("setinetaddresstest.testInetAddressSet", InetAddress.class);

        // then
        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals(expectedOrder, list);

        // Case #2: as a Set
        // when/then
        final Set<InetAddress> set =
                configuration.getValueSet("setinetaddresstest.testInetAddressSet", InetAddress.class);
        verifyIterationOrder(set, expectedOrder);
    }

    @ConfigData("setinetaddresstest")
    public record SetInetAddressTestConfig(
            @ConfigProperty(value = "testInetAddressSet", defaultValue = "1.1.1.1,2.2.2.2") Set<InetAddress> testSet) {}

    @Test
    void checkInetAddressSetInRecord() throws UnknownHostException {
        final List<InetAddress> expectedOrder = List.of(
                InetAddress.getByAddress(new byte[] {1, 1, 1, 1}), InetAddress.getByAddress(new byte[] {2, 2, 2, 2}));

        // InetAddress is not Comparable:
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("setinetaddresstest.testInetAddressSet", "1.1.1.1,2.2.2.2"))
                .withConfigDataType(SetInetAddressTestConfig.class)
                .build();

        // case 1: getValueSet
        final Set<InetAddress> set =
                configuration.getValueSet("setinetaddresstest.testInetAddressSet", InetAddress.class);
        verifyIterationOrder(set, expectedOrder);

        // case 2: getConfigData as record
        final SetInetAddressTestConfig configData = configuration.getConfigData(SetInetAddressTestConfig.class);
        verifyIterationOrder(configData.testSet(), expectedOrder);
    }
}
