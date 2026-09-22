// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Represents one item in an access list. See <a href="https://eips.ethereum.org/EIPS/eip-2930">EIP-2930</a>.
 */
public record AccessListItem(
        @NonNull Bytes address, @NonNull List<Bytes32> storageKeys) {}
