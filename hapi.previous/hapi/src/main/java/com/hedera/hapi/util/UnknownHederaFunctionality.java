// SPDX-License-Identifier: Apache-2.0
package com.hedera.hapi.util;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;

/**
 * Exception raised when mapping from {@link TransactionBody} or {@link Query} to {@link HederaFunctionality}
 * when there is no known mapping. This should NEVER happen and means there is a new functionality that is
 * not yet supported in the code.
 */
public class UnknownHederaFunctionality extends Exception {}
