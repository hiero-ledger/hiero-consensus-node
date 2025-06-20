// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";

contract LazyPrecompileTransfersV2 is HederaTokenService {

    NestedPrecompileCaller precompileCaller;

    constructor() {
        precompileCaller = new NestedPrecompileCaller();
    }

    function cryptoTransferV2LazyCreate(
        IHederaTokenService.TransferList memory transferList,
        IHederaTokenService.TokenTransferList[] memory tokenTransfers,
        IHederaTokenService.TransferList memory transferList2,
        IHederaTokenService.TokenTransferList[] memory tokenTransfers2
    ) external {
        precompileCaller.callCryptoTransferV2(transferList, tokenTransfers);
        int response = HederaTokenService.cryptoTransfer(transferList2, tokenTransfers2);
        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Crypto Transfer Failed");
        }
    }

}

contract NestedPrecompileCaller is HederaTokenService {

    function callCryptoTransferV2(        IHederaTokenService.TransferList memory transferList, IHederaTokenService.TokenTransferList[] memory tokenTransfers) public {
        int response = HederaTokenService.cryptoTransfer(transferList, tokenTransfers);
        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Crypto Transfer Failed");
        }
    }
}
