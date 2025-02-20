// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;
import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract TokenDefaultKycAndFreezeStatus is HederaTokenService {

    function getTokenDefaultFreeze(address token) public {
        (int response,bool frozen) = HederaTokenService.getTokenDefaultFreezeStatus(token);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("getTokenDefaultFreezeStatus failed!");
        }
    }

    function getTokenDefaultKyc(address token) public {
        (int response,bool frozen) = HederaTokenService.getTokenDefaultKycStatus(token);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("getTokenDefaultKycStatus failed!");
        }
    }

}
