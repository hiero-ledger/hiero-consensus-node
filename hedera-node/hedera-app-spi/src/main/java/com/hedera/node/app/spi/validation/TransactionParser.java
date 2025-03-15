// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.validation;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;

public interface TransactionParser {

    TransactionBody parseSigned(Bytes signedBytes) throws PreCheckException;
}
