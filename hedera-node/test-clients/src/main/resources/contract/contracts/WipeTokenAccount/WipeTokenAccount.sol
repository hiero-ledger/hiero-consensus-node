// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;
import "./hip-206/HederaTokenService.sol";
import "./hip-206/HederaResponseCodes.sol";

contract WipeTokenAccount is HederaTokenService {

    function wipeFungibleToken(address token, address account, uint32 amount) public {
        int response = HederaTokenService.wipeTokenAccount(token, account, amount);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token wipe failed!");
        }
    }

    function wipeNonFungibleToken(address token, address account, int64[] memory serialNumbers) public {
        int response = HederaTokenService.wipeTokenAccountNFT(token, account, serialNumbers);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("NFT wipe failed!");
        }
    }
}
