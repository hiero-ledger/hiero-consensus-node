// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";

contract Airdrop is HederaTokenService {
    function tokenAirdrop(address token, address sender, address receiver, int64 amount) public payable returns (int64 responseCode) {
        IHederaTokenService.TokenTransferList[] memory tokenTransfers = new IHederaTokenService.TokenTransferList[](1);
        IHederaTokenService.TokenTransferList memory airdrop;

        airdrop.token = token;
        airdrop.transfers = prepareAA(sender, receiver, amount);
        tokenTransfers[0] = airdrop;
        responseCode = airdropTokens(tokenTransfers);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }

    function nftAirdrop(address token, address sender, address receiver, int64 serial) public payable returns (int64 responseCode) {
        IHederaTokenService.TokenTransferList[] memory tokenTransfers = new IHederaTokenService.TokenTransferList[](1);
        IHederaTokenService.TokenTransferList memory airdrop;

        airdrop.token = token;
        IHederaTokenService.NftTransfer memory nftTransfer = prepareNftTransfer(sender, receiver, serial);
        IHederaTokenService.NftTransfer[] memory nftTransfers = new IHederaTokenService.NftTransfer[](1);
        nftTransfers[0] = nftTransfer;
        airdrop.nftTransfers = nftTransfers;
        tokenTransfers[0] = airdrop;
        responseCode = airdropTokens(tokenTransfers);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }

    function tokenNAmountAirdrops(address[] memory tokens, address[] memory senders, address[] memory receivers, int64 amount) public payable returns (int64 responseCode) {
        uint256 length = senders.length;
        IHederaTokenService.TokenTransferList[] memory tokenTransfers = new IHederaTokenService.TokenTransferList[](length);
        for (uint256 i = 0; i < length; i++)
        {
            IHederaTokenService.TokenTransferList memory airdrop;
            airdrop.token = tokens[i];
            airdrop.transfers = prepareAA(senders[i], receivers[i], amount);
            tokenTransfers[i] = airdrop;
        }
        responseCode = airdropTokens(tokenTransfers);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }

    function nftNAmountAirdrops(address[] memory nft, address[] memory senders, address[] memory receivers, int64[] memory serials) public payable returns (int64 responseCode) {
        uint256 length = nft.length;
        IHederaTokenService.TokenTransferList[] memory tokenTransfers = new IHederaTokenService.TokenTransferList[](length);
        for (uint256 i = 0; i < length; i++)
        {
            IHederaTokenService.TokenTransferList memory airdrop;
            airdrop.token = nft[i];
            IHederaTokenService.NftTransfer[] memory nftTransfers = new IHederaTokenService.NftTransfer[](1);
            nftTransfers[0] = prepareNftTransfer(senders[i], receivers[i], serials[i]);
            airdrop.nftTransfers = nftTransfers;
            tokenTransfers[i] = airdrop;
        }
        responseCode = airdropTokens(tokenTransfers);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }

    function tokenAirdropDistribute(address token, address sender, address[] memory receivers, int64 amount) public payable returns (int64 responseCode) {
        uint256 length = receivers.length + 1;
        IHederaTokenService.TokenTransferList[] memory tokenTransfers = new IHederaTokenService.TokenTransferList[](1);
        IHederaTokenService.TokenTransferList memory airdrop;
        airdrop.token = token;
        IHederaTokenService.AccountAmount memory senderAA;
        senderAA.accountID = sender;
        int64 totalAmount = 0;
        for (uint i = 0; i < receivers.length; i++) {
            totalAmount += amount;
        }
        senderAA.amount = -totalAmount;
        IHederaTokenService.AccountAmount[] memory transfers = new IHederaTokenService.AccountAmount[](length);
        transfers[0] = senderAA;
        for (uint i = 1; i < length; i++)
        {
            IHederaTokenService.AccountAmount memory receiverAA;
            receiverAA.accountID = receivers[i];
            receiverAA.amount = amount;
            transfers[i] = receiverAA;
        }
        airdrop.transfers = transfers;
        tokenTransfers[0] = airdrop;
        responseCode = airdropTokens(tokenTransfers);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }

    function nftAirdropDistribute(address token, address sender, address[] memory receivers) public payable returns (int64 responseCode) {
        uint256 length = receivers.length;
        IHederaTokenService.TokenTransferList[] memory tokenTransfers = new IHederaTokenService.TokenTransferList[](1);
        IHederaTokenService.TokenTransferList memory airdrop;
        airdrop.token = token;
        IHederaTokenService.NftTransfer[] memory nftTransfers = new IHederaTokenService.NftTransfer[](length);
        int64 serial = 1;
        for (uint i = 0; i < length; i++) {
            nftTransfers[i] = prepareNftTransfer(sender, receivers[i], serial);
            serial++;
        }
        airdrop.nftTransfers = nftTransfers;
        tokenTransfers[0] = airdrop;

        responseCode = airdropTokens(tokenTransfers);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }

    function mixedAirdrop(address[] memory token, address[] memory nft, address[] memory tokenSenders, address[] memory tokenReceivers, address[] memory nftSenders, address[] memory nftReceivers, int64 tokenAmount, int64[] memory serials) public payable returns (int64 responseCode) {
        uint256 length = tokenSenders.length + nftSenders.length;
        IHederaTokenService.TokenTransferList[] memory tokenTransfers = new IHederaTokenService.TokenTransferList[](length);
        for (uint i = 0; i < tokenSenders.length; i++)
        {
            IHederaTokenService.TokenTransferList memory airdrop;
            airdrop.token = token[i];
            airdrop.transfers = prepareAA(tokenSenders[i], tokenReceivers[i], tokenAmount);
            tokenTransfers[i] = airdrop;
        }
        uint nftIndex = tokenSenders.length;
        for (uint v = 0; nftIndex < length; v++)
        {
            IHederaTokenService.TokenTransferList memory airdrop;
            airdrop.token = nft[v];
            IHederaTokenService.NftTransfer[] memory nftTransfers = new IHederaTokenService.NftTransfer[](1);
            nftTransfers[0] = prepareNftTransfer(nftSenders[v], nftReceivers[v], serials[v]);
            airdrop.nftTransfers = nftTransfers;
            tokenTransfers[nftIndex] = airdrop;
            nftIndex++;
        }
        responseCode = airdropTokens(tokenTransfers);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }

    function distributeMultipleTokens(address[] memory tokens, address sender, address[] memory receivers, int64 amount)
    public payable returns (int64 responseCode) {
        uint256 length = tokens.length;
        IHederaTokenService.TokenTransferList[] memory tokenTransfers = new IHederaTokenService.TokenTransferList[](length);
        for (uint256 i = 0; i < length; i++)
        {
            IHederaTokenService.TokenTransferList memory airdrop;
            airdrop.token = tokens[i];
            IHederaTokenService.AccountAmount memory senderAA;
            senderAA.accountID = sender;
            uint256 receiversLength = receivers.length;
            int64 totalAmount = 0;
            for (uint x = 0; x < receiversLength; x++) {
                totalAmount += amount;
            }
            senderAA.amount = -totalAmount;
            IHederaTokenService.AccountAmount[] memory transfers = new IHederaTokenService.AccountAmount[](receiversLength + 1);
            transfers[0] = senderAA;
            for (uint y = 0; y < receiversLength; y++)
            {
                IHederaTokenService.AccountAmount memory receiverAA;
                receiverAA.accountID = receivers[y];
                receiverAA.amount = amount;
                transfers[y + 1] = receiverAA;
            }
            airdrop.transfers = transfers;
            tokenTransfers[i] = airdrop;
        }

        responseCode = airdropTokens(tokenTransfers);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return responseCode;
    }

    function prepareAA(address sender, address receiver, int64 amount) internal pure returns (IHederaTokenService.AccountAmount[] memory transfers) {
        IHederaTokenService.AccountAmount memory aa1;
        aa1.accountID = sender;
        aa1.amount = -amount;
        IHederaTokenService.AccountAmount memory aa2;
        aa2.accountID = receiver;
        aa2.amount = amount;
        transfers = new IHederaTokenService.AccountAmount[](2);
        transfers[0] = aa1;
        transfers[1] = aa2;
        return transfers;
    }

    function prepareNftTransfer(address sender, address receiver, int64 serial) internal pure returns (IHederaTokenService.NftTransfer memory nftTransfer) {
        nftTransfer.senderAccountID = sender;
        nftTransfer.receiverAccountID = receiver;
        nftTransfer.serialNumber = serial;
        return nftTransfer;
    }
}
