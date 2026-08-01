# Experimental Stars production preset — 2026-08-01

## Scope

`Experimental Stars` is an append-only production profile for JPEG stacking. Existing profile enum positions, their parameter objects, and the original `LocalStarContrastEnhancer.apply` path are unchanged. The new profile requires at least six frames and uses the existing stable urban stack recipe through Stage 3; only its Stage 4 star enhancement and profile-specific result handling are new.

## Stage 4 algorithm

The profile applies a deterministic, positive-only local residual to compact stellar candidates:

- noise floor: `max(1 / 8191, 0.75 * sky MAD)`;
- candidate discovery: sky-alpha region only, local maximum plus annulus median, width limit `2.85 px`, at least three supporting samples and an opposite-pair requirement;
- discovered-candidate ellipticity limit: `0.68`;
- catalog-star acceptance: confidence at least `0.32`, width `0.65..4.4 px`, ellipticity at most `0.62`;
- output: PSF- and radial-weighted positive residual, scaled by sky alpha and bright-pixel protection, with chroma-preserving RGB gain;
- strength changes only the bounded residual and the perceptual weighting inside the same PSF radius (`weight^(1 / strength^2)`); zero support remains zero;
- maximum discovered candidates: `512`;
- sensor-defect pixels, protected foreground, broad structures, negative residuals, and saturated/bright cores are excluded or attenuated.

The profile-specific mapper disables the existing global gradient, neutralization, stretch, asinh, and chroma operations. The selected MEDIUM configuration uses profile strength `1.0`, residual strength `1.50`, maximum detail gain `2.50`, minimum contrast `0.60`, sky median factor `1.05`, clipping threshold `2.0`, width growth `0`, and target median `0`.

## Failure handling

Failure isolation is enabled only for `Experimental Stars`:

1. A Stage 4 exception preserves and recomposes the CLEAN candidate. Cancellation remains cancellation.
2. A no-op experimental result is rejected by the quality gate as `experimental_processing_returned_clean_fallback`, selecting CLEAN explicitly.
3. If Experimental PNG writing or reopened-PNG hash verification fails, the unverified artifact is removed and the CLEAN candidate is published as `RecoveredStars_<timestamp>.png`.
4. Existing profiles still propagate the same processing/publication failures as before.

## Deterministic urban-window-30 replay

Report directory: `app/build/reports/experimental-stars-production/urban-window-30` (generated build output, not committed).

| Variant | Changed pixels | Sky MAD | Banding proxy | Weak-star contrast gain | Strict aperture-flux ratio | Strict peak ratio | Max centroid shift | Max width ratio | Max ellipticity change | Protected changes | New detections |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| CLEAN | 0 | 2.072200000 | 4.132032201 | 1.000000000 | 1.000000000 | 1.000000000 | 0.000000000 | 1.000000000 | 0.000000000 | 0 | 0 |
| Current strong, rejected | 310588 | 4.362200000 | 9.425333723 | 3.650532114 | 3.135823119 | 1.882749197 | 0.174156917 | 1.172007336 | 0.051378974 | 0 | 0 |
| Current safe | 0 | 2.072200000 | 4.132032201 | 1.000000000 | 1.000000000 | 1.000000000 | 0.000000000 | 1.000000000 | 0.000000000 | 0 | 0 |
| Recovered Stars | 0 | 2.072200000 | 4.132032201 | 1.000000000 | 1.000000000 | 1.000000000 | 0.000000000 | 1.000000000 | 0.000000000 | 0 | 0 |
| Experimental Stars (MEDIUM) | 112 | 2.072200000 | 4.128907919 | 2.183765870 | 1.739771739 | 1.316883635 | 0.094534044 | 1.000000000 | 0.033321362 | 0 | 0 |

Experimental foreground mean/maximum change and sensor-defect mean/maximum residual are all `0`. Mean halo score is `0`; mean leakage score is unchanged from CLEAN at `0.009829900`. The two deterministic MEDIUM runs produced the same ARGB SHA-256: `70144606bb93c99d7969a4c41d9101a295efdab0b96086527a7267ec0113c347`. CLEAN, Current safe, and Recovered Stars are byte-identical with ARGB SHA-256 `786052b443af8fca5484beafa5482fcfa53430a4cb685b89a2e7a12d1551daef`.

Exactly three fixed strength variants were evaluated:

| Variant | Residual strength | Detail cap | Changed PSF-support pixels | New detections | Hard gate | Result |
|---|---:|---:|---:|---:|---|---|
| CURRENT | 1.00 | 1.75 | 83 | 0 | pass | safe, weaker |
| MEDIUM | 1.50 | 2.50 | 112 | 0 | pass | selected |
| STRONG | 2.25 | 3.25 | 564 | 4 | reject | rejected |

All three used the same `204` considered candidates, detection thresholds, shape acceptance, defect mask, foreground mask, and PSF radii. The selected support grew from `83` to `112` pixels because additional low-weight pixels inside accepted PSF neighborhoods crossed the lossless output quantization boundary; it did not come from a wider discovery region or a changed detection threshold.

Review artifacts include a full CLEAN/RECOVERED/EXPERIMENTAL comparison, a CLEAN/CURRENT/MEDIUM/STRONG full-scale comparison, both difference images at `8x`, six strict-star crops, two sensor-defect crops, both metrics CSV files, PSF-support evidence, review guide, algorithm description, and SHA-256 manifest.

## Verification

- Focused production/fallback/save/UI tests: `63/63` passed across 8 suites.
- Related Stage 4/5/7 tests: `157/157` passed.
- Full `testDebugUnitTest`: `996/996` passed across 79 XML suites; failures, errors, and skips are all zero.
- `assembleDebug`: passed.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 15,710,596 bytes, SHA-256 `9739B27AF0639897DAA6DD5CA02CC191BC6BB99FF1F545AED9401B9B972EB2DE`.
- The APK was installed on Xiaomi `23021RAA2Y` (`topaz`). The updated review package is on the device under `Pictures/AstroPhotoExperimentalMedium`.
- The full comparison was reviewed in MIUI Gallery at landscape fit-to-screen scale. The clean device screenshot is `app/build/reports/experimental-stars-production/device-updated-medium-landscape-clean.png`.

## Known limitation

The measured change remains local: 112 pixels in this fixture. The replay proves deterministic metric improvements and protection invariants for `urban-window-30`; it does not replace human acceptance on additional real scenes. Stable runtime and peak-memory A/B deltas are not available from this replay harness.
