// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";

contract LazyPrecompileTransfers is HederaTokenService {

    NestedPrecompileCaller precompileCaller;

    constructor() {
        precompileCaller = new NestedPrecompileCaller();
    }

    function cryptoTransferV1LazyCreate(
        IHederaTokenService.TokenTransferList[] memory tokenTransfers,
        IHederaTokenService.TokenTransferList[] memory tokenTransfers2
    ) external {
        precompileCaller.callCryptoTransferV1(tokenTransfers);
        int response = HederaTokenService.cryptoTransfer(tokenTransfers2);
        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Crypto Transfer Failed");
        }
    }
}

contract NestedPrecompileCaller is HederaTokenService {

    function callCryptoTransferV1(IHederaTokenService.TokenTransferList[] memory tokenTransfers) public {
        int response = HederaTokenService.cryptoTransfer(tokenTransfers);
        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Crypto Transfer Failed");
        }
    }
}
