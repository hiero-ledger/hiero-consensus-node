// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ZonedDateTimeConverterTest {

    @Test
    public void testNull() {
        // given
        final ZonedDateTimeConverter converter = new ZonedDateTimeConverter();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    public void test1() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("app.start", "1994-11-05T08:15:30-05:00"))
                .build();

        // when
        final ZonedDateTime zonedDateTime = configuration.getValue("app.start", ZonedDateTime.class);

        // then
        Assertions.assertNotNull(zonedDateTime);
        Assertions.assertEquals(1994, zonedDateTime.getYear());
        Assertions.assertEquals(Month.NOVEMBER, zonedDateTime.getMonth());
        Assertions.assertEquals(5, zonedDateTime.getDayOfMonth());
        Assertions.assertEquals(8, zonedDateTime.getHour());
        Assertions.assertEquals(15, zonedDateTime.getMinute());
        Assertions.assertEquals(30, zonedDateTime.getSecond());
        Assertions.assertEquals(0, zonedDateTime.getNano());
        Assertions.assertEquals(ZoneOffset.ofHours(-5), zonedDateTime.getZone());

        final ZonedDateTime converted = zonedDateTime.withZoneSameInstant(ZoneId.of("UTC"));
        Assertions.assertEquals(1994, converted.getYear());
        Assertions.assertEquals(Month.NOVEMBER, converted.getMonth());
        Assertions.assertEquals(5, converted.getDayOfMonth());
        Assertions.assertEquals(13, converted.getHour());
        Assertions.assertEquals(15, converted.getMinute());
        Assertions.assertEquals(30, converted.getSecond());
        Assertions.assertEquals(0, converted.getNano());
        Assertions.assertEquals(ZoneId.of("UTC"), converted.getZone());
    }

    @Test
    public void test2() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("app.start", "1994-11-05T08:15:30Z"))
                .build();

        // when
        final ZonedDateTime zonedDateTime = configuration.getValue("app.start", ZonedDateTime.class);

        // then
        Assertions.assertNotNull(zonedDateTime);
        Assertions.assertEquals(1994, zonedDateTime.getYear());
        Assertions.assertEquals(Month.NOVEMBER, zonedDateTime.getMonth());
        Assertions.assertEquals(5, zonedDateTime.getDayOfMonth());
        Assertions.assertEquals(8, zonedDateTime.getHour());
        Assertions.assertEquals(15, zonedDateTime.getMinute());
        Assertions.assertEquals(30, zonedDateTime.getSecond());
        Assertions.assertEquals(0, zonedDateTime.getNano());
        Assertions.assertEquals(ZoneOffset.UTC, zonedDateTime.getZone());

        final ZonedDateTime converted = zonedDateTime.withZoneSameInstant(ZoneId.of("UTC"));
        Assertions.assertEquals(1994, converted.getYear());
        Assertions.assertEquals(Month.NOVEMBER, converted.getMonth());
        Assertions.assertEquals(5, converted.getDayOfMonth());
        Assertions.assertEquals(8, converted.getHour());
        Assertions.assertEquals(15, converted.getMinute());
        Assertions.assertEquals(30, converted.getSecond());
        Assertions.assertEquals(0, converted.getNano());
        Assertions.assertEquals(ZoneId.of("UTC"), converted.getZone());
    }

    @Test
    public void test3() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("app.start", "1994-11-05T08:15:30-05:00[US/Eastern]"))
                .build();

        // when
        final ZonedDateTime zonedDateTime = configuration.getValue("app.start", ZonedDateTime.class);

        // then
        Assertions.assertNotNull(zonedDateTime);
        Assertions.assertEquals(1994, zonedDateTime.getYear());
        Assertions.assertEquals(Month.NOVEMBER, zonedDateTime.getMonth());
        Assertions.assertEquals(5, zonedDateTime.getDayOfMonth());
        Assertions.assertEquals(8, zonedDateTime.getHour());
        Assertions.assertEquals(15, zonedDateTime.getMinute());
        Assertions.assertEquals(30, zonedDateTime.getSecond());
        Assertions.assertEquals(0, zonedDateTime.getNano());
        Assertions.assertEquals(ZoneId.of("US/Eastern"), zonedDateTime.getZone());

        final ZonedDateTime converted = zonedDateTime.withZoneSameInstant(ZoneId.of("UTC"));
        Assertions.assertEquals(1994, converted.getYear());
        Assertions.assertEquals(Month.NOVEMBER, converted.getMonth());
        Assertions.assertEquals(5, converted.getDayOfMonth());
        Assertions.assertEquals(13, converted.getHour());
        Assertions.assertEquals(15, converted.getMinute());
        Assertions.assertEquals(30, converted.getSecond());
        Assertions.assertEquals(0, converted.getNano());
        Assertions.assertEquals(ZoneId.of("UTC"), converted.getZone());
    }
}
