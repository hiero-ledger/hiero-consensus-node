// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.config.impl.converters.BigDecimalConverter;
import com.swirlds.config.impl.converters.BigIntegerConverter;
import com.swirlds.config.impl.converters.BooleanConverter;
import com.swirlds.config.impl.converters.ByteConverter;
import com.swirlds.config.impl.converters.ChronoUnitConverter;
import com.swirlds.config.impl.converters.DoubleConverter;
import com.swirlds.config.impl.converters.DurationConverter;
import com.swirlds.config.impl.converters.EnumConverter;
import com.swirlds.config.impl.converters.FileConverter;
import com.swirlds.config.impl.converters.FloatConverter;
import com.swirlds.config.impl.converters.InetAddressConverter;
import com.swirlds.config.impl.converters.IntegerConverter;
import com.swirlds.config.impl.converters.LongConverter;
import com.swirlds.config.impl.converters.PathConverter;
import com.swirlds.config.impl.converters.ShortConverter;
import com.swirlds.config.impl.converters.StringConverter;
import com.swirlds.config.impl.converters.UriConverter;
import com.swirlds.config.impl.converters.UrlConverter;
import com.swirlds.config.impl.converters.ZonedDateTimeConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

class ConverterService implements ConfigLifecycle {
    private final Map<Class<?>, ConfigConverter<?>> converters;

    private boolean initialized = false;

    /**
     * constant converter for strings.
     */
    private static final ConfigConverter<String> STRING_CONVERTER = new StringConverter();

    private static final ConfigConverter<Integer> INTEGER_CONVERTER = new IntegerConverter();

    private static final ConfigConverter<Long> LONG_CONVERTER = new LongConverter();

    private static final ConfigConverter<Double> DOUBLE_CONVERTER = new DoubleConverter();

    private static final ConfigConverter<Float> FLOAT_CONVERTER = new FloatConverter();

    private static final ConfigConverter<Short> SHORT_CONVERTER = new ShortConverter();

    private static final ConfigConverter<Byte> BYTE_CONVERTER = new ByteConverter();

    private static final ConfigConverter<Boolean> BOOLEAN_CONVERTER = new BooleanConverter();

    private static final ConfigConverter<BigDecimal> BIG_DECIMAL_CONVERTER = new BigDecimalConverter();

    private static final ConfigConverter<BigInteger> BIG_INTEGER_CONVERTER = new BigIntegerConverter();

    private static final ConfigConverter<URL> URL_CONVERTER = new UrlConverter();

    private static final ConfigConverter<URI> URI_CONVERTER = new UriConverter();

    private static final ConfigConverter<Path> PATH_CONVERTER = new PathConverter();

    private static final ConfigConverter<File> FILE_CONVERTER = new FileConverter();

    private static final ConfigConverter<ZonedDateTime> ZONED_DATE_TIME_CONVERTER = new ZonedDateTimeConverter();

    private static final ConfigConverter<Duration> DURATION_CONVERTER = new DurationConverter();

    private static final ConfigConverter<ChronoUnit> CHRONO_UNIT_CONVERTER = new ChronoUnitConverter();

    private static final ConfigConverter<InetAddress> INET_ADDRESS_CONFIG_CONVERTER = new InetAddressConverter();

    ConverterService() {
        this.converters = new ConcurrentHashMap<>();
    }

    @Nullable
    <T> T convert(@Nullable final String value, @NonNull final Class<T> targetClass) {
        throwIfNotInitialized();
        Objects.requireNonNull(targetClass, "targetClass must not be null");
        if (value == null) {
            return null;
        }
        if (Objects.equals(targetClass, String.class)) {
            return (T) value;
        }
        final ConfigConverter<T> converter = getOrAdConverter(targetClass);

        if (converter == null) {
            throw new IllegalArgumentException("No converter defined for type '" + targetClass + "'");
        }
        try {
            return converter.convert(value);
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Can not convert to '" + targetClass + "'", ex);
        }
    }

    /**
     * Associates a {@code ConfigConverter} to a {@code Class} so each conversion of that type is performed by the
     * converter. Additional converters registeration for a single type will be ignored.
     *
     * @throws IllegalStateException if {@code ConverterService} instance is already initialized
     * @throws NullPointerException if any of the following parameters are {@code null}.
     *     <ul>
     *       <li>{@code converterType}</li>
     *       <li>{@code converter}</li>
     *     </ul>
     */
    <T> void addConverter(@NonNull final Class<T> converterType, @NonNull final ConfigConverter<T> converter) {
        throwIfInitialized();
        Objects.requireNonNull(converterType, "converterType must not be null");
        Objects.requireNonNull(converter, "converter must not be null");

        if (converters.containsKey(converterType)) {
            if (initialized) {
                throw new IllegalStateException("Converter for type '" + converterType + "' already registered");
            } else {
                return;
            }
        }

        this.converters.put(converterType, converter);
    }

    void clear() {
        this.converters.clear();
    }

    @Override
    public void init() {
        throwIfInitialized();
        // Primitives
        addConverter(Integer.TYPE, INTEGER_CONVERTER);
        addConverter(Long.TYPE, LONG_CONVERTER);
        addConverter(Double.TYPE, DOUBLE_CONVERTER);
        addConverter(Float.TYPE, FLOAT_CONVERTER);
        addConverter(Short.TYPE, SHORT_CONVERTER);
        addConverter(Byte.TYPE, BYTE_CONVERTER);
        addConverter(Boolean.TYPE, BOOLEAN_CONVERTER);

        addConverter(String.class, STRING_CONVERTER);
        addConverter(Integer.class, INTEGER_CONVERTER);
        addConverter(Long.class, LONG_CONVERTER);
        addConverter(Double.class, DOUBLE_CONVERTER);
        addConverter(Float.class, FLOAT_CONVERTER);
        addConverter(Short.class, SHORT_CONVERTER);
        addConverter(Byte.class, BYTE_CONVERTER);
        addConverter(Boolean.class, BOOLEAN_CONVERTER);
        addConverter(BigDecimal.class, BIG_DECIMAL_CONVERTER);
        addConverter(BigInteger.class, BIG_INTEGER_CONVERTER);
        addConverter(URL.class, URL_CONVERTER);
        addConverter(URI.class, URI_CONVERTER);
        addConverter(Path.class, PATH_CONVERTER);
        addConverter(File.class, FILE_CONVERTER);
        addConverter(ZonedDateTime.class, ZONED_DATE_TIME_CONVERTER);
        addConverter(Duration.class, DURATION_CONVERTER);
        addConverter(ChronoUnit.class, CHRONO_UNIT_CONVERTER);
        addConverter(InetAddress.class, INET_ADDRESS_CONFIG_CONVERTER);

        initialized = true;
    }

    @Override
    public void dispose() {
        this.converters.clear();
        initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Nullable
    <T> ConfigConverter<T> getConverterForType(@NonNull final Class<T> valueType) {
        throwIfNotInitialized();
        Objects.requireNonNull(valueType, "valueType must not be null");
        return getOrAdConverter(valueType);
    }

    /**
     * @param valueType type to convert to
     * @return
     *     <ul>
     *       <li>the previously configured {@code ConfigConverter} if exist for {@code valueType}</li>
     *       <li>a new instance of {@code EnumConverter} if {@code valueType} is an enum
     *       and no {@code ConfigConverter} was found</li>
     *       <li>{@code null} otherwise</li>
     *     </ul>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ConfigConverter<T> getOrAdConverter(@NonNull Class<T> valueType) {
        ConfigConverter<T> converter = (ConfigConverter<T>) converters.get(valueType);

        if (converter == null && valueType.isEnum()) {
            return (ConfigConverter<T>) converters.computeIfAbsent(valueType, c -> new EnumConverter(c));
        }
        return converter;
    }
}
