// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Class for constructing HTML tags.
 */
public class HtmlTagFactory {
    /**
     * Map from attribute name to list of attribute values.
     */
    private final Map<String, List<String>> attributeMap = new HashMap<>();

    /**
     * The type of HTML tag
     */
    private final String tagName;

    /**
     * The content of the HTML tag. May be empty
     */
    private final String content;

    /**
     * Construct a new HtmlTagFactory.
     *
     * @param tagName The type of HTML tag
     * @param content The content of the HTML tag
     */
    public HtmlTagFactory(@NonNull final String tagName, @NonNull final String content) {
        this.tagName = Objects.requireNonNull(tagName);
        this.content = Objects.requireNonNull(content);
    }

    /**
     * Construct a new HtmlTagFactory.
     * <p>
     * This constructor is for a void tag factory
     *
     * @param tagName The type of HTML tag
     */
    public HtmlTagFactory(@NonNull final String tagName) {
        this.tagName = Objects.requireNonNull(tagName);
        this.content = null;
    }

    /**
     * Add an attribute with multiple values.
     * <p>
     * If the attribute has already been added, values will be appended to the existing value list.
     *
     * @param attributeName The attribute name
     * @param values        The attribute values
     * @return this
     */
    @NonNull
    public HtmlTagFactory addAttribute(@NonNull final String attributeName, @NonNull final List<String> values) {
        if (attributeMap.containsKey(attributeName)) {
            attributeMap.get(attributeName).addAll(values);
        } else {
            attributeMap.put(attributeName, new ArrayList<>(values));
        }

        return this;
    }

    /**
     * Add an attribute with a single value.
     * <p>
     * If the attribute has already been added, the value will be appended to the existing value list.
     *
     * @param attributeName The attribute name
     * @param value         The attribute value
     * @return this
     */
    @NonNull
    public HtmlTagFactory addAttribute(@NonNull final String attributeName, @NonNull final String value) {
        if (attributeMap.containsKey(attributeName)) {
            attributeMap.get(attributeName).add(value);
        } else {
            final ArrayList<String> attributeList = new ArrayList<>();
            attributeList.add(value);

            attributeMap.put(attributeName, attributeList);
        }

        return this;
    }

    /**
     * Convenience method for adding a class attribute.
     *
     * @param className The class name
     * @return this
     */
    @NonNull
    public HtmlTagFactory addClass(@NonNull final String className) {
        return addAttribute("class", className);
    }

    /**
     * Convenience method for adding multiple class attributes.
     *
     * @param classNames The class names
     * @return this
     */
    @NonNull
    public HtmlTagFactory addClasses(@NonNull final List<String> classNames) {
        return addAttribute("class", classNames);
    }

    /**
     * Convenience method for adding multiple class attributes.
     *
     * @param classNames The class names
     * @return this
     */
    @NonNull
    public HtmlTagFactory addClasses(@NonNull final String... classNames) {
        return addClasses(List.of(classNames));
    }

    /**
     * Generate the HTML tag.
     *
     * @return the HTML tag
     */
    @NonNull
    public String generateTag() {
        final StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append("<");
        stringBuilder.append(tagName);

        final List<String> attributeStrings = new ArrayList<>();
        for (final Map.Entry<String, List<String>> entry : attributeMap.entrySet()) {
            final String attributeStringBuilder = entry.getKey() + "=\"" + String.join(" ", entry.getValue()) + "\"";

            attributeStrings.add(attributeStringBuilder);
        }

        if (!attributeStrings.isEmpty()) {
            stringBuilder.append(" ");
            stringBuilder.append(String.join(" ", attributeStrings));
        }

        // this is a void tag
        if (content == null) {
            stringBuilder.append(">");
            return stringBuilder.toString();
        }

        stringBuilder.append(">");
        stringBuilder.append(content);
        stringBuilder.append("</");
        stringBuilder.append(tagName);
        stringBuilder.append(">");

        return stringBuilder.toString();
    }
}
