// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_UPDATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ENTITY_NOT_ALLOWED_TO_DELETE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.workflows.PreCheckException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Shared privilege check for operations whose authorization is decidable from the payer and transaction body. Both
 * ingest (fail-fast, throwing) and pre-handle (returning a due-diligence status) use this single class, so the set of
 * enforced operations and the status mapping live in one place and cannot drift between the two paths.
 *
 * <p>Scoped to file operations (create/update/append/delete); {@code FILE_CREATE} never requires a privilege
 * (pass-through) but is listed for completeness. Every other functionality keeps its consensus-time authorization only.
 * The privilege decision itself is delegated to {@link Authorizer#hasPrivilegedAuthorization}, the same source of truth
 * used at consensus.
 */
@Singleton
public class AuthorizationChecker {
    private final Authorizer authorizer;

    @Inject
    public AuthorizationChecker(@NonNull final Authorizer authorizer) {
        this.authorizer = requireNonNull(authorizer, "authorizer must not be null");
    }

    /**
     * Returns the response code for a privileged operation whose payer lacks the required privilege, or {@code null}
     * if the operation is authorized, requires no privilege, or is not one of the enforced operations. This is the
     * single decision used by both ingest and pre-handle.
     *
     * @param payerId the payer account
     * @param functionality the transaction functionality
     * @param txBody the transaction body
     * @return {@code AUTHORIZATION_FAILED}, {@code ENTITY_NOT_ALLOWED_TO_DELETE}, or {@code null} if there is no failure
     */
    @Nullable
    public ResponseCodeEnum failureFor(
            @NonNull final AccountID payerId,
            @NonNull final HederaFunctionality functionality,
            @NonNull final TransactionBody txBody) {
        if (!isPrivilegedFileOperation(functionality)) {
            return null;
        }
        return switch (authorizer.hasPrivilegedAuthorization(payerId, functionality, txBody)) {
            case UNAUTHORIZED -> AUTHORIZATION_FAILED;
            case IMPERMISSIBLE -> ENTITY_NOT_ALLOWED_TO_DELETE;
            case AUTHORIZED, UNNECESSARY -> null;
        };
    }

    /**
     * Rejects a transaction whose payer lacks the privilege required for an enforced operation (for example, updating
     * a system file such as the exchange-rate file 0.0.112). A no-op for any other functionality.
     *
     * @param payerId the payer account
     * @param functionality the transaction functionality
     * @param txBody the transaction body
     * @throws PreCheckException AUTHORIZATION_FAILED if the payer lacks a required privilege, or
     *                           ENTITY_NOT_ALLOWED_TO_DELETE if the operation is impermissible
     */
    public void enforce(
            @NonNull final AccountID payerId,
            @NonNull final HederaFunctionality functionality,
            @NonNull final TransactionBody txBody)
            throws PreCheckException {
        final var failure = failureFor(payerId, functionality, txBody);
        if (failure != null) {
            throw new PreCheckException(failure);
        }
    }

    private static boolean isPrivilegedFileOperation(@NonNull final HederaFunctionality functionality) {
        return functionality == FILE_CREATE
                || functionality == FILE_UPDATE
                || functionality == FILE_APPEND
                || functionality == FILE_DELETE;
    }
}
