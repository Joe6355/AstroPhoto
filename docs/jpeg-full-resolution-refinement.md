# JPEG full-resolution registration refinement

Stage 11 fixes the remaining coordinate-precision gap after model-guided analysis registration.
Stage 10 estimates translations on the analysis thumbnail; scaling a small residual to the original
JPEG can leave one or two full-resolution pixels of error. The unchanged clean-stack quality gate
then correctly rejects the blurred result.

## Production order

The JPEG v2 profile pipeline now runs in this order:

1. analysis registration and Stage 10 provisional acceptance;
2. full-resolution decode and file-backed cache of provisional frames only;
3. bounded original-pixel star-patch refinement and per-frame verification;
4. deletion of rejected provisional caches;
5. final weight calculation from refined registrations;
6. integration with the refined canonical transforms;
7. the existing clean-stack quality gate and Stage 4.

The transform contract is unchanged: `source = output/reference + dx/dy`. Refinement is
translation-only, the reference remains exact identity, and refined full-resolution translations
are never scaled again.

## Patch evidence and matching

Candidates come from reliable reference stars after the existing static-artifact filtering.
Coherent moving-sky tracks are preferred. Stationary-camera tracks, low-confidence or elongated
features, saturated/flat patches, foreground-boundary patches, and invalid-coverage patches are
rejected with per-patch diagnostic reasons. Selection is round-robin across 3x3 spatial sectors and
is capped at 24 patches.

Each 11x11 original-resolution patch is plane-detrended, then matched with zero-mean normalized
cross-correlation in a local grid. A separable two-dimensional quadratic fit refines the correlation
maximum. Flat, ambiguous, non-concave, non-finite, and edge-clipped peaks are rejected.

Patch corrections use a weighted median, radial MAD rejection, and five bounded Huber IRLS
iterations. Correlation, peak confidence, stellar confidence, and peak sharpness contribute to the
weight; no single bright patch can dominate.

## Search radius and final policy

The full-resolution search radius is:

```text
clamp(
  2.5
  + 0.35 * analysisScaleUncertainty
  + 0.12 * clamp(stage10Residual, 0, 3)
  + 0.10 * clamp(sequenceResidual, 0, 3)
  + 0.35 * (1 - stage10Confidence),
  2.5,
  4.0
)
```

The evidence targets remain median residual 0.35 px and p90 residual 0.60 px. The frame gate allows
only a small confidence-dependent measurement tolerance (at most +0.10 px median and +0.15 px p90),
requires at least three patches and multiple sectors when available, and checks retention, contrast,
centroid residual, width growth, and smear. The refined transform must beat or safely not regress the
initial transform and must beat identity, inverse, and double-applied alternatives. This gate is
frame-local and does not change the final clean-stack thresholds.

## Sampling diagnostic

Production interpolation remains bilinear. Stage 11 records native identity contrast and a bounded
half-pixel bilinear phase diagnostic. No higher-fidelity kernel is enabled without real-session
evidence showing material interpolation loss after the geometric residual is within target.

## Read-only replay

The optional replay test becomes mandatory whenever the real export is available:

```powershell
$env:ASTROPHOTO_REGISTRATION_REPLAY_ZIP='C:\path\AstroPhoto_Session_20260713_123724_20260717_052350.zip'
.\gradlew.bat testDebugUnitTest --tests com.example.astrophoto.JpegV2Stage11ReplayTest
```

Extraction is confined to `build/tmp/stage11-full-resolution-replay`, the ZIP is never modified, and
only provisional frames are decoded at full resolution. The replay prints per-frame transforms,
corrections, patch counts, residuals, and bounded clean-stack before/after metrics.
