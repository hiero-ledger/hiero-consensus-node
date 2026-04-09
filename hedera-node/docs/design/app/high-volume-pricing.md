# High-Volume Pricing (HIP-1313)

This document describes how high-volume transaction pricing works, and how to correctly add or update values in:

- `simpleFeesSchedules.json`
- `throttles.json`

The rules here apply to HIP-1313 high-volume pricing.

## Scope

This covers:

- Which operations are considered high-volume.
- How per-operation and total high-volume throttles are defined.
- How spreadsheet rates are converted into `utilizationBasisPoints` for `simpleFeesSchedules.json`.
- How runtime multiplier calculation uses these values.

This does not cover legacy congestion multipliers unrelated to HIP-1313 high-volume pricing.

## Runtime Model

For each high-volume operation, runtime computes:

1. Current utilization of that operation's high-volume throttle bucket.
2. Fee multiplier from the operation's `highVolumeRates.pricingCurve`.
3. Final fee = regular fee * high-volume multiplier.

High-volume pricing applies only when:

- `fees.simpleFeesEnabled=true` and `networkAdmin.highVolumeThrottlesEnabled=true`

## Required Data Files

### 1) `throttles.json`

For each high-volume operation, define:

- A dedicated high-volume bucket (for that operation only), `highVolume: true`.
- Membership in `HVTotalThrottles`, also `highVolume: true`.
- Set the burstPeriodMs to 1000 for easier interpolation with the pricing curve to get instantaneous utilization.

Example dedicated bucket:

```json
{
   "burstPeriod": 0,
   "burstPeriodMs": 1000,
   "name": "HVCryptoCreateThrottles",
   "highVolume": true,
   "throttleGroups": [
      {
         "opsPerSec": 0,
         "milliOpsPerSec": 10000000,
         "operations": [
            "CryptoCreate"
         ]
      }
   ]
}
```

### 2) `simpleFeesSchedules.json`

For each high-volume operation schedule entry, define:

- `highVolumeRates.maxMultiplier`
- `highVolumeRates.pricingCurve.piecewiseLinear.points[]`

Each point is:

- `utilizationBasisPoints` (0..10000; integer)
- `multiplier` (scaled by 1000, where 1000 = 1x)

## Conversion Rule (Spreadsheet -> JSON)

Product sheets typically provide rates (TPS), not utilization basis points. Convert each curve x-axis rate into basis points using:

`utilizationBasisPoints = round((effectiveRate / dedicatedHighVolumeRate) * 10000)`

Where:

- `effectiveRate` is the x-value from the product curve for that operation.
- `dedicatedHighVolumeRate` is the operation's dedicated high-volume TPS from `throttles.json` (not `HVTotalThrottles`).

Use integer basis points in JSON.

## Worked Example

### CryptoCreate

- Dedicated HV rate: `10000 TPS` (`milliOpsPerSec=10000000`)
- Spreadsheet first point: `2 TPS`
- Conversion: `2 / 10000 * 10000 = 2 bps`

So first point is:

```json
{ "utilizationBasisPoints": 2, "multiplier": 4000 }
```

### ConsensusCreateTopic

- Dedicated HV rate: `25000 TPS` (`milliOpsPerSec=25000000`)
- Spreadsheet points: `5, 7.5, 12.5, ... TPS`
- Converted basis points: `2, 3, 5, ... bps`

So the first points in JSON must be:

```json
{ "utilizationBasisPoints": 2, "multiplier": 4000 }
{ "utilizationBasisPoints": 3, "multiplier": 8000 }
{ "utilizationBasisPoints": 5, "multiplier": 10000 }
```

Not raw `5` or `7.5` basis points.

### Base Curve (Normalized)

This is the approved normalized curve (from spreadsheet), and it is the source used to derive per-operation curves:

- x-axis: `effectiveRate / baseRate`
- y-axis: `effectivePrice / basePrice`

Points:

- `1 -> 4`
- `1.5 -> 8`
- `2.5 -> 10`
- `3.5 -> 15`
- `5 -> 20`
- `7.5 -> 30`
- `10 -> 40`
- `25 -> 60`
- `50 -> 80`
- `100 -> 100`
- `250 -> 150`
- `500 -> 200`
- `5000 -> 200`

### Derivation Pipeline (Normalized Curve -> JSON Points)

For each operation:

1. `effectiveRate_i = baseRate * rateMultiplier_i`
2. `effectivePrice_i = basePrice * priceMultiplier_i`
3. `utilizationBasisPoints_i = round((effectiveRate_i / dedicatedHighVolumeRate) * 10000)`
4. `multiplier_i = round(priceMultiplier_i * 1000)`

The stored JSON point is:

- `{ "utilizationBasisPoints": utilizationBasisPoints_i, "multiplier": multiplier_i }`

So we do not store normalized x-values (`1, 1.5, 2.5, ...`) directly in `simpleFeesSchedules.json`; we store the converted utilization basis points.

### Derived Common BPS Shape

Operations with matching base-rate-to-HV-rate proportion can end up with the same bps sequence after conversion. A common derived sequence is:

- `2, 3, 5, 7, 10, 15, 20, 50, 100, 200, 500, 1000, 10000`

with multipliers:

- `4000, 8000, 10000, 15000, 20000, 30000, 40000, 60000, 80000, 100000, 150000, 200000, 200000`

### Per-Operation Values (from XLSX -> JSON)

The entries below are the spreadsheet-derived values used to populate `highVolumeRates`.

- `CryptoCreate`
  - Base rate: `2 TPS`, base price: `$0.05`, dedicated HV rate: `10000 TPS`
  - Curve (`bps -> multiplier`): `2->4000, 3->8000, 5->10000, 7->15000, 10->20000, 15->30000, 20->40000, 50->60000, 100->80000, 200->100000, 500->150000, 1000->200000, 10000->200000`
- `ConsensusCreateTopic`
  - Base rate: `5 TPS`, base price: `$0.01`, dedicated HV rate: `25000 TPS`
  - Curve (`bps -> multiplier`): `2->4000, 3->8000, 5->10000, 7->15000, 10->20000, 15->30000, 20->40000, 50->60000, 100->80000, 200->100000, 500->150000, 1000->200000, 10000->200000`
- `ScheduleCreate`
  - Base rate: `100 TPS`, base price: `$0.02`, dedicated HV rate: `10000 TPS`
  - Curve (`bps -> multiplier`): `100->4000, 150->8000, 250->10000, 350->15000, 500->20000, 750->30000, 1000->40000, 2500->60000, 5000->80000, 10000->100000`
- `CryptoApproveAllowance`
  - Base rate: `10000 TPS`, base price: `$0.03`, dedicated HV rate: `10000 TPS`
  - Curve (`bps -> multiplier`): `10000->4000`
- `FileCreate`
  - Base rate: `2 TPS`, base price: `$0.05`, dedicated HV rate: `10000 TPS`
  - Curve (`bps -> multiplier`): `2->4000, 3->8000, 5->10000, 7->15000, 10->20000, 15->30000, 20->40000, 50->60000, 100->80000, 200->100000, 500->150000, 1000->200000, 10000->200000`
- `FileAppend`
  - Base rate: `10 TPS`, base price: `$0.05`, dedicated HV rate: `50000 TPS`
  - Curve (`bps -> multiplier`): `2->4000, 3->8000, 5->10000, 7->15000, 10->20000, 15->30000, 20->40000, 50->60000, 100->80000, 200->100000, 500->150000, 1000->200000, 10000->200000`
- `ContractCreate`
  - Base rate: `350 TPS`, base price: `$1.0`, dedicated HV rate: `17500 TPS`
  - Curve (`bps -> multiplier`): `200->4000, 300->8000, 500->10000, 700->15000, 1000->20000, 1500->30000, 2000->40000, 5000->60000, 10000->80000`
- `HookStore`
  - Base rate: `10 TPS`, base price: `$0.005`, dedicated HV rate: `50000 TPS`
  - Curve (`bps -> multiplier`): `2->4000, 3->8000, 5->10000, 7->15000, 10->20000, 15->30000, 20->40000, 50->60000, 100->80000, 200->100000, 500->150000, 1000->200000, 10000->200000`
- `TokenAssociateToAccount`
  - Base rate: `100 TPS`, base price: `$0.05`, dedicated HV rate: `10000 TPS`
  - Curve (`bps -> multiplier`): `100->4000, 150->8000, 250->10000, 350->15000, 500->20000, 750->30000, 1000->40000, 2500->60000, 5000->80000, 10000->100000`
- `TokenAirdrop`
  - Base rate: `100 TPS`, base price: `$0.1`, dedicated HV rate: `10000 TPS`
  - Curve (`bps -> multiplier`): `100->4000, 150->8000, 250->10000, 350->15000, 500->20000, 750->30000, 1000->40000, 2500->60000, 5000->80000, 10000->100000`
- `TokenClaimAirdrop`
  - Base rate: `3000 TPS`, base price: `$0.001`, dedicated HV rate: `10500 TPS`
  - Curve (`bps -> multiplier`): `2857->4000, 4286->8000, 7143->10000, 10000->15000`
- `TokenMint`
  - Base rate: `50 TPS`, base price: `$0.02`, dedicated HV rate: `12500 TPS`
  - Curve (`bps -> multiplier`): `40->4000, 60->8000, 100->10000, 140->15000, 200->20000, 300->30000, 400->40000, 1000->60000, 2000->80000, 4000->100000, 10000->150000`
- `TokenCreate`
  - Base rate: `100 TPS`, base price: `$0.1`, dedicated HV rate: `10000 TPS`
  - Curve (`bps -> multiplier`): `100->4000, 150->8000, 250->10000, 350->15000, 500->20000, 750->30000, 1000->40000, 2500->60000, 5000->80000, 10000->100000`

### Additional Workbook Variants

The workbook also includes variant blocks in `Buckets` (for example `Consensus Create Topic w/ Fees`, `Token Create w/ fees`, `Token Mint: Fungible`, `Token Mint: Non-fungible`). These are product reference variants and must only be mapped into JSON when the corresponding operation fee model requires separate high-volume schedules.

## Invariants and Validation Checklist

When updating high-volume data, verify all of the following:

1. `utilizationBasisPoints` are integers.
2. Curve points are sorted by non-decreasing `utilizationBasisPoints`.
3. Final point reaches `10000` basis points unless intentionally capped earlier.
4. Multipliers are in scaled units (`1000 = 1x`).
5. `maxMultiplier` is >= highest curve multiplier.
6. Every high-volume operation exists in both:
   - a dedicated `HV*` bucket
   - `HVTotalThrottles`
7. `HVTotalThrottles` contains exactly the high-volume operation set intended by product.
8. End-to-end tests validate observed fees vs expected multipliers for representative operations.

## Common Mistakes

- Using raw TPS values directly as `utilizationBasisPoints`.
- Adding decimal basis points (for example `7.5`) in JSON.
- Converting with total-bucket TPS instead of dedicated per-operation HV TPS.
- Unsorted curve points (can produce incorrect interpolation behavior).

## Recommended Update Workflow

1. Update dedicated HV bucket rates in `throttles.json` if needed.
2. Recompute each curve x-axis value to basis points using the conversion rule.
3. Update `simpleFeesSchedules.json` curve points and `maxMultiplier`.
4. Run JSON validation and targeted tests:
   - HIP-1313 fee-multiplier tests
   - high-volume mixed-burst tests
5. Confirm no operation-level or total-bucket membership drift in `throttles.json`.
