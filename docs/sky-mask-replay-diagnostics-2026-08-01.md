# Sky-mask replay diagnostics — 2026-08-01

## Scope

- Fixture: `urban-window-30`, 30 frames, 720×960.
- Mode: replay-only; no production processing or thresholds were changed.
- Ground truth: `astrophoto.ground-truth/2`, SHA-256 `93979af0d4440ee31996ff1d0dde1ac17968b2c830dcbf6099e89e6f98b9996a`.
- Strict denominator: 6 confirmed stars. `uncertain` labels are excluded.
- Full generated report: `app/build/reports/sky-mask-diagnostics/urban-window-30/index.html` (ignored by Git).

## Verified pipeline

The replay follows the current production ordering and coordinate contract:

1. `SkyMaskEstimator.estimate` — initial Boolean sky mask in reference/output coordinates.
2. sequence registration and automatic sensor mask — transforms are looked up by original capture index;
3. `LinearWeightedIntegrator.integrate` — bilinear aligned integration of accepted frames only;
4. `SkyMaskRefiner.refine` — refined Boolean mask;
5. `ForegroundProtectionMask.detect` — thin-structure protection;
6. `MaskFeathering.feather` — Float32 alpha;
7. `ReferenceStarSignalPreserver.preserve`;
8. `SkyForegroundComposer.compose` — effective alpha and clean composition;
9. `FileBackedAdaptivePresetProcessor.process` — active Stage 4 path;
10. `ResultSelectionPolicy.select` — processed → clean → reference quality fallback.

Geometry is unchanged 720×960 reference/output space: origin top-left, no common-region crop, no output resize. Integration is the only spatial interpolation (bilinear source sampling). Composition is per-pixel linear-light blending.

Accepted original frame indices: `1–21, 25`.

Rejected original frame indices: `22, 23, 24, 26, 27, 28, 29, 30`.

The exposed pre-composition component replay and active `FileBackedAdaptivePresetProcessor` output are pixel-identical on this fixture: maximum channel difference `0`, differing pixels `0`.

## Baseline hashes

| Artifact | Canonical SHA-256 |
|---|---|
| reference ARGB | `b71131d1199acbb29466eded02c16497cb3e62b23f59c42082f4923a0be6ff27` |
| clean stack ARGB | `c52a0100c241a01a0d39535eec16d242fc9d14cea18d75887d7755c6ed65c98d` |
| processed sky ARGB | `43199842fbd7eac83f2d9b77811cc0015ad3cab24204ddd0519ef0c278f2e5fd` |
| composed processed candidate ARGB | `21c81eb44bb8710bcb59ffab8fb9aa5f60e5f3c40dd483278a8e525bb0bb8adf` |
| selected final ARGB | `786052b443af8fca5484beafa5482fcfa53430a4cb685b89a2e7a12d1551daef` |
| initial mask | `f984cc36ad922d68518f5162fcba60d2b5b4044489204c4ec3c000e7db607211` |
| refined mask | `a8aef6a5d1db9a370c819cc28a69bc096c9979421ecebc1c1283eb9660513ca6` |
| effective alpha Float32 LE | `984cb0f5f9ce0e611830c894e8d59580367ca98871e59299d4c9fedd26820f51` |

## Mask facts

- Initial confidence: `0.9948354`; fallback not used.
- Refined confidence: `0.8210418`; fallback not used.
- Production feather radius: `25 px`; foreground protection radius: `1 px`.
- Initial/refined boundary pixel proxies: `6588 / 7460`.
- Alpha transition area `[0.01, 0.99]`: `77272 px`.
- Alpha counts: zero `364378`, transition `77272`, one `249550`; no values in `(0,0.01)` or `(0.99,1)`.
- Mean/max horizontal-or-vertical transition-run proxy: `44.8864 / 425 px`.
- No manual foreground mask exists. Foreground inclusion/exclusion and leakage values in the report are explicitly labelled proxies, not ground truth.

## Ablation evidence

| Variant | Boundary halo proxy | Leakage proxy | Sky MAD | Foreground change |
|---|---:|---:|---:|---:|
| current | 0.659983 | 0.000000 | 4.3622 | 0.000000 |
| no mask | 2.292989 | 6.823167 | 3.7152 | 6.823167 |
| hard mask | 12.934470 | 0.000000 | 4.0000 | 0.000000 |
| no refine | 2.792573 | 2.852440 | 4.6470 | 2.994808 |
| no protection | 0.659983 | 0.000000 | 4.3622 | 0.000000 |
| no postprocess | 0.362364 | 0.000000 | 2.0722 | 0.000000 |

`hard-mask` and `no-refine` are worse than current. `no-protection` is pixel/metric-equivalent to current on this fixture. Therefore the evidence does not support changing refinement, feathering, or foreground protection first.

## Proven first bad stage

| Stage | Sky MAD | Banding proxy | Boundary edge excess |
|---|---:|---:|---:|
| clean input | 2.0722 | 4.089350 | 0.737781 |
| gradient removal | 1.7112 | 3.507236 | 0.737673 |
| background neutralization | 1.6470 | 3.335275 | 0.737673 |
| **adaptive stretch** | **4.7914** | **11.096793** | **3.743458** |
| chroma reduction | 4.7914 | 11.096793 | 3.743458 |
| star enhancement | 4.7914 | 11.095890 | 3.743458 |
| final safety | 4.7914 | 11.095890 | 3.743458 |
| background match | 4.4332 | 9.694920 | 2.708677 |

Classification: `POSTPROCESS_ERROR`. The first bad stage is `AdaptiveAsinhStretch`; the last clean stage is background neutralization. Later stages preserve or only partially reduce the artifact.

The processed candidate is rejected for `sky_mad_increased_excessively` and `banding_increased_excessively`. The selected final candidate is `CLEAN_STACK`, so the bad processed candidate is not the current final output.

## Strict stars

All 6 strict stars were measured at every required stage. In the selected final result, five have flux retention `1.0` and centroid shift `0`; `star-01`, 15.25 px from the refined boundary with center alpha `0.68`, has flux retention `0.986761`, centroid shift `0.00457 px`, and width ratio `0.984403` relative to the clean stack.

This is not a claim that every star in the photograph was retained.

## Determinism

Two independent runs produced a byte-identical 387-file tree.

- Tree SHA-256: `ff1a1eddb5624647b3d9d76b054c9c4671cf56cbc7f9a64181aebfc4f2cded69`.
- Pipeline manifest: `e182c8bb282a26207e3f6da85d99122a40fc01e8cacc9ab4cf3a763a02934973`.
- Strict-star CSV: `8f39dbbccda9291e1b7e6e0b69c63750da3d6fd0cdfd5dbf0ce49ca51c8cc5ed`.
- Boundary CSV: `13ecb4a389404b020771541d839c6794c1c6157c0e0f3cb340d90f2d7ad215d9`.
- HTML: `4301f80816d06c2424c2783701b65a0cd6cd5de84d472e11aaca71ebc93cd4b4`.

## Validation

- Focused replay, Stage 6, ground-truth, automatic-filter, and manual-alignment suites: `150` tests, `0` failures, `0` errors, `0` skipped.
- Complete `testDebugUnitTest`: `983` tests, `0` failures, `0` errors, `0` skipped.
- `tools/Generate-SkyMaskDiagnostics.ps1`: successful; regenerated the ignored canonical report.
- `assembleDebug`: successful.
- No APK was installed and no production source was changed by this replay-only stage.

## Next step

The responsible production class is proven, but the exact replacement formula is not. The next single step should be a test-only ablation inside `AdaptiveAsinhStretch` that separates its fractional `sqrt(alpha)` operation strength from the later composition alpha. Only if that removes the boundary/banding regression while preserving strict-star metrics should a minimal single-alpha production change be proposed.
