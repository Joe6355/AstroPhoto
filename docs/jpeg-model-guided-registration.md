# JPEG model-guided frame registration

## Production flow

The Stage 9 global stellar model remains the sequence anchor. Stage 10 changes only the
per-frame path:

```text
sparse hypotheses -> global model -> predicted reference-to-source transform
                   -> bounded local matching -> robust translation correction
                   -> single-frame verification -> explicit acceptance policy
                   -> final sequence validation -> file-backed integration
```

A missing sparse hypothesis no longer causes immediate rejection. If JPEG analysis contains at
least two detections, the frame receives exactly one wider model-guided retry. Invalid analysis is
not retried. A rejected frame keeps a non-reference predicted transform for diagnostics and is
never cached or integrated.

## Search radius and refinement

All distances in this stage are analysis pixels:

```text
baseRadius = 1.25 + 1.5 * min(modelResidual, 2.0) + 1.5 * (1 - modelConfidence)
normalRadius = clamp(baseRadius, 1.25, 4.5)
retryRadius = clamp(baseRadius * 1.6, 1.25, 6.0)
```

Candidate detections are stored in a bounded spatial grid. Each reference star searches only
around `reference + predictedDx/Dy`. Candidate assignments are one-to-one and deterministically
ordered by distance, photometric/shape compatibility, motion class, and coordinates. When a
coherent moving set is available, stationary-camera tracks cannot displace it.

The correction is initialized with the median of `candidate-reference-predicted`, refined with
three Huber-weighted iterations, and finalized from MAD-filtered inliers. Corrections beyond 80%
of the local radius or 3 pixels are rejected. A non-zero prediction cannot jump to the zero mode.

## Frame acceptance thresholds

Legacy `StarSimilarityRegistrar` thresholds remain unchanged. A strong sparse result uses that
existing path. The separate sequence-supported path requires all of:

| Evidence | Threshold |
|---|---:|
| Observable global model | required |
| Sequence model score | >= 0.50 |
| Local matches / inliers | >= 2 / >= 2 |
| Local residual | <= 1.25 px |
| Local confidence | >= 0.42 |
| Sequence agreement | >= 0.55 |
| Verification stars | >= 4 |
| Frame retention | >= 0.80 |
| Frame contrast ratio | >= 0.78 |
| Frame width growth | <= 0.65 |
| Frame smear | <= 0.10 |
| Verification confidence | >= 0.70 |

Thus fixtures matching real frames 012 (`retention=1`, `contrast=0.837`, `smear=0`) and 013
(`retention=1`, `contrast=1.064`, `smear=0`) can pass when their model/local evidence is strong.
The full-resolution clean-stack gate remains unchanged at retention `0.90`, contrast `0.85`, and
smear `0.10`.

## Verification aggregation

Aggregate frame metrics include only defined non-reference samples. Reference identity is not a
rejected sample, and missing verification is not converted to smear `1`. Accepted and rejected
sample counts are reported separately; compatibility fields now use the accepted-frame mean.

The sparse verification plane is sized in warped reference/output coordinates. This prevents
edge stars from being clipped merely because candidate/source bounds differ after inverse warp.

## Read-only real-session replay

When `AstroPhoto_Session_20260713_123724_20260717_041333.zip` is available:

```powershell
$env:ASTROPHOTO_REGISTRATION_REPLAY_ZIP='C:\path\AstroPhoto_Session_20260713_123724_20260717_041333.zip'
.\gradlew.bat testDebugUnitTest --tests com.example.astrophoto.JpegV2Stage8Test.realSessionReplayWhenZipIsProvided
```

The ZIP is extracted only under `build/tmp`. The replay prints prediction, sparse rank, radius,
correction, final transform, local evidence, verification metrics, acceptance path/reason, and
retry state for every frame.
