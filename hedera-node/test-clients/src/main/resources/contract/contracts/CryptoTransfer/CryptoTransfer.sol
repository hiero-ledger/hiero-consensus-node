// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";

contract CryptoTransfer is HederaTokenService {

    constructor() public payable {
    }

    function transferMultipleTokens(IHederaTokenService.TokenTransferList[] memory tokenTransfers) external {
        int response = HederaTokenService.cryptoTransfer(tokenTransfers);
        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Crypto Transfer Failed");
        }
    }

    function sendViaTransferWithAmount(address payable _to, uint256 amount) public {
        _to.transfer(amount);
    }

    function sendViaTransfer(address payable _to) public payable {
        _to.transfer(msg.value);
    }

    function sendViaSend(address payable _to) public payable {
        bool sent = _to.send(msg.value);
        require(sent, "Failed to send Ether");
    }

    function sendViaCall(address payable _to) public payable {
        (bool sent, bytes memory data) = _to.call{value: msg.value}("");
        require(sent, "Failed to send Ether");
    }
}
