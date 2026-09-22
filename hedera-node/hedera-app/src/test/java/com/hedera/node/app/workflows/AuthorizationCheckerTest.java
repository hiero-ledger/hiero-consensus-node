// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.hapi.node.base.HederaFunctionality.FILE_UPDATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ENTITY_NOT_ALLOWED_TO_DELETE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import com.hedera.node.app.spi.workflows.PreCheckException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthorizationCheckerTest {
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(2).build();
    private static final TransactionBody TX_BODY = TransactionBody.DEFAULT;

    @Mock
    private Authorizer authorizer;

    private AuthorizationChecker subject;

    @BeforeEach
    void setUp() {
        subject = new AuthorizationChecker(authorizer);
    }

    // --- enforce(...) : throwing form used at ingest ---

    @ParameterizedTest
    @EnumSource(
            value = HederaFunctionality.class,
            names = {"FILE_CREATE", "FILE_UPDATE", "FILE_APPEND", "FILE_DELETE"})
    void fileOperationsConsultTheAuthorizerAndRejectUnauthorizedPayers(final HederaFunctionality functionality) {
        given(authorizer.hasPrivilegedAuthorization(PAYER_ID, functionality, TX_BODY))
                .willReturn(SystemPrivilege.UNAUTHORIZED);

        assertThatThrownBy(() -> subject.enforce(PAYER_ID, functionality, TX_BODY))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", AUTHORIZATION_FAILED);
    }

    @ParameterizedTest
    @EnumSource(
            value = HederaFunctionality.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"FILE_CREATE", "FILE_UPDATE", "FILE_APPEND", "FILE_DELETE"})
    void nonFileOperationsAreNotEnforced(final HederaFunctionality functionality) {
        assertThatCode(() -> subject.enforce(PAYER_ID, functionality, TX_BODY)).doesNotThrowAnyException();
        verify(authorizer, never()).hasPrivilegedAuthorization(PAYER_ID, functionality, TX_BODY);
    }

    @Test
    void authorizedPayerIsAllowed() throws PreCheckException {
        given(authorizer.hasPrivilegedAuthorization(PAYER_ID, FILE_UPDATE, TX_BODY))
                .willReturn(SystemPrivilege.AUTHORIZED);

        assertThatCode(() -> subject.enforce(PAYER_ID, FILE_UPDATE, TX_BODY)).doesNotThrowAnyException();
    }

    @Test
    void payerNeedingNoPrivilegeIsAllowed() {
        given(authorizer.hasPrivilegedAuthorization(PAYER_ID, FILE_UPDATE, TX_BODY))
                .willReturn(SystemPrivilege.UNNECESSARY);

        assertThatCode(() -> subject.enforce(PAYER_ID, FILE_UPDATE, TX_BODY)).doesNotThrowAnyException();
    }

    @Test
    void impermissibleOperationIsRejectedAsNotAllowedToDelete() {
        given(authorizer.hasPrivilegedAuthorization(PAYER_ID, FILE_UPDATE, TX_BODY))
                .willReturn(SystemPrivilege.IMPERMISSIBLE);

        assertThatThrownBy(() -> subject.enforce(PAYER_ID, FILE_UPDATE, TX_BODY))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", ENTITY_NOT_ALLOWED_TO_DELETE);
    }

    // --- failureFor(...) : status-returning form used at pre-handle ---

    @Test
    void failureForReturnsAuthorizationFailedForUnauthorizedFileOp() {
        given(authorizer.hasPrivilegedAuthorization(PAYER_ID, FILE_UPDATE, TX_BODY))
                .willReturn(SystemPrivilege.UNAUTHORIZED);

        assertThat(subject.failureFor(PAYER_ID, FILE_UPDATE, TX_BODY)).isEqualTo(AUTHORIZATION_FAILED);
    }

    @Test
    void failureForReturnsEntityNotAllowedToDeleteForImpermissibleFileOp() {
        given(authorizer.hasPrivilegedAuthorization(PAYER_ID, FILE_UPDATE, TX_BODY))
                .willReturn(SystemPrivilege.IMPERMISSIBLE);

        assertThat(subject.failureFor(PAYER_ID, FILE_UPDATE, TX_BODY)).isEqualTo(ENTITY_NOT_ALLOWED_TO_DELETE);
    }

    @Test
    void failureForReturnsNullWhenAuthorized() {
        given(authorizer.hasPrivilegedAuthorization(PAYER_ID, FILE_UPDATE, TX_BODY))
                .willReturn(SystemPrivilege.AUTHORIZED);

        assertThat(subject.failureFor(PAYER_ID, FILE_UPDATE, TX_BODY)).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = HederaFunctionality.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"FILE_CREATE", "FILE_UPDATE", "FILE_APPEND", "FILE_DELETE"})
    void failureForReturnsNullAndSkipsTheAuthorizerForNonFileOps(final HederaFunctionality functionality) {
        assertThat(subject.failureFor(PAYER_ID, functionality, TX_BODY)).isNull();
        verify(authorizer, never()).hasPrivilegedAuthorization(PAYER_ID, functionality, TX_BODY);
    }
}
