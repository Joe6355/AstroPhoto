# AdaptiveAsinhStretch Stage 17B — test-only ablation

> TEST-ONLY ABLATION — PRODUCTION PROCESSING UNCHANGED

## Baseline

- Repository baseline: `7e2a1fbc4998d3c830a0eb390b0ab00408cbb140`.
- Fixture: `urban-window-30`, `720x960`.
- Accepted original frame indices: `1–21, 25`.
- Rejected original frame indices: `22, 23, 24, 26, 27, 28, 29, 30`.
- Ground-truth SHA-256: `93979af0d4440ee31996ff1d0dde1ac17968b2c830dcbf6099e89e6f98b9996a`.
- Strict denominator: 6 stars and 2 sensor defects; 6 `needs_review` and 8 rejected rows remain excluded.
- CURRENT selected result: `CLEAN_STACK`.
- CURRENT processed rejection reasons: `sky_mad_increased_excessively` and `banding_increased_excessively`.
- Active file-backed output, production-component replay, and configurable test-only `sqrt(alpha)` replay differ by `0` channel levels at `0` pixels.

Baseline ARGB/Float32 hashes:

| Layer | SHA-256 |
|---|---|
| Clean input | `c52a0100c241a01a0d39535eec16d242fc9d14cea18d75887d7755c6ed65c98d` |
| Background-neutralized | `050145351298f61fc3706f16e736154082c80e355ae214e5163c604cdaada02e` |
| CURRENT adaptive stretch | `b2343a82f44a92d8b4224f324556d7db42a0a2724a766cb36e59648897a96612` |
| CURRENT composed | `21c81eb44bb8710bcb59ffab8fb9aa5f60e5f3c40dd483278a8e525bb0bb8adf` |
| CURRENT selected final | `786052b443af8fca5484beafa5482fcfa53430a4cb685b89a2e7a12d1551daef` |
| Effective alpha Float32 LE | `984cb0f5f9ce0e611830c894e8d59580367ca98871e59299d4c9fedd26820f51` |

## Current formula

Production converts 8-bit sRGB channels to linear RGB and calculates:

```text
Y = 0.2126*R + 0.7152*G + 0.0722*B
normalized = clamp((Y-blackPoint)/(whitePoint-blackPoint), 0, 1)
mapped = asinh(asinhStrength*normalized)/asinh(asinhStrength)
appliedBlend = clamp(max(stretchBlend*confidenceScale, targetBlend*confidenceScale), 0, 1)
localBlend = appliedBlend*sqrt(effectiveAlpha)*highlightWeight
targetY = clamp(Y+(mapped-Y)*localBlend, 0, 0.995)
```

After gamut-limited per-channel reconstruction and median safety, `SkyForegroundComposer` applies the same effective alpha in a linear-light composition:

```text
output = processed*effectiveAlpha + reference*(1-effectiveAlpha)
```

For this fixture: `stretchBlend=0.25`, `asinhStrength=5.2`, `highlightProtection=0.95`, `blackPoint=0.001709402`, `whitePoint=0.421709388`, and measured `appliedBlend=0.999882877`. The configured `0.25` blend is therefore dominated by the target-median branch in CURRENT.

Exact production locations:

- [`AdaptiveAsinhStretch.kt`](../app/src/main/java/com/example/astrophoto/processing/jpeg/v2/postprocessing/AdaptiveAsinhStretch.kt#L82): `sqrt(alpha)` local blend followed by luminance gain, gamut clamp, and per-channel reconstruction.
- [`FileBackedAdaptivePresetProcessor.kt`](../app/src/main/java/com/example/astrophoto/processing/jpeg/v2/postprocessing/FileBackedAdaptivePresetProcessor.kt#L582): active file-backed equivalent.
- [`SkyForegroundComposer.kt`](../app/src/main/java/com/example/astrophoto/processing/jpeg/v2/composition/SkyForegroundComposer.kt#L83): subsequent effective-alpha linear-light composition.

## Ablation result

Metrics below are measured on each composed processed candidate with the unchanged quality policy and current effective-alpha analysis region.

| Variant | Sky MAD | Banding | Boundary excess | Halo | Leakage | Quality result |
|---|---:|---:|---:|---:|---:|---|
| V0 CURRENT | 4.362200 | 9.425334 | 0.185133 | 0.639574 | 0.016815 | rejected: sky MAD, banding |
| V1 FULL_STRETCH_SINGLE_COMPOSE | 4.434400 | 9.623474 | 0.849590 | 2.324292 | 0.016815 | rejected: sky MAD, banding |
| V2 LINEAR_ALPHA_THEN_COMPOSE | 4.362200 | 9.272537 | 0.084381 | 0.129705 | 0.016815 | rejected: sky MAD, banding |
| V3 SQRT_ALPHA_NO_SECOND_COMPOSE | 4.362200 | 9.378527 | 4.465441 | 6.167495 | 0.000000 | rejected: sky MAD, banding |
| V4 FULL_STRETCH_HARD_COMPOSE | 4.434400 | 9.700476 | 14.224261 | 17.241450 | 0.000000 | rejected: sky MAD, banding; negative control |
| V5 NO_STRETCH | 1.709200 | 3.404388 | 0.077522 | 0.020270 | 0.017575 | accepted, but strict-star gate fails |

V1 changes only `sqrt(alpha)` operation strength to `1`, keeps the same mask and composer, and makes both banding and boundary metrics worse. This refutes `DOUBLE_ALPHA_CONFIRMED` for the fixture.

V2 reduces banding, boundary excess, and halo relative to CURRENT, but retains both production quality failures. Its `star-01` width ratio is `1.151524`, above the existing `1.05` width-growth gate. The partial improvement is insufficient for `SQRT_ALPHA_SPECIFIC_REGRESSION` or a production candidate.

V5 proves that the failure is stretch-dependent, but it is not a valid replacement: `candidate-x55810-y22220` keeps only `0.856960` of clean local contrast, below the existing `0.95` gate.

## Strict stars

All 6 strict stars remain measurable in every variant. Across the full diagnostic matrix:

- minimum aperture-flux retention: `0.843043`;
- maximum centroid shift: `0.217482 px`;
- width-ratio range: `0.759991–1.153728`;
- maximum absolute ellipticity change: `0.051379`.

These figures describe the diagnostic variants, not a claim that every star in the photograph was retained.

## Root cause and production candidate

Decision: `GENERAL_STRETCH_PARAMETER_ERROR`.

Evidence: full-strength single-compose preserves both CURRENT quality failures and worsens both banding and boundary excess. Removing stretch makes the quality policy accept the processed candidate, while changing only its spatial alpha exponent does not.

Production candidate: `NONE — evidence insufficient`. No production fix is included.

## Determinism

Two independent runs produced a byte-identical 133-file report tree.

- Tree SHA-256: `fbc7bfba9d22faff81b6c4e05c0746ed89d0056ee912fd1627a28aaca3bdf1c0`.
- Manifest SHA-256: `fbc7bfba9d22faff81b6c4e05c0746ed89d0056ee912fd1627a28aaca3bdf1c0`.
- Ablation summary SHA-256: `6af2a3253f238f4eae82bb8957703070b06313b748a45908142338e3c68cd7c3`.
- Strict-star CSV SHA-256: `7cd4a086313ac39af96b370db3c4e6da24458de934b2985e13771ea25b682b53`.
- HTML SHA-256: `cd6028bcc0533c4e22483823ee54a7aec5f6b94051e29d351942ca2fc68063e9`.

The generated PNG/raw/CSV/JSON/HTML report remains under ignored `app/build/reports/adaptive-asinh-ablation/urban-window-30/`.

## Validation

- Focused `AdaptiveAsinhAblationTest`: 4 tests, 0 failures.
- Combined replay/memory regression (`AdaptiveAsinhAblationTest`, `SkyMaskReplayDiagnosticsTest`, and `Stage6CandidateDiagnosticsTest`): 13 tests, 0 failures.
- Related Stage 17, Stage 6, ground-truth, automatic-filter, and manual-filter suites: 150 tests, 0 failures.
- Complete `testDebugUnitTest`: 987 tests, 0 failures, 0 errors, 0 skipped.
- `assembleDebug`: successful.
- `git diff --check`: successful; only Git line-ending conversion notices were emitted.
- `tools/Generate-AdaptiveAsinhAblation.ps1`: successful.
- Production source and fixture ground truth remained byte-identical during both runs.
- No APK was installed and no device data was accessed.

## Next test-only ablation

Isolate the target-median escalation in `appliedBlend`: keep current `sqrt(alpha)`, masks, composition, `asinhStrength`, black/white points, and all later stages, but compare CURRENT `max(configuredBlend, targetBlend)` with `stretchBlend*confidenceScale` only. This changes one independent variable and directly tests why configured `stretchBlend=0.25` becomes measured `appliedBlend=0.999882877`.
