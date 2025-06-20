// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import com.hedera.hapi.node.base.TokenID;
import com.hedera.node.app.service.token.impl.validators.TokenListChecks;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class TokenListChecksTest {

    private static final TokenID TOKEN_1 = TokenID.newBuilder().tokenNum(1).build();
    private static final TokenID TOKEN_2 = TokenID.newBuilder().tokenNum(2).build();
    private static final TokenID TOKEN_3 = TokenID.newBuilder().tokenNum(3).build();

    @Test
    void repeatsItself_nullInput() {
        //noinspection DataFlowIssue
        Assertions.assertThatThrownBy(() -> TokenListChecks.repeatsItself(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void repeatsItself_emptyInput() {
        Assertions.assertThat(TokenListChecks.repeatsItself(Collections.emptyList()))
                .isFalse();
    }

    @Test
    void repeatsItself_singleElement() {
        Assertions.assertThat(TokenListChecks.repeatsItself(List.of(TOKEN_1))).isFalse();
    }

    @Test
    void repeatsItself_multipleDistinctElements() {
        Assertions.assertThat(TokenListChecks.repeatsItself(List.of(TOKEN_1, TOKEN_2, TOKEN_3)))
                .isFalse();
    }

    @Test
    void repeatsItself_multipleRepeatedElements() {
        Assertions.assertThat(TokenListChecks.repeatsItself(List.of(TOKEN_1, TOKEN_2, TOKEN_3, TOKEN_1)))
                .isTrue();
        Assertions.assertThat(TokenListChecks.repeatsItself(List.of(TOKEN_1, TOKEN_1)))
                .isTrue();
        Assertions.assertThat(TokenListChecks.repeatsItself(List.of(TOKEN_1, TOKEN_2, TOKEN_1, TOKEN_2)))
                .isTrue();
    }
}
