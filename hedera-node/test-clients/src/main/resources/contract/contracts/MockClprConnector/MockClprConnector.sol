// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

import { ClprTypes } from "../types/ClprTypes.sol";
import { IClprConnector } from "../interfaces/IClprConnector.sol";
import { IClprMiddleware } from "../interfaces/IClprMiddleware.sol";
import { IERC20 } from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import { SafeERC20 } from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";

/// @title Mock CLPR Connector
/// @author Hashgraph
/// @notice Reference connector implementation that models MVP connector economics and pairing constraints.
/// @dev This contract is used as both a source-side and destination-side connector in tests.
///      It intentionally keeps logic simple while reflecting the spec requirements:
///      - authorize() approves/denies and attaches a max_charge commitment.
///      - handleMessage() reimburses the receiving middleware and returns an optional connector response payload.
contract MockClprConnector is IClprConnector {
    using SafeERC20 for IERC20;

    /// @notice Thrown when a non-admin attempts an admin-only action.
    error AdminOnly();

    /// @notice Thrown when constructed with inconsistent unit configuration.
    error InvalidUnit();

    /// @notice Thrown when attempting to reimburse without sufficient funds.
    error InsufficientFunds();

    /// @notice Connector admin authority (models protocol-level admin key control).
    address public immutable admin;

    /// @inheritdoc IClprConnector
    bytes32 public immutable override connectorId;

    /// @inheritdoc IClprConnector
    bytes32 public immutable override expectedRemoteConnectorId;

    /// @inheritdoc IClprConnector
    bytes32 public immutable override remoteLedgerId;

    /// @notice Optional ERC20 token used for this connector's local balance (zero address => native value).
    IERC20 public immutable token;

    /// @notice Unit used for this connector's local balance reporting (e.g., "ETH" or "WETH").
    string private _localUnit;

    /// @notice Safety threshold in the local unit.
    uint256 private _safetyThreshold;

    /// @notice Destination-side minimum processing charge policy (local unit).
    uint256 private _minimumCharge;

    /// @notice Destination-side maximum processing charge policy (local unit).
    uint256 private _maximumCharge;

    /// @notice Source-side outbound max charge commitment (often in the destination unit).
    ClprTypes.ClprAmount private _outboundMaxCharge;

    /// @notice When true, `authorize` always denies (used to simulate "known bad" connectors in tests).
    bool private _denyAuthorize;

    /// @notice Number of times `authorize` has been called.
    uint64 public authorizeCount;

    /// @notice Number of times this connector has been notified of a pre-enqueue rejection.
    uint64 public sendRejectedCount;

    /// @notice Number of times this connector has been notified of an inbound message handling.
    uint64 public handleMessageCount;

    /// @notice Last inbound request route-header bytes observed by this connector on destination-side handling.
    bytes public lastInboundRequestRouteData;

    /// @notice Last inbound response route-header bytes observed by this connector on destination-side handling.
    bytes public lastInboundResponseRouteData;

    /// @notice Emitted when the connector authorizes a draft.
    event Authorized(
        address indexed middleware,
        address indexed senderApplicationId,
        bytes32 indexed destinationConnectorId,
        bool approve,
        uint256 maxChargeValue,
        bytes32 applicationPayloadHash
    );

    /// @notice Emitted when the connector is notified of a pre-enqueue rejection.
    event SendRejected(
        address indexed middleware,
        uint64 indexed appMsgId,
        bytes32 indexed destinationConnectorId,
        ClprTypes.ClprSendFailureReason reason,
        ClprTypes.ClprSendFailureSide side
    );

    /// @notice Emitted when the destination-side connector reimburses the receiving middleware.
    event Reimbursed(address indexed middleware, uint256 amount, bytes32 balanceHash);

    /// @notice Emitted when the connector receives a connector response payload.
    event ConnectorResponseHandled(address indexed middleware, bytes32 payloadHash);

    /// @notice Emitted when the connector receives an application response payload.
    event ApplicationResponseHandled(address indexed middleware, uint64 indexed appMsgId, bytes32 payloadHash);

    modifier onlyAdmin() {
        if (msg.sender != admin) revert AdminOnly();
        _;
    }

    /// @param connectorId_ Local connector identifier (spec: Hash(signature_over_local_config)).
    /// @param expectedRemoteConnectorId_ Expected remote connector identifier (opaque bytes).
    /// @param remoteLedgerId_ Remote ledger id (opaque bytes).
    /// @param localUnit_ Local balance-reporting unit (e.g., "ETH" or "WETH").
    /// @param tokenAddress Optional ERC20 token used for local balance (zero address => native value).
    /// @param safetyThresholdValue Safety threshold in the local unit.
    /// @param minimumChargeValue Destination-side minimum processing charge (local unit).
    /// @param maximumChargeValue Destination-side maximum processing charge (local unit).
    /// @param outboundMaxCharge Source-side per-message max charge commitment (may be a different unit).
    constructor(
        bytes32 connectorId_,
        bytes32 expectedRemoteConnectorId_,
        bytes32 remoteLedgerId_,
        string memory localUnit_,
        address tokenAddress,
        uint256 safetyThresholdValue,
        uint256 minimumChargeValue,
        uint256 maximumChargeValue,
        ClprTypes.ClprAmount memory outboundMaxCharge
    ) payable {
        if (bytes(localUnit_).length == 0) revert InvalidUnit();
        admin = msg.sender;

        connectorId = connectorId_;
        expectedRemoteConnectorId = expectedRemoteConnectorId_;
        remoteLedgerId = remoteLedgerId_;

        token = IERC20(tokenAddress);

        _localUnit = localUnit_;
        _safetyThreshold = safetyThresholdValue;
        _minimumCharge = minimumChargeValue;
        _maximumCharge = maximumChargeValue;

        // outboundMaxCharge.unit may be empty to indicate "use local unit" in simple deployments.
        if (bytes(outboundMaxCharge.unit).length == 0) {
            outboundMaxCharge.unit = localUnit_;
        }
        _outboundMaxCharge = outboundMaxCharge;
    }

    /// @inheritdoc IClprConnector
    function enabled() external pure override returns (bool) {
        // In this prototype the middleware owns enable/disable state. A real connector would expose
        // its on-ledger enabled/disabled status here.
        return true;
    }

    /// @notice Updates the safety threshold (admin-only).
    function setSafetyThreshold(uint256 newThreshold) external onlyAdmin {
        _safetyThreshold = newThreshold;
    }

    /// @notice Updates the destination-side min/max charge policy (admin-only).
    function setChargePolicy(uint256 newMinCharge, uint256 newMaxCharge) external onlyAdmin {
        _minimumCharge = newMinCharge;
        _maximumCharge = newMaxCharge;
    }

    /// @notice Updates the outbound max charge commitment (admin-only).
    function setOutboundMaxCharge(ClprTypes.ClprAmount calldata newOutboundMaxCharge) external onlyAdmin {
        _outboundMaxCharge = newOutboundMaxCharge;
    }

    /// @notice Configures `authorize` to always deny when set to true (admin-only).
    function setDenyAuthorize(bool deny) external onlyAdmin {
        _denyAuthorize = deny;
    }

    /// @notice Registers this connector with the given middleware instance.
    /// @dev In the spec, connector creation is a protocol transaction; this is a prototype stand-in.
    function registerWithMiddleware(address middleware) external onlyAdmin {
        IClprMiddleware(middleware).registerConnector(connectorId, remoteLedgerId, expectedRemoteConnectorId, admin);
    }

    /// @inheritdoc IClprConnector
    function getBalanceReport(
        uint256 outstandingCommitments
    ) external view override returns (ClprTypes.ClprBalanceReport memory report) {
        uint256 available = _availableBalance();
        report = ClprTypes.ClprBalanceReport({
            connectorId: connectorId,
            availableBalance: ClprTypes.ClprAmount({value: available, unit: _localUnit}),
            safetyThreshold: ClprTypes.ClprAmount({value: _safetyThreshold, unit: _localUnit}),
            outstandingCommitments: ClprTypes.ClprAmount({value: outstandingCommitments, unit: _localUnit})
        });
    }

    /// @inheritdoc IClprConnector
    function minimumCharge() external view override returns (ClprTypes.ClprAmount memory amount) {
        amount = ClprTypes.ClprAmount({value: _minimumCharge, unit: _localUnit});
    }

    /// @inheritdoc IClprConnector
    function maximumCharge() external view override returns (ClprTypes.ClprAmount memory amount) {
        amount = ClprTypes.ClprAmount({value: _maximumCharge, unit: _localUnit});
    }

    /// @inheritdoc IClprConnector
    function authorize(
        ClprTypes.ClprMessageDraft calldata draft
    ) external override returns (ClprTypes.ClprConnectorMessage memory connectorMessage) {
        authorizeCount++;

        if (_denyAuthorize) {
            connectorMessage = ClprTypes.ClprConnectorMessage({
                approve: false,
                maxCharge: ClprTypes.ClprAmount({value: 0, unit: _outboundMaxCharge.unit}),
                data: bytes("")
            });
            emit Authorized(
                msg.sender,
                draft.senderApplicationId,
                draft.destinationConnectorId,
                connectorMessage.approve,
                connectorMessage.maxCharge.value,
                keccak256(draft.applicationMessage.data)
            );
            return connectorMessage;
        }

        // Pairing validation: the middleware-supplied destination connector id must match this connector's config.
        if (draft.destinationConnectorId != expectedRemoteConnectorId) {
            connectorMessage = ClprTypes.ClprConnectorMessage({
                approve: false,
                maxCharge: ClprTypes.ClprAmount({value: 0, unit: _outboundMaxCharge.unit}),
                data: bytes("")
            });
        } else {
            connectorMessage = ClprTypes.ClprConnectorMessage({
                approve: true,
                maxCharge: _outboundMaxCharge,
                data: bytes("")
            });
        }

        emit Authorized(
            msg.sender,
            draft.senderApplicationId,
            draft.destinationConnectorId,
            connectorMessage.approve,
            connectorMessage.maxCharge.value,
            keccak256(draft.applicationMessage.data)
        );
    }

    /// @inheritdoc IClprConnector
    function handleMessage(
        ClprTypes.ClprMessage calldata message,
        ClprTypes.ClprMessageResponse calldata response,
        ClprTypes.ClprBilling calldata billing
    ) external override returns (ClprTypes.ClprConnectorResponse memory connectorResponse) {
        handleMessageCount++;
        lastInboundRequestRouteData = message.middlewareMessage.data;
        lastInboundResponseRouteData = response.middlewareResponse.middlewareMessage.data;

        uint256 charge = billing.charge.value;
        if (charge != 0) {
            _reimburse(msg.sender, charge);
        }

        // Connector-to-connector payload is optional; this prototype returns the hash of the reimbursement details.
        connectorResponse = ClprTypes.ClprConnectorResponse({data: abi.encodePacked(uint64(charge))});
    }

    /// @inheritdoc IClprConnector
    function handleConnectorResponse(ClprTypes.ClprConnectorResponse calldata connectorResponse) external override {
        emit ConnectorResponseHandled(msg.sender, keccak256(connectorResponse.data));
    }

    /// @inheritdoc IClprConnector
    function handleApplicationResponse(
        ClprTypes.ClprApplicationResponse calldata response,
        uint64 appMsgId
    ) external override {
        emit ApplicationResponseHandled(msg.sender, appMsgId, keccak256(response.data));
    }

    /// @inheritdoc IClprConnector
    function notifySendRejected(
        ClprTypes.ClprMessageDraft calldata draft,
        uint64 appMsgId,
        ClprTypes.ClprSendFailureReason reason,
        ClprTypes.ClprSendFailureSide side
    ) external override {
        sendRejectedCount++;
        emit SendRejected(msg.sender, appMsgId, draft.destinationConnectorId, reason, side);
    }

    function _availableBalance() private view returns (uint256) {
        if (address(token) == address(0)) {
            return address(this).balance;
        }
        return token.balanceOf(address(this));
    }

    function _reimburse(address recipient, uint256 amount) private {
        if (_availableBalance() < amount) revert InsufficientFunds();
        if (address(token) == address(0)) {
            // Native value reimbursement.
            // solhint-disable-next-line avoid-low-level-calls
            (bool ok,) = payable(recipient).call{value: amount}("");
            require(ok, "native reimbursement failed");
        } else {
            token.safeTransfer(recipient, amount);
        }

        emit Reimbursed(recipient, amount, keccak256(abi.encodePacked(_availableBalance(), _safetyThreshold)));
    }
}
