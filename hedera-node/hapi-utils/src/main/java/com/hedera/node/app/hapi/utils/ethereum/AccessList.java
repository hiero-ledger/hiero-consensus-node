package com.hedera.node.app.hapi.utils.ethereum;

import java.util.List;

/**
 * Represents one item in an access list. See <a href="https://eips.ethereum.org/EIPS/eip-2930">EIP-2930</a>.
 */
public record AccessList(byte[] address, List<byte[]> storageKeys) {
}
