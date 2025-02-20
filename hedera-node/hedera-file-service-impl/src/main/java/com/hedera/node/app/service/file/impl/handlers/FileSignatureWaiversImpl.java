// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.file.FileSignatureWaivers;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A Singleton implementation of signature waivers needed for transactions in
 * {@link com.hedera.node.app.service.file.FileService}.
 */
@Singleton
public class FileSignatureWaiversImpl implements FileSignatureWaivers {
    private final Authorizer authorizer;

    /**
     * Constructs a {@link FileSignatureWaiversImpl} with the given {@link Authorizer}.
     * @param authorizer account is authorized to perform a specific function
     */
    @Inject
    public FileSignatureWaiversImpl(@NonNull final Authorizer authorizer) {
        this.authorizer = requireNonNull(authorizer);
    }

    @Override
    public boolean areFileUpdateSignaturesWaived(final TransactionBody fileUpdateTxn, final AccountID payer) {
        return authorizer.hasPrivilegedAuthorization(payer, HederaFunctionality.FILE_UPDATE, fileUpdateTxn)
                == SystemPrivilege.AUTHORIZED;
    }

    @Override
    public boolean areFileAppendSignaturesWaived(final TransactionBody fileAppendTxn, final AccountID payer) {
        return authorizer.hasPrivilegedAuthorization(payer, HederaFunctionality.FILE_APPEND, fileAppendTxn)
                == SystemPrivilege.AUTHORIZED;
    }
}
