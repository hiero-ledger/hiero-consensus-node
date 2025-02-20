// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A builder for toString methods.
 * <p>
 * It can be used to build a toString method for an object by appending values to the builder and then calling
 * {@link #toString()} to get the final string. This maintains a uniform toString format across the codebase.
 * <p>
 * If you are using IntelliJ, you can use the "Generate" menu to generate a toString method using this class by adding
 * the following templates:
 * <pre>
 * public java.lang.String toString() {
 *   return new com.swirlds.base.utility.ToStringBuilder(this)
 *   #foreach ($member in $members)
 *   .append("$member.name", $member.accessor)
 *   #end
 *   .toString();
 * }
 * </pre>
 * and with {@code appendSuper}:
 * <pre>
 * public java.lang.String toString() {
 *   return new com.swirlds.base.utility.ToStringBuilder(this)
 *   .appendSuper(super.toString())
 *   #foreach ($member in $members)
 *   .append("$member.name", $member.accessor)
 *   #end
 *   .toString();
 * }
 * </pre>
 */
public class ToStringBuilder {
    private static final Pattern SUPER_STRING_PATTERN = Pattern.compile("\\[(.*?)\\]");
    private static final String PACKAGE_PREFIX_PATTERN = "^.*\\.";
    private static final String NULL_STRING = "<null>";
    private static final char PACKAGE_SEPARATOR = '.';
    private static final char INNER_CLASS_SEPARATOR = '$';
    private final StringBuilder builder;
    private boolean parameterAdded = false;

    /**
     * Create a new ToStringBuilder for the given object.
     *
     * @param object the object to build a toString for
     */
    public ToStringBuilder(@NonNull final Object object) {
        builder = new StringBuilder();
        builder.append(formatClassName(object.getClass().getName())).append("[");
    }

    /**
     * Appends all values from the super toString method.
     * <p>
     * Note: This method will only work if the super toString method is also built using this class.
     *
     * @param superString the output of {@code super.toString()} for the object being built
     *
     * @return this builder
     */
    @NonNull
    public ToStringBuilder appendSuper(@NonNull final String superString) {
        Objects.requireNonNull(superString, "superString must not be null");
        final Matcher matcher = SUPER_STRING_PATTERN.matcher(superString);

        if (matcher.find()) {
            builder.append(matcher.group(1));
            appendSeparator();
        }

        return this;
    }

    /**
     * Appends the given value to the toString.
     * <p>
     * Note: {@code null} values will be replaced with the string "&lt;null&gt;".
     *
     * @param value the value to append
     *
     * @return this builder
     */
    @NonNull
    public ToStringBuilder append(@Nullable final Object value) {
        final String formattedValue = value == null ? NULL_STRING : value.toString();
        builder.append(formattedValue);
        appendSeparator();
        return this;
    }

    /**
     * Appends the given field and its value to the toString.
     * <p>
     * Note: {@code null} values will be replaced with the string "&lt;null&gt;".
     *
     * @param fieldName the name of the field
     * @param value the value of the field
     *
     * @return this builder
     */
    @NonNull
    public ToStringBuilder append(@NonNull final String fieldName, @Nullable final Object value) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        final String formattedValue = value == null ? NULL_STRING : value.toString();
        builder.append(fieldName).append("=").append(formattedValue);
        appendSeparator();
        return this;
    }

    /**
     * Finish building the toString.
     *
     * @return the final toString
     */
    @Override
    @NonNull
    public String toString() {
        if (parameterAdded) {
            builder.setLength(builder.length() - 1); // Remove last comma
        }
        builder.append("]");
        return builder.toString();
    }

    /**
     * Format the given class name to remove the package prefix and replace inner class separators with a period (".")
     * character.
     * <p>
     * For example, "com.hedera.services.legacy.core.jproto.JKeyList$JKeyListObject" becomes "JKeyList.JKeyListObject"
     * @param input the class name to format
     * @return the formatted class name
     */
    @NonNull
    private static String formatClassName(@NonNull final String input) {
        Objects.requireNonNull(input, "input must not be null");
        final String withoutPackage = input.replaceFirst(PACKAGE_PREFIX_PATTERN, "");
        return withoutPackage.replace(INNER_CLASS_SEPARATOR, PACKAGE_SEPARATOR);
    }

    private void appendSeparator() {
        builder.append(",");
        parameterAdded = true;
    }
}
