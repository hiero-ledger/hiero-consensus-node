// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.12;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";

contract MintNFTContract is HederaTokenService {

    function mintNonFungibleTokenWithAddress(address tokenAddress, bytes[] memory metadata) external {
        (int response, uint64 newTotalSupply, int64[] memory serialNumbers) = HederaTokenService.mintToken(tokenAddress, 0, metadata);
        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Non Fungible mint failed!");
        }
    }
}
