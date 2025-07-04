//SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

import "./IHederaTokenService.sol";
import "./KeyHelper.sol";
import {Structs, NumericHelperV2, NumericHelperV3} from "./NumericHelper.sol";

contract NumericContractComplex is KeyHelper {

    int32 public constant SUCCESS_CODE = 22;

    /*´:°•.°+.*•´.*:˚.°*.˚•´.°:°•.°•.*•´.*:˚.°*.˚•´.°:°•.°+.*•´.*:*/
    /*             Utilities for building HederaToken             */
    /*.•°:°.´+˚.*°.˚:*.´•*.+°.•°:´*.´•*.•°.•°:°.´:•˚°.*°.˚:*.´+°.•*/
    function buildFixedFeeV1(uint32 amount) private returns (IHederaTokenService.FixedFee memory fixedFee) {
        fixedFee.amount = amount;
        fixedFee.useHbarsForPayment = true;
        fixedFee.feeCollector = msg.sender;
        return fixedFee;
    }

    function buildFixedFeeV2(int64 amount) private returns (Structs.FixedFeeV2 memory fixedFee) {
        fixedFee = Structs.FixedFeeV2(amount, address(0), true, false, msg.sender);
    }

    function buildTokenV1(uint32 expirySecond, uint32 expiryRenew, uint32 maxSupply)
    private
    returns (IHederaTokenService.HederaToken memory token)
    {
        IHederaTokenService.TokenKey[] memory keys = new IHederaTokenService.TokenKey[](1);
        keys[0] = getSingleKey(0, 1, "");
        token = IHederaTokenService.HederaToken({
            name: "NAME",
            symbol: "SYMBOL",
            treasury: address(this),
            memo: "MEMO",
            tokenSupplyType: true,
            maxSupply: maxSupply,
            freezeDefault: false,
            tokenKeys: keys,
            expiry: IHederaTokenService.Expiry(expirySecond, address(this), expiryRenew)
        });
    }

    function buildDefaultStructFrom(
        address autoRenewAccount,
        uint32 autoRenewPeriod
    ) internal returns (IHederaTokenService.HederaToken memory token) {
        IHederaTokenService.TokenKey[] memory keys = new IHederaTokenService.TokenKey[](1);
        keys[0] = getSingleKey(0, 1, "");

        IHederaTokenService.Expiry memory expiry;
        expiry.autoRenewAccount = autoRenewAccount;
        expiry.autoRenewPeriod = autoRenewPeriod;

        token.name = "NAME";
        token.symbol = "SYMBOL";
        token.treasury = address(this);
        token.tokenKeys = keys;
        token.expiry = expiry;
        token.memo = "MEMO";
        token.maxSupply = 10000;
    }

    function buildTokenV2(uint32 expirySecond, uint32 expiryRenew, int64 maxSupply)
    private
    returns (Structs.HederaTokenV2 memory token)
    {
        IHederaTokenService.TokenKey[] memory keys = new IHederaTokenService.TokenKey[](1);
        keys[0] = getSingleKey(0, 1, "");

        token = Structs.HederaTokenV2({
            name: "NAME",
            symbol: "SYMBOL",
            treasury: address(this),
            memo: "MEMO",
            tokenSupplyType: true,
            maxSupply: maxSupply,
            freezeDefault: false,
            tokenKeys: keys,
            expiry: IHederaTokenService.Expiry(expirySecond, address(this), expiryRenew)
        });
    }

    function buildTokenV3(int64 expirySecond, int64 expiryRenew, int64 maxSupply)
    private
    returns (Structs.HederaTokenV3 memory token)
    {
        token = Structs.HederaTokenV3({
            name: "NAME",
            symbol: "SYMBOL",
            treasury: address(this),
            memo: "MEMO",
            tokenSupplyType: true,
            maxSupply: maxSupply,
            freezeDefault: false,
            tokenKeys: new IHederaTokenService.TokenKey[](0),
            expiry: Structs.ExpiryV2(expirySecond, address(this), expiryRenew)
        });
    }


    /*´:°•.°+.*•´.*:˚.°*.˚•´.°:°•.°•.*•´.*:˚.°*.˚•´.°:°•.°+.*•´.*:*/
    /*         Non-static Complex HTS functions - Create          */
    /*.•°:°.´+˚.*°.˚:*.´•*.+°.•°:´*.´•*.•°.•°:°.´:•˚°.*°.˚:*.´+°.•*/
    function createFungibleTokenWithCustomFeesFixedFee(uint32 fixedFee) public payable {
        IHederaTokenService.HederaToken memory token = buildTokenV1({
            expirySecond: 0, expiryRenew: 3_000_000, maxSupply: 10000});

        IHederaTokenService.FixedFee[] memory _fixedFee = new IHederaTokenService.FixedFee[](1);
        _fixedFee[0] = buildFixedFeeV1(fixedFee);
        (bool success, bytes memory result) = address(0x167).call{value: msg.value}(
            abi.encodeWithSelector(IHederaTokenService.createFungibleTokenWithCustomFees.selector, token, uint(100), uint(2), _fixedFee, new IHederaTokenService.FractionalFee[](0))
        );

        (int32 responseCode, address addressToken) =
            success
                ? abi.decode(result, (int32, address))
                : (int32(0), address(0));
        require(responseCode == SUCCESS_CODE);
    }

    function createFungibleTokenWithCustomFeesFractionalFee(uint32 numerator, uint32 denominator, uint32 minimumAmount, uint32 maximumAmount) public payable {
        IHederaTokenService.HederaToken memory token = buildTokenV1({
            expirySecond: 0, expiryRenew: 3_000_000, maxSupply: 10000});

        IHederaTokenService.FractionalFee[] memory fractionalFee = new IHederaTokenService.FractionalFee[](1);
        fractionalFee[0] = IHederaTokenService.FractionalFee(numerator, denominator, minimumAmount, maximumAmount, false, address(this));

        (bool success, bytes memory result) = address(0x167).call{value: msg.value}(
            abi.encodeWithSelector(IHederaTokenService.createFungibleTokenWithCustomFees.selector, token, uint(100), uint(2), new IHederaTokenService.FixedFee[](0), fractionalFee)
        );

        (int32 responseCode, address addressToken) =
            success
                ? abi.decode(result, (int32, address))
                : (int32(0), address(0));
        require(responseCode == SUCCESS_CODE);
    }

    function createFungibleTokenWithCustomFeesV3FixedFee(int64 fixedFee) public payable {
        Structs.HederaTokenV3 memory token = buildTokenV3({
            expirySecond: 0, expiryRenew: 3_000_000, maxSupply: 10000});

        Structs.FixedFeeV2[] memory _fixedFee = new Structs.FixedFeeV2[](1);
        _fixedFee[0] = buildFixedFeeV2(fixedFee);

        (bool success, bytes memory result) = address(0x167).call{value: msg.value}(
            abi.encodeWithSelector(NumericHelperV3.createFungibleTokenWithCustomFees.selector, token, int64(100), int64(2), _fixedFee, new Structs.FractionalFeeV2[](0))
        );

        (int32 responseCode, address addressToken) =
            success
                ? abi.decode(result, (int32, address))
                : (int32(0), address(0));
        require(responseCode == SUCCESS_CODE);
    }

    // Test-note: We skip V2 test, as its already validated via normal create flow.
    function createFungibleTokenWithCustomFeesV3FractionalFee(int64 numerator, int64 denominator, int64 minimumAmount, int64 maximumAmount) public payable {
        Structs.HederaTokenV3 memory token = buildTokenV3({
            expirySecond: 0, expiryRenew: 3_000_000, maxSupply: 10000});

        Structs.FractionalFeeV2[] memory fractionalFees = new Structs.FractionalFeeV2[](1);
        fractionalFees[0] = Structs.FractionalFeeV2(numerator, denominator, minimumAmount, maximumAmount, false, address(this));

        (bool success, bytes memory result) = address(0x167).call{value: msg.value}(
            abi.encodeWithSelector(NumericHelperV3.createFungibleTokenWithCustomFees.selector, token, int64(100), int64(2), new Structs.FixedFeeV2[](0), fractionalFees));
        (int32 responseCode, address addressToken) =
            success
                ? abi.decode(result, (int32, address))
                : (int32(0), address(0));
        require(responseCode == SUCCESS_CODE);
    }

    function createNonFungibleTokenWithCustomFeesV3RoyaltyFees(bytes memory key, int64 numerator, int64 denominator, int64 amount) public payable {
        Structs.HederaTokenV3 memory token = buildTokenV3({
            expirySecond: 0, expiryRenew: 3_000_000, maxSupply: 10000});
        IHederaTokenService.TokenKey[] memory keys = getAllTypeKeys(3, key);
        token.tokenKeys = keys;

        Structs.RoyaltyFeeV2[] memory royaltyFees = new Structs.RoyaltyFeeV2[](1);
        Structs.RoyaltyFeeV2 memory royalty;
        royalty.numerator = numerator;
        royalty.denominator = denominator;
        royalty.amount = amount;
        royalty.useHbarsForPayment = true;
        royalty.feeCollector = address(this);
        royaltyFees[0] = royalty;


        (bool success, bytes memory result) = address(0x167).call{value: msg.value}(
            abi.encodeWithSelector(NumericHelperV3.createNonFungibleTokenWithCustomFees.selector, token, new Structs.FixedFeeV2[](0), royaltyFees)
        );

        (int32 responseCode, address addressToken) =
            success
                ? abi.decode(result, (int32, address))
                : (int32(0), address(0));
        require(responseCode == SUCCESS_CODE);
    }

    // expirySecond: 0, not tested because '_expiryRenew' has a priority
    function createFungibleToken(uint32 _expiryRenew, uint32 _maxSupply, uint initialTotalSupply, uint decimals) public payable {
        IHederaTokenService.HederaToken memory token = buildTokenV1({
            expirySecond: 0, expiryRenew: _expiryRenew, maxSupply: _maxSupply});

        (bool success, bytes memory result) = address(0x167).call{value: msg.value}(
            abi.encodeWithSelector(IHederaTokenService.createFungibleToken.selector, token, initialTotalSupply, decimals)
        );
        (int32 responseCode, address addressToken) =
            success
                ? abi.decode(result, (int32, address))
                : (int32(0), address(0));
        require(responseCode == SUCCESS_CODE);
    }

    // expirySecond: 0, not tested because '_expiryRenew' has a priority
    function createFungibleTokenV2(uint32 _expiryRenew, int64 _maxSupply, uint64 initialTotalSupply, uint32 decimals) public payable {
        Structs.HederaTokenV2 memory token = buildTokenV2({
            expirySecond: 0, expiryRenew: _expiryRenew, maxSupply: _maxSupply});

        (bool success, bytes memory result) = address(0x167).call{value: msg.value}(
            abi.encodeWithSelector(NumericHelperV2.createFungibleToken.selector, token, initialTotalSupply, decimals)
        );
        (int32 responseCode, address addressToken) =
            success
                ? abi.decode(result, (int32, address))
                : (int32(0), address(0));
        require(responseCode == SUCCESS_CODE);
    }

    // expirySecond: 0, not tested because '_expiryRenew' has a priority
    function createFungibleTokenV3(int64 _expiryRenew, int64 _maxSupply, int64 initialTotalSupply, int32 decimals) public payable {
        Structs.HederaTokenV3 memory token = buildTokenV3({
            expirySecond: 0, expiryRenew: _expiryRenew, maxSupply: _maxSupply});

        (bool success, bytes memory result) = address(0x167).call{value: msg.value}(
            abi.encodeWithSelector(NumericHelperV3.createFungibleToken.selector, token, initialTotalSupply, decimals)
        );
        (int32 responseCode, address addressToken) =
            success
                ? abi.decode(result, (int32, address))
                : (int32(0), address(0));
        require(responseCode == SUCCESS_CODE);
    }

    // expirySecond: 0, not tested because '_expiryRenew' has a priority
    function createNonFungibleTokenV2(bytes memory key, uint32 _expiryRenew, int64 _maxSupply) public payable {
        Structs.HederaTokenV2 memory token = buildTokenV2({
            expirySecond: 0, expiryRenew: _expiryRenew, maxSupply: _maxSupply});
        IHederaTokenService.TokenKey[] memory keys = getAllTypeKeys(3, key);
        token.tokenKeys = keys;

        (bool success, bytes memory result) = address(0x167).call{value: msg.value}(
            abi.encodeWithSelector(NumericHelperV2.createNonFungibleToken.selector, token)
        );
        (int32 responseCode, address addressToken) =
            success
                ? abi.decode(result, (int32, address))
                : (int32(0), address(0));
        require(responseCode == SUCCESS_CODE);
    }

    // expirySecond: 0, not tested because '_expiryRenew' has a priority
    function createNonFungibleTokenV3(bytes memory key, int64 _expiryRenew, int64 _maxSupply) public payable {
        Structs.HederaTokenV3 memory token = buildTokenV3({
            expirySecond: 0, expiryRenew: _expiryRenew, maxSupply: _maxSupply});
        IHederaTokenService.TokenKey[] memory keys = getAllTypeKeys(3, key);
        token.tokenKeys = keys;

        (bool success, bytes memory result) = address(0x167).call{value: msg.value}(
            abi.encodeWithSelector(NumericHelperV3.createNonFungibleToken.selector, token)
        );
        (int32 responseCode, address addressToken) =
            success
                ? abi.decode(result, (int32, address))
                : (int32(0), address(0));
        require(responseCode == SUCCESS_CODE);
    }

    /*´:°•.°+.*•´.*:˚.°*.˚•´.°:°•.°•.*•´.*:˚.°*.˚•´.°:°•.°+.*•´.*:*/
    /*         Non-static Complex HTS functions - Update          */
    /*.•°:°.´+˚.*°.˚:*.´•*.+°.•°:´*.´•*.•°.•°:°.´:•˚°.*°.˚:*.´+°.•*/
    function updateTokenInfoV2(address token, int64 _maxSupply) public {
        Structs.HederaTokenV2 memory newToken;
        newToken.maxSupply = _maxSupply;

        (bool success, bytes memory result) = address(0x167).call(
            abi.encodeWithSelector(NumericHelperV2.updateTokenInfo.selector, token, newToken));

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    function updateTokenInfoV3(address token, int64 _expirySecond, int64 _expiryRenew, int64 _maxSupply) public {
        Structs.HederaTokenV3 memory newToken;
        newToken.expiry = Structs.ExpiryV2(_expirySecond, address(this), _expiryRenew);
        newToken.maxSupply = _maxSupply;

        (bool success, bytes memory result) = address(0x167).call(
            abi.encodeWithSelector(NumericHelperV3.updateTokenInfo.selector, token, newToken));

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    /*´:°•.°+.*•´.*:˚.°*.˚•´.°:°•.°•.*•´.*:˚.°*.˚•´.°:°•.°+.*•´.*:*/
    /*        Non-static Complex HTS functions - Transfer         */
    /*.•°:°.´+˚.*°.˚:*.´•*.+°.•°:´*.´•*.•°.•°:°.´:•˚°.*°.˚:*.´+°.•*/
    function cryptoTransferFungibleV1(address tokenToTransfer, int64[] memory amounts, address sender, address receiver) public {
        IHederaTokenService.AccountAmount[] memory accountAmounts = new IHederaTokenService.AccountAmount[](2);
        accountAmounts[0] = IHederaTokenService.AccountAmount(sender, amounts[0]);
        accountAmounts[1] = IHederaTokenService.AccountAmount(receiver, amounts[1]);

        IHederaTokenService.TokenTransferList[] memory tokenTransfers = new IHederaTokenService.TokenTransferList[](1);
        tokenTransfers[0] = IHederaTokenService.TokenTransferList(tokenToTransfer, accountAmounts, new IHederaTokenService.NftTransfer[](0));

        (bool success, bytes memory result) = address(0x167).call(
            abi.encodeWithSelector(IHederaTokenService.cryptoTransfer.selector, tokenTransfers));

        int64 responseCode = abi.decode(result, (int64));
        require(responseCode == SUCCESS_CODE);
    }

    /* CryptoTransferV2 allows to specify Hbars for the transfer, instead of just tokens */
    function cryptoTransferV2(int64[] memory amounts, address sender, address receiver) public {
        Structs.AccountAmount[] memory accountAmounts = new Structs.AccountAmount[](2);
        accountAmounts[0] = Structs.AccountAmount(sender, amounts[0], false);
        accountAmounts[1] = Structs.AccountAmount(receiver, amounts[1], false);

        Structs.TransferList memory hbarTransfers = Structs.TransferList(accountAmounts);

        (bool success, bytes memory result) = address(0x167).call(
            abi.encodeWithSelector(NumericHelperV2.cryptoTransfer.selector, hbarTransfers, new IHederaTokenService.TokenTransferList[](0)));

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    function cryptoTransferNonFungible(address token, address sender, address receiver, int64 serialNumber) public {
        IHederaTokenService.NftTransfer[] memory nftTransfers = new IHederaTokenService.NftTransfer[](1);
        nftTransfers[0] = IHederaTokenService.NftTransfer(sender, receiver, serialNumber);

        IHederaTokenService.TokenTransferList[] memory tokenTransfers = new IHederaTokenService.TokenTransferList[](1);
        tokenTransfers[0] = IHederaTokenService.TokenTransferList(token, new IHederaTokenService.AccountAmount[](0), nftTransfers);

        (bool success, bytes memory result) = address(0x167).call(
            abi.encodeWithSelector(IHederaTokenService.cryptoTransfer.selector, tokenTransfers));

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    function transferTokenTest(address token, address sender, address receiver, int64 amount) public {
        (bool success, bytes memory result) = address(0x167).call(
            abi.encodeWithSignature("transferToken(address,address,address,int64)", token, sender, receiver, amount)
        );

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    function transferNFTs(
        address token,
        address sender,
        address receiver,
        int64[] memory serialNumbers
    ) public {
        address[] memory senders = new address[](1);
        senders[0] = sender;

        address[] memory receivers = new address[](1);
        receivers[0] = receiver;

        (bool success, bytes memory result) = address(0x167).call(
            abi.encodeWithSignature(
                "transferNFTs(address,address[],address[],int64[])", token, senders, receivers, serialNumbers
            )
        );

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    function transferNFTTest(address token, address sender, address receiver, int64 serialNumber) public {
        (bool success, bytes memory result) = address(0x167).call(
            abi.encodeWithSignature("transferNFT(address,address,address,int64)", token, sender, receiver, serialNumber)
        );

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    function transferFrom(address token, address from, address to, uint256 amount) public {
        (bool success, bytes memory result) = address(0x167).call(
            abi.encodeWithSignature("transferFrom(address,address,address,uint256)", token, from, to, amount)
        );

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    function transferFromERC(address token, address from, address to, uint256 amount) public {
        (bool success, bytes memory result) =
                                address(token).call(abi.encodeWithSignature("transferFrom(address,address,uint256)", from, to, amount));

        bool resultBool = abi.decode(result, (bool));
        require(resultBool);
    }

    function transferFromNFT(address token, address from, address to, uint256 serialNumber) public {
        (bool success, bytes memory result) = address(0x167).call(
            abi.encodeWithSignature("transferFromNFT(address,address,address,uint256)", token, from, to, serialNumber)
        );

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

}
