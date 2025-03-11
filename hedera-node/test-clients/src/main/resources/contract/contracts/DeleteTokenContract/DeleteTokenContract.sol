// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;
import "./hip-206/HederaTokenService.sol";
import "./hip-206/HederaResponseCodes.sol";

contract DeleteTokenContract is HederaTokenService {

    function tokenDelete(address token)external{
        int response = HederaTokenService.deleteToken(token);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token deletion failed!");
        }
    }
}
