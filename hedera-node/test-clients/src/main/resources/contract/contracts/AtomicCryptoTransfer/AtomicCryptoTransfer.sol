// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;

import "./HederaTokenService.sol";
import "./IHederaTokenService.sol";

contract CryptoTransferV2 is HederaTokenService {

    function transferMultipleTokens(IHederaTokenService.TransferList memory transferList,
        IHederaTokenService.TokenTransferList[] memory tokenTransfers) external {
        int response = HederaTokenService.cryptoTransfer(transferList, tokenTransfers);
        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Crypto Transfer Failed");
        }
    }

    function transferMultipleTokensDelegateCall(IHederaTokenService.TransferList memory transferList,
        IHederaTokenService.TokenTransferList[] memory tokenTransfers) external {
        (bool success, bytes memory result) = precompileAddress.delegatecall(abi.encodeWithSignature("cryptoTransfer(((address,int64,bool)[]),(address,(address,int64,bool)[],(address,address,int64,bool)[])[])", transferList, tokenTransfers));
        if (!success) {
            revert ("Crypto Transfer Failed As Expected");
        }
    }

}
