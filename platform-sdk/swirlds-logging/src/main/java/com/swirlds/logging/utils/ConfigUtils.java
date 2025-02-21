// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.utils;

import static com.swirlds.logging.utils.ConfigUtils.DataUnit.BYTE;
import static com.swirlds.logging.utils.ConfigUtils.DataUnit.GIGA_BYTE;
import static com.swirlds.logging.utils.ConfigUtils.DataUnit.KILO_BYTE;
import static com.swirlds.logging.utils.ConfigUtils.DataUnit.MEGA_BYTE;
import static com.swirlds.logging.utils.ConfigUtils.DataUnit.TERA_BYTE;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * Ad-hoc configuration utilities.
 * to-be-fixed: #12310
 * @deprecated Do not extend this class as this should become behaviour of the config api.
 */
@Deprecated(forRemoval = true)
public class ConfigUtils {

    // FUTURE-WORK move all this as features of the config module.
    private ConfigUtils() {}

    /**
     * Returns the value of the property with the given name or the given default value if the property does not exist.
     * Never returns null.
     *
     * @param propertyName the name of the property
     * @param propertyType the type of the property
     * @param orElseValue  the default value that will be used if the property does not exist.
     * @param <T>          the generic type of the property
     * @return the value of the property or the given default value if the property does not exist
     * @throws IllegalArgumentException if the raw {@code String} value of the property can not be converted to the
     *                                  given type
     */
    @NonNull
    public static <T> T configValueOrElse(
            final @NonNull Configuration configuration,
            final @NonNull String propertyName,
            final @NonNull Class<T> propertyType,
            final @NonNull T orElseValue) {
        Objects.requireNonNull(orElseValue, "orElseValue must not be null");
        return Objects.requireNonNullElse(configuration.getValue(propertyName, propertyType, null), orElseValue);
    }

    /**
     * Reads a property value as a data size value. Returns the value in bytes. The property can be written as
     * <p>
     * Usage:
     * <pre>
     *     For bytes:
     *     property.example.bytes=500
     *     For kilobytes:
     *     property.example.kilobytes=500kb
     *     property.example.kilobytes=500KB
     *     property.example.kilobytes=500 kb
     *     property.example.kilobytes=500 KB
     *     For megabytes:
     *     property.example.megabytes=500MB
     *     property.example.megabytes=500mb
     *     property.example.megabytes=500 mb
     *     property.example.megabytes=500 MB
     *     For gigabytes:
     *     property.example.gigabytes=500GB
     *     property.example.gigabytes=500gb
     *     property.example.gigabytes=500 gb
     *     property.example.gigabytes=500 GB
     *     For terabytes:
     *     property.example.terabytes=500TB
     *     property.example.terabytes=500tb
     *     property.example.terabytes=500 tb
     *     property.example.terabytes=500 TB
     * </pre>
     *
     * @param configuration configuration object to get the properties from
     * @param propertyName  property containing the data-size property value
     * @return the value of the property transformed to bytes
     */
    @Nullable
    public static Long readDataSizeInBytes(
            final @NonNull Configuration configuration, final @NonNull String propertyName) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(propertyName, "propertyName must not be null");
        final String propertyValue = configuration.getValue(propertyName, String.class, null);
        if (propertyValue == null || propertyValue.isBlank()) {
            return null;
        }
        return DataSize.parseFrom(propertyValue).asBytes();
    }

    public record DataSize(long value, DataUnit unit) {

        /**
         * /** Converts a string value to a {@link DataSize} instance. Accepts units bytes, kb, mb, gb and tb. Unit values
         * can be lower or upper case and can be followed by as many blank spaces as needed. If not unit is present or the
         * unit is not recognized as:kb, mb, gb and tb byte is assumed.
         * <p>
         * e.g:
         * <pre>
         *     DataSize.parse("500") --> DataSize(500L,DataUnit.BYTES)
         *     DataSize.parse("500kb") --> DataSize(500L,DataUnit.KILO_BYTES)
         *     DataSize.parse("500 kb") --> DataSize(500L,DataUnit.KILO_BYTES)
         *     DataSize.parse("500 KB") --> DataSize(500L,DataUnit.KILO_BYTES)
         *     DataSize.parse("500     KB") --> DataSize(500L,DataUnit.KILO_BYTES)
         *     DataSize.parse("500     MB") --> DataSize(500L,DataUnit.MEGA_BYTES)
         * </pre>
         *
         * @param value String value to convert
         * @return an {@link DataSize} instance matching the value
         */
        @NonNull
        public static DataSize parseFrom(final @NonNull String value) {
            final String result = value.replaceAll("\\s", "").toLowerCase();
            DataUnit unit =
                    switch (result.charAt(result.length() - 2)) {
                        case 'k':
                            yield KILO_BYTE;
                        case 'm':
                            yield MEGA_BYTE;
                        case 'g':
                            yield GIGA_BYTE;
                        case 't':
                            yield TERA_BYTE;
                        default:
                            yield BYTE;
                    };

            return new DataSize(unit.getValueFromString(result), unit);
        }

        /**
         * Converts the value represented by this {@link DataSize} to Byte
         *
         * @return the corresponding value represented by this instance in bytes
         */
        public long asBytes() {
            return this.unit.convertToBytes(this.value);
        }
    }

    public enum DataUnit {
        BYTE(""),
        KILO_BYTE("kb"),
        MEGA_BYTE("mb"),
        GIGA_BYTE("gb"),
        TERA_BYTE("tb");

        final String symbol;

        DataUnit(@NonNull final String symbol) {
            this.symbol = symbol;
        }

        public long convertToBytes(long value) {
            return value * (long) Math.pow(1024, ordinal());
        }

        public double convertFromBytes(double value) {
            return value / Math.pow(1024, ordinal());
        }

        public double convertTo(DataUnit targetUnit, long value) {
            return targetUnit.convertFromBytes(convertToBytes(value));
        }

        public String getSymbol() {
            return symbol;
        }

        public long getValueFromString(String value) {
            final String result = value.replaceAll("\\s", "");
            return Long.parseLong(result.substring(0, result.length() - symbol.length()));
        }
    }
}
