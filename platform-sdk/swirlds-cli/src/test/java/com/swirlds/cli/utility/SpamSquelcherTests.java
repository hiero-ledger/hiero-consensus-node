/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.cli.utility;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Tests for the SpamSquelcher class
 */
@DisplayName("SpamSquelcher Tests")
class SpamSquelcherTests {

    @BeforeEach()
    void reset() {
        SpamSquelcher.resetSpamStatus();
    }

    @Test
    @DisplayName("Test line is not spam")
    void lineIsNotSpam() {
        assertFalse(SpamSquelcher.lineIsSpam("This is a line outside spam."));
    }

    @Test
    @DisplayName("Test line is start of spam")
    void lineIsStartOfSpam() {
        assertTrue(SpamSquelcher.lineIsSpam(SpamSquelcher.startOfSpam));
    }

    @Test
    @DisplayName("Test line is in middle of spam")
    void lineIsMiddleOfSpam() {
        assertTrue(SpamSquelcher.lineIsSpam(SpamSquelcher.startOfSpam));
        assertTrue(SpamSquelcher.lineIsSpam("This line is in middle of spam."));
    }

    @Test
    @DisplayName("Test line is end of spam")
    void lineIsEndOfSpam() {
        assertTrue(SpamSquelcher.lineIsSpam(SpamSquelcher.startOfSpam));
        assertTrue(SpamSquelcher.lineIsSpam("This line is in middle of spam."));
        assertTrue(SpamSquelcher.lineIsSpam(SpamSquelcher.endOfSpam));
    }
}
