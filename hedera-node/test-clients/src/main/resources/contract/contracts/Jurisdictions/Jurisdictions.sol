// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.24;
pragma experimental ABIEncoderV2;

import "./Ownable.sol";
import "./SafeMath.sol";


/// @title Jurisdictions
contract Jurisdictions is Ownable {

    using SafeMath for uint256;

    struct Jurisdiction {
        bytes32 code;
        string name;
        uint taxRate;
        address inventory; // bitcarbon's inventory address
        address reserve; // bitcarbon's reserve address
    }

    /// @notice Make sure incoming calls only come from BCDA contract
    modifier onlyAdmin(){
        require(msg.sender == admin, "Caller not Bcda");
        _;
    }


    /// @notice Address of BCDA contract
    address admin;
    /// @notice List of jurisdiction codes
    bytes32[] codes;

    uint rate = 96711214693058;

    /// @notice jurisdiction code => jurisdicton info
    mapping (bytes32 => Jurisdiction) public jurisdictions;
    /// @notice  jurisdiction code => if jurisdiction exists
    mapping (bytes32 => bool) jurisidictionExists;
    /// @notice address => if its a registered bitcarbon address
    mapping (address => bool) public isBitcarbon;
    /// @notice bitcarbon address => its jurisdiction code
    mapping (address => bytes32) public bitcarbonJurisdiction;
    /// @notice bitcarbon address => list of approval pending tokens
    mapping (address => uint[]) inventoryApprovalTokens;

    event JurisdictionAdded(bytes32 code, string name, uint taxRate, address inventory, address reserve, uint timestamp);
    event JurisdictionRemoved(bytes32 code, uint timestamp);
    event TaxRateChanged(uint oldTaxRate, uint newTaxRate, uint timestamp);

    /// @notice Initialize contract with exchange rate contract
    /// @param _admin Address of admin
    constructor(address _admin) public {
        admin = _admin;
    }

    /// @notice Onboard a new jurisdicton
    /// @param name Name of jurisdicton
    /// @param taxRate Tax rate of jurisdictions
    /// @param inventory Inventory address of jurisdicton
    /// @param reserve Reserve address of jurisdicton
    function add(string name, uint taxRate, address inventory, address reserve) public onlyOwner(){
        bytes32 code = keccak256(abi.encodePacked(name));
        require(!jurisidictionExists[code], "Jurisdiction already in use");
        require(!isBitcarbon[inventory], "Inventory address already in use");
        require(!isBitcarbon[reserve], "Reserve address already in use");
        Jurisdiction memory newJurisdiction = Jurisdiction({
        code : code,
        name : name,
        taxRate : taxRate,
        inventory : inventory,
        reserve : reserve
        });
        jurisdictions[code] = newJurisdiction;
        codes.push(code);
        jurisidictionExists[code] = true;
        isBitcarbon[reserve] = true;
        isBitcarbon[inventory] = true;
        bitcarbonJurisdiction[reserve] = code;
        bitcarbonJurisdiction[inventory] = code;
        emit JurisdictionAdded(code, name, taxRate, inventory, reserve, now);
    }

    /// @notice Delete a jurisdicton
    /// @param code Jurisdiction code
    function remove(bytes32 code) public onlyOwner() {
        require(jurisidictionExists[code], "Jurisdiction code not in in use");
        address reserve = jurisdictions[code].reserve;
        address inventory = jurisdictions[code].inventory;
        delete jurisdictions[code];
        jurisidictionExists[code] = false;
        if(codes.length == 1){
            codes.length = codes.length.sub(1);
        }
        else {
            for(uint i = 0; i < codes.length; i++){
                if(code == codes[i]){
                    if(i == codes.length.sub(1)){
                        codes.length = codes.length.sub(1);
                    }
                    else{
                        codes[i] = codes[codes.length.sub(1)];
                        delete codes[codes.length.sub(1)];
                        codes.length = codes.length.sub(1);
                    }
                }
            }
        }
        jurisidictionExists[code] = false;
        isBitcarbon[reserve] = false;
        isBitcarbon[inventory] = false;
        delete bitcarbonJurisdiction[reserve];
        delete bitcarbonJurisdiction[inventory];
        emit JurisdictionRemoved(code, now);
    }

    /// @notice Code list of all registered jurisdictions
    function getCodes() public view returns(bytes32[]){
        return codes;
    }

    /// @notice List of all token pending acceptance by inventory
    /// @param inventory Address of Inventory
    function getPendingTokens(address inventory) public view returns(uint[]){
        return inventoryApprovalTokens[inventory];
    }

    /// @notice Get reserve address of a jurisdicton
    /// @param code Jurisdiction code
    function getReserve(bytes32 code) public view returns(address){
        return jurisdictions[code].reserve;
    }

    /// @notice  Get inventory address of a jurisdicton
    /// @param code Jurisdiction code
    function getInventory(bytes32 code) public view returns(address){
        return jurisdictions[code].inventory;
    }

    /// @notice Tax of a jurisdicton
    /// @param code Jurisdiction code
    function getTaxRate(bytes32 code) public view returns(uint) {
        return jurisdictions[code].taxRate;
    }

    /// @notice Tax due for a particular value
    /// @param priceCents Value in cents
    /// @param code Jurisdiction code
    function getTaxes(uint priceCents, bytes32 code) public view returns(uint, uint){
        uint taxRate = getTaxRate(code);
        uint taxCents = (priceCents.mul(taxRate)).div(10000);
        uint taxWei = taxCents.mul(rate);
        return (taxCents, taxWei);
    }


    function setTaxRate(bytes32 code, uint taxRate) public onlyOwner() {
        Jurisdiction storage jurisdiction = jurisdictions[code];
        uint oldTaxRate = jurisdiction.taxRate;
        jurisdiction.taxRate = taxRate;
        emit TaxRateChanged(oldTaxRate, taxRate, now);
    }

    /// @notice Reset all/any of jurisdicton params
    /// @param code Jurisdiction code
    /// @param taxRate Jurisdiction tax rate
    /// @param reserve Address of jurisdicton reserve
    /// @param inventory Address of jurisdicton inventory
    function setJurisdictionParams(bytes32 code, uint taxRate, address reserve, address inventory) public onlyOwner(){
        Jurisdiction storage jurisdiction = jurisdictions[code];
        uint oldTaxRate = jurisdiction.taxRate;
        address oldInventory = jurisdiction.inventory;
        address oldReserve = jurisdiction.reserve;
        jurisdiction.taxRate = taxRate;
        jurisdiction.inventory = inventory;
        jurisdiction.reserve = reserve;
        emit TaxRateChanged(oldTaxRate, taxRate, now);
    }

    /// @notice Check if a particular jurisdicton is registered
    /// @param code Jurisdiction code
    function isValid(bytes32 code) public view returns (bool) {
        return jurisidictionExists[code];
    }


}
