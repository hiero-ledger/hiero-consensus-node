// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;
import "./hip-206/HederaTokenService.sol";
import "./hip-206/HederaResponseCodes.sol";
import "../../solidity/hip-206/HederaTokenService.sol";

contract BurnToken is HederaTokenService {

    event BurnedTokenInfo(uint64 indexed totalSupply) anonymous;
    address tokenAddress;

    constructor(address _tokenAddress) public {
        tokenAddress = _tokenAddress;
    }

   function burnToken(uint64 amount, int64[] memory serialNumbers) public {
        (int response, uint64 newTotalSupply) = HederaTokenService.burnToken(tokenAddress, amount, serialNumbers);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }

   function burnTokenWithEvent(uint64 amount, int64[] memory serialNumbers) public {
        (int response, uint64 newTotalSupply) = HederaTokenService.burnToken(tokenAddress, amount, serialNumbers);

        emit BurnedTokenInfo(newTotalSupply);
        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }
}
