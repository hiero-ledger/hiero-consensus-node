// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract ConstructorTransferToken {
    address constant HTS_PRECOMPILE = address(0x167);
    int32 public transferResponseCode;

    constructor(address token, address sender, address receiver, int64 amount) {
        (bool success, bytes memory result) = HTS_PRECOMPILE.call(
            abi.encodeWithSignature(
                "transferToken(address,address,address,int64)",
                token, sender, receiver, amount
            )
        );
        if (success && result.length >= 32) {
            transferResponseCode = abi.decode(result, (int32));
        } else {
            transferResponseCode = -1;
        }
    }

    function transfer(address token, address sender, address receiver, int64 amount) external {
        (bool success, bytes memory result) = HTS_PRECOMPILE.call(
            abi.encodeWithSignature(
                "transferToken(address,address,address,int64)",
                token, sender, receiver, amount
            )
        );
        if (success && result.length >= 32) {
            transferResponseCode = abi.decode(result, (int32));
        } else {
            transferResponseCode = -1;
        }
    }
}
