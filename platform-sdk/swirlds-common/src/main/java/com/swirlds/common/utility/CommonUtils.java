// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Utility class for other operations
 */
public class CommonUtils {

    /**
     * This is equivalent to System.out.println(), but is not used for debugging; it is used for production code for
     * communicating to the user. Centralizing it here makes it easier to search for debug prints that might have
     * slipped through before a release.
     *
     * @param msg the message for the user
     */
    public static void tellUserConsole(final String msg) {
        System.out.println(msg);
    }

    /**
     * Calls {@link CommonUtils#tellUserConsole(String)} and highlights the message.
     *
     * @param msg the message for the user
     */
    public static void tellUserConsoleHighlighted(final String msg) {
        tellUserConsole("\n***** " + msg + " *****\n");
    }

    /**
     * Given a name from the address book, return the corresponding alias to associate with certificates in the trust
     * store. This is found by lowercasing all the letters, removing accents, and deleting every character other than
     * letters and digits. A "letter" is anything in the Unicode category "letter", which includes most alphabets, as
     * well as ideographs such as Chinese.
     * <p>
     * WARNING: Some versions of Java 8 have a terrible bug where even a single capital letter in an alias will prevent
     * SSL or TLS connections from working (even though those protocols don't use the aliases). Although this ought to
     * work fine with Chinese/Greek/Cyrillic characters, it is safer to stick with only the 26 English letters.
     *
     * @param name a name from the address book
     * @return the corresponding alias
     */
    public static String nameToAlias(final String name) {
        // Convert to lowercase. The ROOT locale should work with most non-english characters. Though there
        // can be surprises. For example, in Turkey, the capital I would convert in a Turkey-specific way to
        // a "lowercase I without a dot". But ROOT would simply convert it to a lowercase I.
        String alias = name.toLowerCase(Locale.ROOT);

        // Now find each character that is a single Unicode codepoint for an accented character, and convert
        // it to an expanded form consisting of the unmodified letter followed
        // by all its modifiers. So if "à" was encoded as U+00E0, it will be converted to U+0061 U++U0300.
        // This is necessary because Unicode normally allows that character to be encoded either way, and
        // they are normally treated as equivalent.
        alias = Normalizer.normalize(alias, Normalizer.Form.NFD);

        // Finally, delete the modifiers. So the expanded "à" (U+0061 U++U0300) will be converted to "a"
        // (U+0061). Also delete all spaces, punctuation, special characters, etc. Leave only digits and
        // unaccented letters. Specifically, leave only the 10 digits 0-9 and the characters that have a
        // Unicode category of "letter". Letters include alphabets (Latin, Cyrillic, etc.)
        // and ideographs (Chinese, etc.).
        alias = alias.replaceAll("[^\\p{L}0-9]", "");
        return alias;
    }
}
