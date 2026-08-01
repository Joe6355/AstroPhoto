# AdaptiveAsinhStretch Stage 17C - target-median ablation

> TEST-ONLY ABLATION - PRODUCTION PROCESSING UNCHANGED

## Formula and cause

The production and file-backed implementations both calculate:

```text
confidenceScale = clamp(0.18 + 0.82*confidence, 0, 1)
medianNormalized = clamp((median-black)/(white-black), 0, 1)
fullyMappedMedian = asinh(strength*medianNormalized)/asinh(strength)
targetBlend = clamp((targetLinearMedian-median)/(fullyMappedMedian-median), 0, 1)
appliedBlend = clamp(max(stretchBlend*confidenceScale, targetBlend*confidenceScale), 0, 1)
```

For `urban-window-30`:

```text
stretchBlend             = 0.25
confidenceScale          = 0.999882877
statisticsMedian         = 0.002930403
targetLinearMedian       = 0.009134057
fullyMappedMedian        = 0.006430055
rawTargetBlend           = 1.7726487
targetBlend after clamp  = 1.0
configured contribution  = 0.249970719
target contribution      = 0.999882877
CURRENT appliedBlend     = 0.999882877
```

The target request exceeds the fully mapped median, so `rawTargetBlend` is clamped to `1`.
Its confidence-scaled contribution dominates the configured contribution and turns `0.25`
into `0.999882877`.

## Result

All measurements use the same input, masks, `sqrt(effectiveAlpha)`, composition, later stages,
quality policy, six strict stars, and two strict sensor defects.

| Variant | Applied blend | Sky MAD | Banding | Boundary | Mean halo | Mean leakage | Quality | Star gate |
|---|---:|---:|---:|---:|---:|---:|---|---|
| CLEAN_STACK | - | 2.072200 | 4.132032 | 0.077522 | - | - | baseline | - |
| CURRENT | 0.999883 | 4.362200 | 9.425334 | 0.185133 | 0.639574 | 0.016815 | rejected: sky MAD, banding | fail |
| HONEST_BLEND | 0.250000 | 2.931800 | 5.400928 | 0.090086 | 0.109800 | 0.010788 | accepted | pass |
| CAPPED 0.25 | 0.250000 | 2.931800 | 5.400928 | 0.090086 | 0.109800 | 0.010788 | accepted | pass |
| CAPPED 0.35 | 0.350000 | 3.000000 | 6.037796 | 0.106997 | 0.152064 | 0.008972 | rejected: sky MAD, banding | fail |
| CAPPED 0.50 | 0.500000 | 3.787400 | 7.054538 | 0.113269 | 0.307596 | 0.008152 | rejected: sky MAD | fail |
| CAPPED 0.75 | 0.750000 | 4.361000 | 8.250706 | 0.162562 | 0.478755 | 0.012730 | rejected: sky MAD, banding | fail |
| TARGET_MEDIAN_DISABLED | 0.249971 | 2.931800 | 5.400160 | 0.090086 | 0.108747 | 0.010788 | accepted | pass |

`TARGET_MEDIAN_DISABLED` reduces banding by `42.706%` and Sky MAD by `32.791%` versus
CURRENT. It also improves the sensor-defect residual from `4.408163` to `2.010204` and does
not worsen CURRENT boundary, halo, leakage, or foreground metrics.

Its worst strict-star metrics are:

- minimum aperture-flux retention: `1.297241518`;
- minimum peak retention: `1.205202770`;
- maximum centroid shift: `0.112406962 px`;
- width ratio: `0.777873543-1.018182599`;
- maximum ellipticity change: `0.020181837`;
- minimum local-contrast retention: `1.228667501`;
- established retention gate: `6/6` passed.

## Decision

Root cause: `TARGET_MEDIAN_ESCALATION_CONFIRMED`.

Production candidate: `NONE`.

Although `HONEST_BLEND`, `CAPPED 0.25`, and `TARGET_MEDIAN_DISABLED` pass the current
quality policy, all three remain worse than CLEAN_STACK: Sky MAD is `41.482%` higher and
banding is about `30.7%` higher. The full-resolution comparison also retains visible diagonal
banding in the processed candidate. Caps `0.35` and above remain rejected. No production fix
is included.

## Verification

- CURRENT production, file-backed, and configurable replay: `0` maximum channel difference,
  `0` differing pixels.
- Deterministic report: two byte-identical runs, `154` files,
  tree SHA-256 `8750c14c7749255c809a5ef51d09453e082c85d3ecb07eac9e63dc91238d58cc`.
- Focused ablation: `4/4`.
- Replay/OOM regression: `13/13`.
- Related suites: `150/150`.
- Full unit suite: `987/987`, `0` failures, `0` errors, `0` skipped.
- `assembleDebug`: successful.
- No APK was installed and no device data was accessed.
