// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract MintTokenContract is HederaTokenService {

    function mintToken(uint64 amount, address tokenAddress) public {
        (int response, uint64 newTotalSupply, int[] memory serialNumbers) = HederaTokenService.mintToken(tokenAddress, amount, new bytes[](0));

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token mint failed");
        }
    }
}
