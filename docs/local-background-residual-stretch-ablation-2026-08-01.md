# Local-background residual stretch Stage 17D

> TEST-ONLY ABLATION - PRODUCTION PROCESSING UNCHANGED

## Algorithm and provenance

For linear luminance `Y`:

```text
background = mean(full-alpha square annulus around Y)
residual = max(Y - background, 0)
noiseThreshold = max(0.0015, 2.2 * skyLuminanceMAD)
support = smoothstep(noiseThreshold, 2 * noiseThreshold, residual)
brightProtection = 1 - smoothstep(brightStart, brightEnd, Y)
enhancedResidual = residual * (1 + strength * brightProtection)
outputY = clamp(
    Y + sqrt(effectiveAlpha) * support * (enhancedResidual - residual),
    0,
    0.995
)
RGBout = RGBlinear * outputY / Y
```

The RGB scale preserves linear chromaticity before the existing 8-bit `packLinear` conversion.
No star mask or global tone curve is used.

The fixed parameters come from existing processing rules rather than per-star tuning:

- median clean strict-star PSF: `2.849189281 px`;
- existing `LocalStarContrastEnhancer` annulus rule:
  `inner=ceil(max(2, 1.4*PSF))=4 px`, `outer=min(inner+3,9)=7 px`;
- all `176` square-annulus samples must have alpha at least `0.98`, so the operation is
  disabled at image/mask edges and invalid coverage;
- existing detail threshold: `max(0.0015, 2.2*Sky MAD)=0.001500000` linear;
- smooth support upper threshold: `0.003000000` linear;
- existing bright protection limits: `0.011053235-0.419999987` linear;
- existing `URBAN_SKY_STRONG` maximum requested detail gain:
  `(1.45-1)*0.80=0.360000044`;
- LOW/MEDIUM/STRONG use one-third, two-thirds, and all of that gain:
  `0.12 / 0.24 / 0.36`.

All residual variants reuse the same neutralized input and the same local-background field,
SHA-256 `9c85c31a3411fe35eaa0087a68f838c8a16aef4d836c0f262d9c040d82fc32e6`.
Only `strength` changes.

## Result

All variants use the same `urban-window-30` input, masks, `sqrt(effectiveAlpha)`, composition,
defect filtering, registration, stacking, foreground preservation, downstream stages, six strict
stars, two strict sensor defects, and production quality policy.

| Variant | Strength | Sky MAD | Banding | Boundary | Halo | Leakage | Defect residual | Weak-star gain | Max width | New / unmatched detections | Quality | Star gate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| CLEAN_STACK | - | 2.072200 | 4.132032 | 0.077522 | 0.000000 | 0.017694 | 0.000000 | 1.000000 | 1.000000 | 0 / 0 | baseline | 6/6 |
| CURRENT | 0.999883 applied blend | 4.362200 | 9.425334 | 0.185133 | 0.639574 | 0.016815 | 4.408163 | 3.650532 | 1.153728 | 0 / 0 | rejected | fail |
| TARGET_MEDIAN_DISABLED | 0.249971 applied blend | 2.931800 | 5.400160 | 0.090086 | 0.108747 | 0.010788 | 2.010204 | 1.910750 | 1.018183 | 3 / 3 | accepted | 6/6 |
| RESIDUAL_SOFT_LOW | 0.12 | 1.709200 | 3.404188 | 0.077522 | 0.020270 | 0.017575 | 1.530612 | 1.016850 | 1.010100 | 2 / 2 | accepted | 5/6 |
| RESIDUAL_SOFT_MEDIUM | 0.24 | 1.709200 | 3.404010 | 0.077522 | 0.020270 | 0.017575 | 1.530612 | 1.016850 | 1.010100 | 2 / 2 | accepted | 5/6 |
| RESIDUAL_SOFT_STRONG | 0.36 | 1.709200 | 3.403833 | 0.077522 | 0.020270 | 0.017575 | 1.530612 | 1.016850 | 1.010100 | 2 / 2 | accepted | 5/6 |

Against CLEAN_STACK, the residual variants reduce Sky MAD by `17.518%` and banding by
`17.615-17.623%`. Against CURRENT, the reductions are `60.818%` and `63.883-63.886%`;
against TARGET_MEDIAN_DISABLED they are `41.701%` and `36.961-36.968%`.

The operation found `228742` pixels with a complete local-background annulus, but only `19`
pixels exceeded the support threshold. LOW changed `13` pixels; MEDIUM and STRONG changed
`16`. All three changed exactly `0` negative-residual pixels and `0` below-noise background
pixels. The similar final metrics therefore describe a near-no-op replacement of global
AdaptiveAsinh within the unchanged surrounding replay, not a strong new weak-signal recovery.

## Strict stars, defects, detections, and chroma

Worst strict-star values:

| Variant | Passed | Min flux | Min peak | Max centroid | Width range | Max ellipticity change | Min contrast retention |
|---|---:|---:|---:|---:|---:|---:|---:|
| LOW | 5/6 | 0.843043 | 0.960467 | 0.132093 px | 0.747862-1.010100 | 0.017054 | 0.856960 |
| MEDIUM | 5/6 | 0.843043 | 0.960467 | 0.154074 px | 0.727642-1.010100 | 0.020539 | 0.856960 |
| STRONG | 5/6 | 0.843043 | 0.960467 | 0.156697 px | 0.715934-1.010100 | 0.025508 | 0.856960 |

Bright cores do not exceed the established `1.05` width limit, but
`candidate-x55810-y22220` retains only `0.856960` local contrast, so every residual variant
fails the unchanged strict-star gate.

Known sensor-defect residuals are identical for all three strengths:

| Defect | Mean residual | Maximum residual |
|---|---:|---:|
| defect-01 | 1.836735 | 2.000000 |
| defect-02 | 1.224490 | 2.000000 |

They improve on CURRENT and TARGET_MEDIAN_DISABLED but remain worse than the zero CLEAN_STACK
baseline.

Each residual variant produces two detector candidates absent from CLEAN_STACK. Both are
unmatched to every fixture ground-truth label and aligned-stack star and are therefore counted
as false weak-star detections for candidate acceptance:

```text
x=661.016, y=215.680, width=3.537
x=242.553, y=345.532, width=3.646
```

Luminance scaling preserves chromaticity analytically, but 8-bit packing introduces measured
mean/max linear-chromaticity shifts of `0.000781/0.002126` (LOW),
`0.001408/0.004681` (MEDIUM), and `0.002882/0.009443` (STRONG). Final global chroma residual
is about `1.916`, below CLEAN_STACK `3.806`, but the operator-level quantization shift is not zero.

## Visual finding and decision

Full-resolution inspection shows strong diagonal banding in CURRENT and weaker retained banding
in TARGET_MEDIAN_DISABLED. The three residual outputs are visually close to CLEAN_STACK and do
not visibly amplify its diagonal bands at 1:1. Their `x8` difference images still expose broad
diagonal structured changes from the unchanged surrounding replay stages relative to CLEAN_STACK.

The residual approach passes the production quality policy and improves Sky MAD and banding,
but no strength satisfies all Stage 17D gates: each fails one of six strict stars, retains
non-zero residual at both known defects, and creates two unmatched detector candidates.

```text
LOCAL_RESIDUAL_STRETCH_REJECTED
Production candidate: NONE
```

No production code was changed.

## Verification

- CURRENT replay: `0` maximum channel difference, `0` differing pixels.
- Deterministic report: two byte-identical runs, `49` files, tree SHA-256
  `c3ec36ff55d1308b5bbdba28f67ddf987107a0e00fcfa823dc88ef05e676265e`.
- Focused Stage 17D tests: `4/4`.
- Sensor-defect/OOM regression: `13/13`.
- Related replay/post-processing/detector suites: `70/70`.
- Full unit suite: `991/991`, `0` failures, `0` errors, `0` skipped.
- `assembleDebug`: successful.
- No APK was installed and no device data was accessed.
