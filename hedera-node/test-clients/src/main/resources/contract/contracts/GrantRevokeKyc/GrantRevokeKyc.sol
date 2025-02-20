// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;
import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract GrantRevokeKyc is HederaTokenService {

    function isKycGranted(address token, address account) public {
        (int response,bool kycGranted) = HederaTokenService.isKyc(token, account);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token isKyc failed!");
        }
    }

    function tokenGrantKyc(address token, address account) public {
        int response = HederaTokenService.grantTokenKyc(token, account);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token kyc grant failed!");
        }
    }

    function tokenRevokeKyc(address token, address account) public {
        int response = HederaTokenService.revokeTokenKyc(token, account);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token kyc revoke failed!");
        }
    }
}
