# Automatic sensor-mask baseline

Captured before production changes on 2026-07-30.

## Repository and fixture

- Baseline commit before implementation: `6c9cc459751e7a869a5573614b009e8e24b1ebf5`.
- Baseline tag: `astrophoto-baseline-before-automatic-mask-2026-07-30`.
- `urban-window-30` aggregate SHA-256: `35b3b62a9b3d0e73b23ff1349bcfca4194c06edfa529e87c3a6ff01509c17fda`.

The aggregate is SHA-256 over UTF-8 lines `relative/path=lowercase-file-sha256`,
sorted by absolute file path.

## Pre-fix regression evidence

Command:

```text
.\gradlew.bat :app:testDebugUnitTest --tests com.example.astrophoto.AutomaticSensorDefectFilteringTest
```

Result before the production patch: `1 test completed, 1 failed`.

Failure reason: automatic `LinearWeightedIntegrator` had no source-space
`sensorDefectMask` entry point.

## Xiaomi baseline

- Device: Xiaomi 23021RAA2Y.
- Installed package: `com.example.astrophoto`, version `0.9.0-beta.1` (`versionCode=2`).
- Session directories before update: 24.
- Files below `Pictures/AstroPhoto`: 171.
- JPEG files below `Pictures/AstroPhoto`: 139.
- Target session: `Session_20260713_123724`.
- Target source set: 30 files in `Lights/JPEG`.
- Profile: `URBAN_SKY_STRONG`.
- Reference: `AstroSeries_20260714_022705_009.jpg`.
- Accepted: 1-17 and 20-22, with frame 9 used as the reference.
- Rejected: 18, 19, 23, 24, 25, 26, 27, 28, 29, 30.
- Accepted frame count: 20/30.
- Transform-sequence score: `0.8716512`.
- Selected candidate: `CLEAN_STACK`.
- Fallback reason: `sky_mad_increased_excessively`.
- Integration time: `99,682 ms`.
- Total processing time: `316,199 ms`.
- Peak observed Java heap: `83,054,560 bytes`.
- PNG: `RecoveredStars_20260729_121950.png`, `1,410,562 bytes`.
- PNG SHA-256: `f657321c277e1443d1b4ced5082d13512d6a5d75b198976797695e188c1508b6`.

Full-size device images are intentionally not stored in Git.

## Deterministic fixture result after filtering

- Confirmed mask regions: 10.
- Excluded integration samples: 4,620.
- Affected output pixels: 2,554.
- Insufficient-coverage pixels: 0.
- Defect 01 positive trail contrast: `0.481227 -> 0.010364`.
- Defect 01 absolute residual: `0.481227 -> 0.015545`.
- Defect 02 positive trail contrast: `0.248273 -> 0.0`.
- Defect 02 absolute residual: `0.248273 -> 0.195409`; the remainder is a
  negative quantization residual below one luminance code, not a new trail.
- All three current `STAR` labels have identical contrast, centroid and PSF
  width before and after filtering.
- Background MAD: `2.587000 -> 2.587000`.
- Background RMS: `13.086571 -> 13.085203`.

## Xiaomi stop report

The APK was updated with `adb install -r -t`; the application was not
uninstalled and its data was not cleared. The same
`Session_20260713_123724` input and `URBAN_SKY_STRONG` profile were used.

- Reference, registrations, transforms and frame weights are identical to the
  baseline report.
- Accepted: 1-17 and 20-22.
- Rejected: 18, 19 and 23-30.
- Automatic mask: 8 regions, 552 source pixels, 13,848 excluded samples and
  7,072 affected output pixels.
- Insufficient-coverage pixels: 0; unmasked retry: false.
- Integration time: `99,682 -> 82,632 ms` (`-17.1%`).
- Total time: `316,199 -> 435,657 ms` (`+37.8%`), failing the `+10%` gate.
- Mask construction alone took `137,280 ms`; this is the identified
  performance blocker.
- Peak observed Java heap: `83,054,560 -> 83,279,824 bytes` (`+0.27%`).
- The first device comparison used invalid coordinates. The fixture was made
  from source crop `(0,0,1200,1600)` resized to `720x960`, so fixture
  coordinates map to the 1440x1920 output as `fixture / 0.6`.
- At corrected coordinates, published defect 01 positive contrast changed
  `1.1758 -> 0.8970` and absolute residual `1.2136 -> 1.0234`.
- Published defect 02 positive contrast changed only
  `0.6416 -> 0.6386`, while absolute residual worsened
  `0.8223 -> 0.9492`. The published-PNG trail gate therefore still fails.
- PNG: `RecoveredStars_20260731_004309.png`, 1,410,568 bytes, SHA-256
  `de934f95c4b185ae9d518b434bb2145e8e571de8403ad276a096f71db845cc62`.
- PNG signature, chunk CRCs and IDAT decompression are valid; dimensions are
  1440 x 1920 RGBA8.
- MediaStore reports the published row with `is_pending=0` and size 1,410,568.
- After the run: 24 sessions, 172 files, 139 JPEG files and all 30 target
  source frames remain.

Because the performance gate and real-device trail-reduction requirement did
not pass, the sensor-filtering commit and the legacy-fallback correction were
not created.

## Candidate-lineage trace

The deterministic replay saves full candidates and identical-coordinate crops
under `app/build/reports/automatic-sensor-lineage/urban-window-30/`.

- No common-region crop, composition crop or output resize occurs in this
  replay. Device crop coordinates are derived by the explicit `fixture / 0.6`
  mapping above.
- The quality gate selected `CLEAN_STACK`.
- The selected candidate has the masked integration in its actual parent
  lineage and differs from the equivalent unmasked candidate.
- Selected canonical ARGB SHA-256:
  `8a554a9bfb0f37fff4c487fedd3d9fc6af3c340e87ff43cdaee6a3c396daad41`.
- Encoded and published PNG SHA-256:
  `c9dcab4ff463c4a3ef32af6135cf2ffb24524ea85596c1ce700030b6c670df4d`.
  The encoded and published bytes are identical.
- Defect 01 positive trail metric: unmasked integration `0.48123`, masked
  integration `0.01036`, composed clean `0.10127`, selected/published
  `0.10127`.
- Defect 02 positive trail metric: unmasked integration `0.24827`, masked
  integration `0.0`, reference-star preservation `0.20332`, composed clean
  `0.44641`, selected/published `0.44641`.
- Defect 02 has mean effective sky alpha `0.34678`. Reference-star
  preservation changes one of 22 local-contrast measurement neighborhoods;
  foreground composition then blends most of the remaining signal back from
  the reference.
- The processed candidate is rejected for
  `sky_mad_increased_excessively|banding_increased_excessively`; it is not the
  published candidate.
- PNG encoding and publication do not change the selected pixels.

This proves that the missing real-device improvement is not caused by candidate
selection, PNG encoding or MediaStore publication. It is reintroduced before
selection: first by reference-star preservation for defect 02, then primarily
by sky/foreground composition where effective sky alpha is low.

## Mask-construction profile

The fixture run processed 30 observations, 20,736,000 analysis pixels and
61,657 compact candidates.

- Existing observation extraction: `1,841 ms`; measured JVM allocation
  `35,749,344 bytes`.
- Candidate matching: `3,333.8 ms` of `3,353 ms` total construction time.
- Candidate visits: `1,579,754,808`.
- Distance comparisons: `1,344,922,040`.
- Identity lookups: `11,660,332`.
- Recurrence/classification: `3.2 ms`.
- Footprint construction: `0.1 ms`.
- Mask validation/scaling: `8.8 ms`.
- Additional mask-construction image decodes: `0`.
- Additional mask-construction full-frame scans: `0`.

The 137.3-second Xiaomi overhead comes from the quadratic brute-force
`TemporalPixelConsistency.stationaryTracks` candidate matcher, not from another
decode or full-resolution scan. No optimization was applied because the
published trail and coordinate stop conditions fired; the exact ten-region
fixture membership remains locked by regression test.

## Local finalization result (2026-07-31)

The finalization patch keeps the masked integration value inside the
three-pixel support of a filtered sample in a reference-star preservation
patch. Reference pixels outside that local support remain preserved.
Composition also keeps the masked clean-sky sample when both the
affected-output map and the confirmed reference footprint identify the pixel.

- Reference-star samples skipped: 269 attempts / 187 distinct output pixels.
- Composition reference samples skipped: 210 output pixels.
- Selected candidate: `CLEAN_STACK`.
- Selected canonical ARGB SHA-256:
  `63489a0e51afc4977fc3b2b48ac0f7d271eb8fc46013555affc92e8edbf88eea`.
- Encoded and published PNG SHA-256:
  `81988e36e8e0f5ab34bb4d4e8d34bb204d7a0bb0b1a99314a268a1f941bfae2a`.
- Defect 01 positive trail: unmasked `0.481227`, masked `0.010364`,
  star-preserved `0.010364`, composed/published `0.010364`.
- Defect 02 positive trail: unmasked `0.248273`, masked `0.0`,
  star-preserved `0.0`, composed/published `0.066455`.
- Insufficient coverage remains 0.
- All current annotated `STAR` labels pass the existing contrast, centroid and
  PSF gates against the equivalent unmasked final-candidate baseline.

The matcher now uses a deterministic primitive-array spatial grid. Fixture
tracks, region IDs and footprint membership are exactly equal to the
brute-force reference:

- Candidate visits: `1,579,754,808 -> 214,022`.
- Distance comparisons: `1,344,922,040 -> 214,022`.
- Identity lookups: unchanged at `11,660,332`.
- Fixture construction: approximately `3,354 ms -> 906 ms` in the recorded
  local run.
- Measured construction allocation: `57,865,144 -> 31,155,728 bytes` after
  replacing boxed sort entries with primitive arrays.
- Additional image decodes: 0.
- Additional full-frame scans: 0.

## Xiaomi finalization stop report (2026-07-31)

The rebuilt APK was installed over the existing application only with
`adb install -r -t D:\YniUni\_AndroidProject\app\build\outputs\apk\debug\app-debug.apk`.
The application was not uninstalled and its data was not cleared.

- Output: `RecoveredStars_20260731_043924.png`.
- Reference: frame 9.
- Accepted: 1-17 and 20-22.
- Rejected: 18, 19 and 23-30.
- Reference, accepted/rejected decisions, transforms and weights are exactly
  equal to the baseline report after canonical ordering.
- Mask: 8 regions / 552 source pixels.
- Excluded samples: 13,848; affected output pixels: 7,072.
- Reference-star preservation skipped 964 attempts / 917 distinct output
  pixels; composition skipped 552 reference pixels.
- Insufficient coverage: 0; unmasked retry: false.
- Selected candidate: `CLEAN_STACK`; all clean, processed and selected lineage
  markers are masked.
- Selected canonical ARGB SHA-256:
  `34cebe0c581d33d7ed243ba855b4f4e0f31d4603ab328e76dfea4c29c093c90f`.
- Published PNG SHA-256:
  `efd312f9ed9d20b51188484338858021b407d944504f204714813c9f7a400a4c`.
- Integration time: `99,682 -> 90,727 ms`.
- Mask construction: `137,280 -> 2,897 ms`.
- Total time: `316,199 -> 334,435 ms` (`+5.8%`).
- Peak observed heap: `83,054,560 -> 86,642,768 bytes` (`+4.3%`).
- Candidate visits and distance comparisons: 238,001 each; additional image
  decodes and full-frame scans: 0.
- Published defect 01 positive/absolute trail metrics:
  `3.094600/3.118250 -> 0.088600/0.238600`.
- Published defect 02 positive/absolute trail metrics:
  `0.509250/0.626350 -> 0.132350/0.400000`.
- Both transformed paths are inside the fixed device crops
  `862,512,92,100` and `1072,630,92,100`.
- PNG is 1,410,320 bytes, 1440 x 1920 RGBA8; signature, 77 chunk CRCs,
  11,061,120 decompressed IDAT bytes and image decode are valid.
- MediaStore reports size 1,410,320 and `is_pending=0`.
- The process remained alive. There are 24 sessions, 174 total files, 139 JPEG
  files and 30 target source JPEG files. Their post-run aggregate SHA-256 is
  `f87f1436c5d8cdd52f427473c3da86f4446f1b809bfd783ec67d6a9aa0ff1965`;
  all per-file hashes match the pre-install snapshot.

The fixture passes, including all three current annotated `STAR` labels, but
the real-device report evaluates the same 44 reference sources and retains
42, while the baseline retained 44. Localizing preservation from a whole star
patch to the filtered sample support did not change the published output or
this 42/44 result. This is the remaining stop condition. No production commit
or push was created.

## Accepted production result (2026-07-31)

The automatic sensor filtering result is accepted for production with the
known reference-retention limitation below. No attempt is made in this patch
to reconstruct the two affected sources.

- Source-space `SensorDefectMask` filtering is applied by
  `LinearWeightedIntegrator`.
- Reference-star preservation and sky/foreground composition preserve the
  sensor-defect lineage.
- The quadratic candidate matcher is replaced by a deterministic spatial
  grid. Mask membership, reference selection, registration decisions,
  transforms and frame weights remain unchanged.
- Mask construction improved from `137.280 s` to `2.897 s` without an
  additional full-frame decode or scan.
- Published defect trails are substantially reduced. Device crop 01
  positive/absolute metrics changed from `3.094600/3.118250` to
  `0.088600/0.238600`; crop 02 changed from `0.509250/0.626350` to
  `0.132350/0.400000`.
- Fixture defect 01 changed from `0.481227` to `0.010364`; defect 02 changed
  through the masked, preserved and composed stages as
  `0.248273 -> 0 -> 0 -> 0.066455`. Insufficient coverage remains `0`.
- Fixture background MAD is unchanged at `2.587000`; RMS changed from
  `13.086571` to `13.085203`. All current fixture `STAR` labels pass their
  retention gates.
- The final Xiaomi run used frame 9 as reference, accepted frames `1-17` and
  `20-22`, and rejected frames `18`, `19` and `23-30`. The mask contains 8
  regions and 552 source pixels; 13,848 samples were excluded and 7,072 output
  pixels were affected.
- Integration time changed from `99.682 s` to `90.727 s`; the latest total
  processing time was `310.482 s` versus the `316.199 s` baseline. Peak
  observed heap was `89,722,864 bytes`, or `+8.029%` versus baseline. The
  performance and memory gates pass.
- The published PNG is valid and non-empty. The process did not crash.
  All 24 sessions, 139 JPEG files and all 30 target source frames remain;
  the target-source aggregate SHA-256 is unchanged at
  `f87f1436c5d8cdd52f427473c3da86f4446f1b809bfd783ec67d6a9aa0ff1965`.

Final repository verification:

- Automatic filtering, spatial-index equivalence and Stage 6 focused tests:
  `60/60` passed.
- Processing-report focused tests: `20/20` passed.
- Complete unit suite: `938/938` passed across 72 suites, with no failures,
  errors or skipped tests.
- `assembleDebug`: successful.
- `git diff --check`: successful.

### Known limitation: reference-star retention 42/44

`42 of 44 currently measured reference sources passed retention. The two
failures overlap confirmed camera-space sensor defects.`

- `reference-source-003`, coordinates `(72.156, 770.082)`, intersects the
  source mask. Contrast remains `139 -> 139`, but centroid shift is
  `1.997 px`, so it fails the centroid threshold.
- `reference-source-034`, coordinates `(78.000, 1026.553)`, intersects the
  source mask. Contrast changes `34 -> 2`, centroid shift is `2.311 px`, and
  PSF width changes `4.036 -> 7.317 px`; it fails because the core is lost and
  the PSF changes.

These two reference sources directly intersect confirmed camera-space sensor
defects. Forcing their restoration from the reference could restore the
removed sensor trails. Defect-safe PSF reconstruction and inpainting are not
part of this patch. This result must not be reported as `44/44` or as retaining
all visible stars.
