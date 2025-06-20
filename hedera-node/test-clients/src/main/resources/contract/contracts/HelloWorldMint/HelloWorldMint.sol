// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.12;
pragma experimental ABIEncoderV2;

import "./IHederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract HelloWorldMint is HederaResponseCodes {
    address constant precompileAddress = address(0x167);

    address tokenAddress;

    constructor(address _tokenAddress) public {
        tokenAddress = _tokenAddress;
    }

    function mint() public {
        (int responseCode, uint64 newTotalSupply, int[] memory serialNumbers) = mintToken(tokenAddress, 0, new bytes[](1));
        if (responseCode != 22) {
            revert();
        }
    }

    function brrr(uint64 amount) public {
        (int responseCode, uint64 newTotalSupply, int[] memory serialNumbers) = mintToken(tokenAddress, amount, new bytes[](0));
        if (responseCode != 22 || serialNumbers.length > 0) {
            revert();
        }
    }

    function mintToken(address token, uint64 amount, bytes[] memory metadata) internal
    returns (int responseCode, uint64 newTotalSupply, int[] memory serialNumbers)
    {
        (bool success, bytes memory result) = precompileAddress.call(
            abi.encodeWithSelector(IHederaTokenService.mintToken.selector,
            token, amount, metadata));
        (responseCode, newTotalSupply, serialNumbers) =
        success
        ? abi.decode(result, (int32, uint64, int[]))
        : (HederaResponseCodes.UNKNOWN, 0, new int[](0));
    }
}
