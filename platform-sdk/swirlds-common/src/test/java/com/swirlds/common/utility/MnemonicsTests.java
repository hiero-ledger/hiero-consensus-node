// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static com.swirlds.common.utility.Mnemonics.generateMnemonic;
import static com.swirlds.common.utility.Mnemonics.generateMnemonicWords;
import static com.swirlds.common.utility.Mnemonics.getWord;
import static com.swirlds.common.utility.Mnemonics.getWordIndex;
import static com.swirlds.common.utility.Mnemonics.getWordList;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Mnemonics Tests")
class MnemonicsTests {

    @Test
    @DisplayName("Alphabetical Ordering Test")
    void alphabeticalOrderingTest() {
        String prev = null;
        for (final String word : getWordList()) {
            if (prev != null) {
                if (word.compareTo(prev) < 0) {
                    throw new IllegalStateException("BIP39 words are not in alphabetical order");
                }
            }
            prev = word;
        }
    }

    @Test
    @DisplayName("Word Count Test")
    void wordCountTest() {
        assertEquals(2048, getWordList().size());
    }

    @Test
    @DisplayName("Uniqueness Test")
    void uniquenessTest() {
        final Set<String> words = new HashSet<>();
        for (final String word : getWordList()) {
            assertFalse(words.contains(word));
            words.add(word);
        }
    }

    @Test
    @DisplayName("All Lower Case Test")
    void allLowerCaseTest() {
        for (final String word : getWordList()) {
            assertEquals(word, word.toLowerCase());
        }
    }

    @Test
    @DisplayName("Modulo Test")
    void moduloTest() {
        final Random random = getRandomPrintSeed();

        for (int i = 0; i < getWordList().size(); i++) {
            final String word = getWord(i);

            for (int j = 0; j < 10; j++) {
                final long offset =
                        ((long) Math.abs(random.nextInt())) * getWordList().size();
                assertEquals(word, getWord(i + offset));
                assertEquals(word, getWord(-i - offset));
            }
        }
    }

    @Test
    @DisplayName("Mapping Test")
    void mappingTest() {
        for (final String word : getWordList()) {
            final int index = getWordIndex(word);
            assertEquals(word, getWord(index));
        }

        assertThrows(IllegalArgumentException.class, () -> getWordIndex("not a word"));
    }

    @Test
    @DisplayName("Generate Mnemonic Words Test")
    void generateMnemonicWordsTest() {
        final Random random = getRandomPrintSeed();
        final byte[] entropy = new byte[100];
        random.nextBytes(entropy);

        final int wordCount = random.nextInt(1, 10);
        final List<String> words = generateMnemonicWords(entropy, wordCount);
        assertEquals(words, generateMnemonicWords(entropy, wordCount));

        assertEquals(wordCount, words.size());
        for (final String word : words) {
            assertDoesNotThrow(() -> getWordIndex(word));
        }
    }

    @Test
    @DisplayName("Generate Mnemonic Words Low Entropy Test")
    void generateMnemonicWordsLowEntropyTest() {
        final Random random = getRandomPrintSeed();
        final byte[] entropy = new byte[1];
        random.nextBytes(entropy);

        final int wordCount = random.nextInt(1, 10);
        final List<String> words = generateMnemonicWords(entropy, wordCount);
        assertEquals(words, generateMnemonicWords(entropy, wordCount));

        assertEquals(wordCount, words.size());
        for (final String word : words) {
            assertDoesNotThrow(() -> getWordIndex(word));
        }
    }

    @Test
    @DisplayName("Generate Mnemonic Words No Entropy Test")
    void generateMnemonicWordsNoEntropyTest() {
        final Random random = getRandomPrintSeed();
        final byte[] entropy = new byte[0];

        final int wordCount = random.nextInt(1, 10);
        final List<String> words = generateMnemonicWords(entropy, wordCount);
        assertEquals(words, generateMnemonicWords(entropy, wordCount));

        assertEquals(wordCount, words.size());
        for (final String word : words) {
            assertDoesNotThrow(() -> getWordIndex(word));
        }
    }

    @Test
    @DisplayName("Generate Mnemonic Test")
    void generateMnemonicTest() {
        final Random random = getRandomPrintSeed();
        final byte[] entropy = new byte[100];
        random.nextBytes(entropy);

        final int wordCount = random.nextInt(1, 10);
        final String mnemonic = generateMnemonic(entropy, wordCount);
        assertEquals(mnemonic, generateMnemonic(entropy, wordCount));

        final String[] words = mnemonic.split("-");
        assertEquals(wordCount, words.length);
        for (final String word : words) {
            assertDoesNotThrow(() -> getWordIndex(word));
        }
    }

    @Test
    @DisplayName("Generate Mnemonic Custom Separator Test")
    void generateMnemonicCustomSeparatorTest() {
        final Random random = getRandomPrintSeed();
        final byte[] entropy = new byte[100];
        random.nextBytes(entropy);

        final int wordCount = random.nextInt(1, 10);
        final String mnemonic = generateMnemonic(entropy, wordCount, ":");
        assertEquals(mnemonic, generateMnemonic(entropy, wordCount, ":"));

        final String[] words = mnemonic.split(":");
        assertEquals(wordCount, words.length);
        for (final String word : words) {
            assertDoesNotThrow(() -> getWordIndex(word));
        }
    }
}
