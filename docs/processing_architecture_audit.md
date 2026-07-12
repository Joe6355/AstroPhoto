# AstroPhoto Processing Architecture Audit

## Git baseline

- **CONFIRMED** Repository baseline before this audit was branch `main`, commit `41a4f0d150d41e017dec0ae41f1eb9aa78aa2f5d` (`Initial AstroPhoto project import`). Tag `astrophoto-before-open-result-fix` resolves to the same commit, and `origin` is `https://github.com/Joe6355/AstroPhoto.git`.
- **CONFIRMED** The initial working tree was clean. The audit is being performed on branch `codex/processing-rework`, created from that baseline.
- **CONFIRMED** `gradlew.bat test` and `gradlew.bat assembleDebug` completed successfully with the local JBR at `D:\AndroidStudio\jbr` for the terminal session.

## Processing UI entry points

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/MainActivity.kt` - `AstroPhotoApp` owns the application-level `AppScreen` state. It calls `SessionsScreen`, then `SessionDetailsScreen`; it does not call a processing algorithm directly. This is Compose navigation implemented with local state, not a `NavController`.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/SessionBrowser.kt` - `SessionDetailsScreen` is the session-level processing entry point. It calls `JpegStackingBlock`, `SessionFramesScreen`, `ProcessedResultsScreen`, and `SessionZipExporter.export`. It passes `onOpenResults` and operation-state callbacks to the stacking UI. It depends on Compose, Android `Context`, and navigation callbacks; it does not implement stacking.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `JpegStackingBlock` is the direct processing UI. It creates `SessionFramesRepository`, `FrameMarksStore`, and `JpegStacker`; loads frames and marks; filters inputs; owns processing state; and calls the selected `JpegStacker` method. It depends directly on Compose and Android `Context`, but not on MediaStore APIs or application navigation classes. No processing-specific tests call it.

## Manual processing flow

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `JpegStackingBlock.startStacking` is the active manual dispatcher. It calls exactly one of `JpegStacker.stack`, `stackWithDarkFrames`, `medianStack`, or `sigmaStack` according to `JpegStackingMode`. On success it stores `JpegStackResult`, calls `onStackCompleted`, and can invoke the caller-provided `onOpenResults` callback. It is Compose-owned and has no tests.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `StackProcessingWorkflow` and local `applyWorkflow` provide `QUICK`, `QUALITY`, and `MANUAL` presets for the active manual controls. `QUICK` selects Average, SAFE alignment, and no stretch. `QUALITY` selects Average + Dark when usable darks exist, otherwise Sigma for at least six selected lights, otherwise Average; it enables SAFE alignment and stretch. `JpegStackingBlock` calls `applyWorkflow`; no other caller or tests exist.
- **CONFIRMED** The active call chain is `AstroPhotoApp` -> `SessionDetailsScreen` -> `JpegStackingBlock` -> `SessionFramesRepository.loadFrames` plus `FrameMarksStore.loadOrCreate` -> one `JpegStacker` operation -> `JpegStacker.saveBitmap` -> `Processed/` JPEG. `ProcessedResultsScreen` is a later consumer, not part of the processing call chain.

## Ready-made profile flow

- **UNUSED** `app/src/main/java/com/example/astrophoto/AstroProcessingProfile.kt` - `AstroProcessingProfile` defines `NORMAL`, `DEEP_SKY`, `URBAN_SKY`, and `MAX_STARS` metadata. `JpegStacker.profileStack`, `JpegStackResult`, and result naming reference it. No UI entry point calls `profileStack`; no tests exist. It has no Compose, `Context`, MediaStore, or navigation dependency.
- **UNUSED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `JpegStacker.profileStack` contains the profile pipeline: recipe selection, sampled JPEG decode, star detection/alignment, average or signal-preserving sigma, background removal, stretch, star boost, star recount, warnings, and save. Repository search found no caller. It depends on Android `Bitmap`, `Context`, and `saveBitmap`/MediaStore, but not Compose or navigation. No tests exist.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `JpegStackingBlock` visibly states that the Deep Sky, Urban Sky, and Max Stars profile buttons are disabled. It displays no controls that invoke `profileStack`.

## Frame discovery and filtering

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/SessionFrames.kt` - `SessionFramesRepository.loadFrames` dispatches to `loadMediaStoreFrames` on Android 10+ and `loadFileFrames` on older versions. Callers include `SessionDetailsScreen`, `SessionFramesScreen`, `JpegAutoSelectionScreen`, `JpegStackingBlock`, and `ProcessedResultsScreen`. It calls `frameCategory` to recognize paths containing `Lights/JPEG`, `Lights/RAW`, `Darks/JPEG`, or `Darks/RAW`. It depends on Android `Context`, `ContentResolver`, MediaStore, and filesystem APIs; it has no Compose or navigation dependency. No processing tests exist.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `JpegStackingBlock` keeps only `LIGHTS_JPEG` for light stacking. It removes keys in `FrameMarks.bad + FrameMarks.autoBad`, optionally keeps only favorites, and excludes manually bad dark JPEGs. It is the only active owner of this stacking-input selection policy. No tests cover the policy.
- **PARTIAL** `app/src/main/java/com/example/astrophoto/JpegAutoSelection.kt` - `JpegAutoSelector.analyze`, called by `JpegAutoSelectionScreen`, samples Lights/JPEG and classifies read errors, black, dark, overexposed, blurry, and accepted frames. `JpegAutoSelectionScreen` writes selected failures into marks. The feature is active from `SessionFramesScreen`, but its thresholds and selection behavior have no tests.

## Frame marks

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/SessionFrames.kt` - `FrameMarks` owns `bad`, `favorite`, and `autoBad` key sets. `toggleBad` removes favorite/autoBad when manually marking bad; `toggleFavorite` removes bad/autoBad when favoriting. `SessionFramesScreen`, `JpegAutoSelectionScreen`, `SessionDetailsScreen`, and `JpegStackingBlock` consume it. It has no Android dependency and no tests.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/SessionFrames.kt` - `FrameMarksStore.loadOrCreate` and `save` read/write `frame_marks.json` through MediaStore on Android 10+ or a file on older versions. Callers are the frame UI, auto-selection UI, session detail summary, and stacking UI. It depends directly on Android `Context`, `ContentResolver`, MediaStore, and filesystem APIs; it has no Compose or navigation dependency. No tests exist.

## JPEG decoding

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `JpegStacker.readDimensions`, `decodeFrame`, `decodeMedianFrame`, `decodePreparedFrame`, and `openFrame` decode `SessionFrame` data from a content URI or file path with `BitmapFactory`. Average and dark paths can decode full ARGB bitmaps before resizing; Median, Sigma, and profiles use sampled decoding through `decodeMedianFrame`. These functions are called only inside `JpegStacker`. They depend on Android `Bitmap`, `Context`, and `ContentResolver`; no tests exist.
- **DUPLICATED** JPEG source opening and sampled decode also exist in `SessionFramesRepository.decodeSampledBitmap`, `JpegAutoSelector.decodeSampled`/`openFrame`, `SafeImageLoader`, and `ProcessedImageEditor`. They serve different consumers but do not share one decoder or one error contract. No tests cover any of these decoding paths.

## Average stacking

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `JpegStacker.stack` validates at least two Lights/JPEG frames, normalizes all frames to the minimum common dimensions, optionally computes image-based translation, and calls `addToRunningAverage`. `JpegStackingBlock.startStacking` calls it for `AVERAGE`. It then optionally calls the private legacy stretch, saves `Stacked_*.jpg` or `StackedAligned_*.jpg`, and appends session metadata. It depends on Android `Context`, `Bitmap`, MediaStore/filesystem saving, and coroutine dispatchers; it has no Compose or navigation dependency and no tests.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `addToRunningAverage`, `readShiftedPixels`, and `runningAverageChannel` implement row-chunked running arithmetic mean and translation fill. They are called by `stack`, `averageFrames`, and the average branch of `profileStack`. No tests exist.

## Dark Frame handling

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `JpegStacker.stackWithDarkFrames` validates Lights/JPEG and Darks/JPEG, chooses common minimum dimensions, calls `averageFrames` to create a master dark, then calls `calibrateAndAverageLights`. `JpegStackingBlock.startStacking` calls it for `AVERAGE_DARK`. It saves `StackedDark_*.jpg` or `StackedDarkAligned_*.jpg` and attempts to save `MasterDark_*.jpg`. It depends on `Context`, `Bitmap`, MediaStore/filesystem saving, and coroutines; no tests exist.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `subtractDarkAndAverage` and `calibratedChannel` apply `light - round(dark * 0.65) + shadowOffset`, clamp each channel, and feed the running average. `calibrateAndAverageLights` calls them. Supported offsets are 0, 8, 16, and 32. No tests exist.
- **PARTIAL** Master-dark save is wrapped with `runCatching(...).getOrNull()`, so the main dark-stacked result can succeed without a saved master dark. This is an explicit code path; no runtime result is asserted here.

## Median stacking

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `JpegStacker.medianStack` takes at most 30 Lights/JPEG frames, limits target pixels according to frame count, optionally applies image-based translation, calls `calculateMedian`, optionally applies the private legacy stretch, and saves `Median_*.jpg` or `MedianAligned_*.jpg`. `JpegStackingBlock.startStacking` calls it. It depends on `Context`, `Bitmap`, MediaStore/filesystem saving, and coroutines; no tests exist.
- **CONFIRMED** `calculateMedian` reads shifted rows, sorts per-channel values, and uses the middle value or mean of the two middle values. Only `medianStack` calls it. No tests exist.

## Sigma clipping

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `JpegStacker.sigmaStack` accepts sigma values 1.5, 2.0, 2.5, or 3.0, takes at most 30 Lights/JPEG frames, applies the same pixel limiting and optional image alignment as Median, calls `calculateSigmaClipping`, optionally applies the private legacy stretch, and saves `Sigma_*.jpg` or `SigmaAligned_*.jpg`. `JpegStackingBlock.startStacking` calls it. It depends on `Context`, `Bitmap`, MediaStore/filesystem saving, and coroutines; no tests exist.
- **CONFIRMED** `calculateSigmaClipping` and `sigmaClippedChannel` compute per-channel mean and standard deviation, keep values within `sigma * standardDeviation`, and average accepted values. `profileStack` can enable an additional bright-signal-preserving rule. The active manual Sigma path does not enable that rule. No tests exist.

## Image alignment

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `createAlignmentReference`, `createGrayscaleSample`, `findAlignment`, and `findAlignmentOrZero` implement the active manual translation alignment. They search up to 30 source pixels in each axis on a grayscale sample and optionally reject nonzero shifts using confidence and score thresholds. Average, Average + Dark, Median, and Sigma call this path when alignment is enabled. It depends on Android `Bitmap` but not `Context`, MediaStore, Compose, or navigation directly. No tests exist.
- **PARTIAL** SAFE and AGGRESSIVE manual modes both call the same image matcher; `alignmentSafe=false` only disables the confidence/score rejection in `findAlignmentOrZero`. No test or runtime evidence establishes alignment quality.

## ROI support

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/StarDetector.kt` - `AstroRoi` and `toRect` define normalized `Full`, `Top70`, `Top50`, and `Center70` regions. `StarDetector`, `StarAlignment`, `BackgroundRemoval`, and profile recipes consume ROI. It depends on Android `Rect`, not Compose, `Context`, MediaStore, or navigation. No tests exist.
- **UNUSED** ROI is only connected to `JpegStacker.profileStack`, which has no caller. The active manual Average, Dark, Median, and Sigma flow has no ROI parameter.

## Star detection

- **UNUSED** `app/src/main/java/com/example/astrophoto/StarDetector.kt` - `StarDetector.detect` estimates background/noise in an ROI, finds local luminance maxima, filters candidates, and returns `StarDetectionResult`. `StarAlignment`, `BackgroundRemoval` helpers, `AstroStretch`, `StarBoost`, and `profileStack` call its APIs. Because the only top-level processing caller is the unused profile path, no active UI flow reaches star detection. It depends on Android `Bitmap`/`Rect`, not `Context`, MediaStore, Compose, or navigation. No tests exist.

## Star-based alignment

- **UNUSED** `app/src/main/java/com/example/astrophoto/StarAlignment.kt` - `StarAlignment.align` calls `StarDetector.detect`, then `matchStars`, and falls back to `imageSafeAlignment`. `JpegStacker.profileStack` is its only repository caller. It depends on Android `Bitmap`, not `Context`, MediaStore, Compose, or navigation. No tests exist.
- **DUPLICATED** `StarAlignment.imageSafeAlignment` overlaps with the separate active grayscale translation matcher in `JpegStacker.findAlignment`; the implementations use different sampling and acceptance thresholds.

## Background removal

- **UNUSED** `app/src/main/java/com/example/astrophoto/BackgroundRemoval.kt` - `BackgroundRemoval.applyInPlace` builds a 24x24 interpolated background model, applies mode-specific correction, and optionally neutralizes urban tint. `JpegStacker.profileStack` is its only caller. It calls `AstroRoi.toRect` and `StarDetector` luminance/percentile helpers. It depends on Android `Bitmap`, not `Context`, MediaStore, Compose, or navigation. No tests exist.

## Astro Stretch

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - private `applyAstroStretchInPlace` is the active optional manual stretch used by Average, Dark, Median, and Sigma. It derives black/white points from luminance percentiles and applies a logarithmic channel curve. It depends on Android `Bitmap`; no tests exist.
- **UNUSED** `app/src/main/java/com/example/astrophoto/AstroStretch.kt` - `AstroStretch.applyInPlace` implements `NATURAL`, `SOFT`, `MEDIUM`, `STRONG`, and `EXTREME_PREVIEW`, returns clipping metrics, and is called only by the unused `profileStack`. It depends on Android `Bitmap`; no tests exist.
- **DUPLICATED** The active private stretch and the standalone `AstroStretch` class are separate histogram/log-curve implementations with separate mode and warning behavior.

## Star Boost

- **UNUSED** `app/src/main/java/com/example/astrophoto/StarBoost.kt` - `StarBoost.applyInPlace` brightens detected stars or applies `conservativeHighPass` when no stars are supplied. `JpegStacker.profileStack` is its only caller. It calls `StarDetector.luminance` and depends on Android `Bitmap`, not `Context`, MediaStore, Compose, or navigation. No tests exist.

## Result sanity checking

- **PARTIAL** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `saveBitmap` verifies that a MediaStore row or legacy file has a positive size and removes incomplete output on failure. All stack methods call it. This checks persistence, not image content. It depends on `Context`, MediaStore or filesystem APIs, and `CameraSettingsStore`; no tests exist.
- **UNUSED** `JpegStacker.profileStack` compares detected star counts before and after processing and records warnings when the final count is much lower, alignment is rejected, or Max Stars may amplify noise. No active UI caller reaches this check, and no tests exist.
- **MISSING** No active manual processing component validates the decoded output dimensions, luminance distribution, clipping, or star retention before saving. Repository search found no processing-specific sanity-check class or test.

## Processing state and coroutine ownership

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `JpegStackingBlock` owns all processing state in Compose `remember` values and starts work with `rememberCoroutineScope().launch`. It calls `onOperationStateChanged` so `SessionDetailsScreen` can gate export and management operations. There is no processing `ViewModel`, service, or retained job. No tests exist.
- **CONFIRMED** `JpegStacker.stack`, `stackWithDarkFrames`, `medianStack`, `sigmaStack`, and `profileStack` switch to `Dispatchers.IO`, check cancellation in frame/row loops, and dispatch progress callbacks to `Dispatchers.Main.immediate`. They do not own an independent scope.
- **PARTIAL** The processing job lifetime is the composable scope. State and ownership behavior across screen disposal or process recreation is not covered by tests, and no runtime claim is made.

## Result naming and saving

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt` - `saveBitmap` writes JPEG quality from `CameraSettingsStore` into `Pictures/AstroPhoto/<session>/Processed/`. Android 10+ uses MediaStore with `IS_PENDING`; older Android uses a temporary file and rename. It is called by all `JpegStacker` result paths. It directly depends on Android `Context`, MediaStore, and filesystem APIs; no tests exist.
- **CONFIRMED** Active names are `Stacked_`, `StackedAligned_`, `StackedDark_`, `StackedDarkAligned_`, `MasterDark_`, `Median_`, `MedianAligned_`, `Sigma_`, and `SigmaAligned_`, each followed by `yyyyMMdd_HHmmss.jpg`.
- **UNUSED** The unreachable profile path can save `DeepSky_`, `DeepSkyAligned_`, `UrbanSky_`, `UrbanSkyStrong_`, and `MaxStars_` names.
- **CONFIRMED** `appendSessionInfo`, `appendDarkStackSessionInfo`, `appendMedianSessionInfo`, `appendSigmaSessionInfo`, and `appendProfileSessionInfo` append processing metadata to `session_info.txt` through MediaStore or the legacy filesystem. Save success is returned even when metadata append fails, with `sessionInfoUpdated=false`.

## Existing tests

- **CONFIRMED** `app/src/test/java/com/example/astrophoto/ExampleUnitTest.kt` contains only `ExampleUnitTest.addition_isCorrect`; it does not call processing code.
- **CONFIRMED** `app/src/androidTest/java/com/example/astrophoto/ExampleInstrumentedTest.kt` contains only `ExampleInstrumentedTest.useAppContext`; it does not call processing code.
- **MISSING** No tests cover frame filtering, marks, decoding, Average, dark subtraction, Median, Sigma, image alignment, ROI, star detection, star alignment, background removal, stretch, boost, saving, or result naming.

## Confirmed working components

- **CONFIRMED** Here, "confirmed" means connected to the current source call graph, not runtime-validated. Active connected components are `SessionDetailsScreen`, `JpegStackingBlock`, `SessionFramesRepository`, `FrameMarksStore`, manual Average, Average + Dark, Median, Sigma, manual image alignment, manual optional stretch, JPEG save, session metadata append, and `ProcessedResultsRepository` discovery of the resulting name prefixes.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/ProcessedResults.kt` - `ProcessedResultsRepository.processedResultType` recognizes every active `JpegStacker` output prefix, and `loadMediaStoreResults`/`loadLegacyResults` scan the same session `Processed/` directory. It is called by `ProcessedResultsScreen`. It depends on Compose at the screen level and on `Context`, MediaStore/filesystem, and `SafeImageLoader` at the repository level. No processing integration test exists.

## Partial or uncertain components

- **PARTIAL** Auto-selection is connected to the frame UI and marks store, but has no tests.
- **PARTIAL** Manual alignment is connected to all manual stackers, but its quality and SAFE/AGGRESSIVE behavior have no tests or captured runtime evidence.
- **PARTIAL** Master-dark persistence is optional after the main dark stack is saved.
- **UNCERTAIN** Runtime memory behavior and output quality of all stack methods are unverified in this audit. Source code contains frame-count and pixel limits for Median, Sigma, and profiles, but no processing benchmark, fixture, or device test is present.
- **UNCERTAIN** The profile algorithm code compiles as part of the application only if the requested build succeeds; it has no reachable UI flow or direct test.

## Missing components

- **MISSING** Producers for `RecoveredStars_*.jpg`, `BackgroundRemoved_*.jpg`, and `StarsOnlyPreview_*.jpg` were not found. `ProcessedResultsRepository` only recognizes these prefixes as result types.
- **MISSING** RAW/DNG stacking is not implemented in the processing path; `JpegStacker` requires Lights/JPEG or Darks/JPEG.
- **MISSING** A processing-domain test suite and deterministic image fixtures are absent.
- **MISSING** A processing API independent of Compose and Android `Context` is absent; selection is in `JpegStackingBlock`, while decoding/saving are in `JpegStacker`.

## Duplicate or conflicting implementations

- **DUPLICATED** Image translation alignment exists in active `JpegStacker.findAlignment` and profile-only `StarAlignment.imageSafeAlignment`.
- **DUPLICATED** Stretch exists in active private `JpegStacker.applyAstroStretchInPlace` and profile-only `AstroStretch.applyInPlace`.
- **DUPLICATED** JPEG opening/decoding exists in `JpegStacker`, `SessionFramesRepository`, `JpegAutoSelector`, `SafeImageLoader`, and `ProcessedImageEditor` with separate sampling and error handling.
- **DUPLICATED** JPEG result saving exists in private `JpegStacker.saveBitmap` and private `ProcessedImageEditor.saveBitmap`; both implement MediaStore/legacy paths and metadata updates for different result producers.
- **PARTIAL** `JpegStackingBlock` exposes workflow presets named Quick and Quality inside the manual block while the separate astronomical profile implementation is disabled. They are distinct mechanisms rather than confirmed conflicting runtime paths.

## Processing files and responsibilities

- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegStacker.kt`: `JpegStackResult`, `JpegStacker`, manual stack algorithms, unused profile pipeline, decode/alignment helpers, save/metadata helpers, and Compose `JpegStackingBlock`.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/AstroProcessingProfile.kt`: profile names, descriptions, and file prefixes.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/SessionFrames.kt`: `SessionFrame`, category discovery, previews, `FrameMarks`, `FrameMarksStore`, and frame-management Compose UI.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/JpegAutoSelection.kt`: JPEG quality metrics, classification thresholds, auto-marking flow, and its Compose screen.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/StarDetector.kt`: ROI model, star candidate detection, luminance, and percentile helpers.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/StarAlignment.kt`: profile-only star matching and image fallback alignment.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/BackgroundRemoval.kt`: profile-only background model and correction.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/AstroStretch.kt`: profile-only named stretch modes and clipping warnings.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/StarBoost.kt`: profile-only detected-star boost and high-pass fallback.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/SessionBrowser.kt`: session details UI, processing host, operation gating, results entry, and ZIP entry.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/ProcessedResults.kt`: result discovery/classification, viewer/editor entry, comparison, rename, and delete.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/SafeImageLoader.kt`: viewer-oriented bounded decode and error result contract; it is not used by processing.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/ProcessedImageEditor.kt`: post-processing editor and `Edited_*.jpg` producer; it consumes `ProcessedResult` and is not called by `JpegStacker`.
- **CONFIRMED** `app/src/main/java/com/example/astrophoto/SessionZipExporter.kt`: whole-session ZIP producer; it consumes files under the session root, including `Processed/`, and is not called by `JpegStacker`.

## Dependencies on unrelated systems

- **CONFIRMED** Camera2 and camera preview: `app/src/main/java/com/example/astrophoto/CameraPreviewView.kt` - `CameraPreviewView.captureJpeg`, `captureRawDng`, and `captureTestJpeg` produce capture files. Processing code does not call `CameraPreviewView` or Camera2. Dependency direction is capture -> shared session storage -> `SessionFramesRepository` -> processing.
- **CONFIRMED** Capture UI: `app/src/main/java/com/example/astrophoto/MainActivity.kt` - `CameraScreen`, local `recordSessionFrame`, and `captureSeriesFrame` call `CameraPreviewView` capture methods. They do not call `JpegStacker`; processing later discovers their files by directory category.
- **CONFIRMED** Navigation: `AstroPhotoApp` selects `SessionDetailsScreen`; `SessionDetailsScreen` hosts `JpegStackingBlock` and toggles `ProcessedResultsScreen`. `JpegStackingBlock` knows only callbacks and does not modify application navigation state.
- **CONFIRMED** Processed results and viewer: `ProcessedResultsRepository` reads files produced in `Processed/`; `ProcessedResultsScreen` calls `ProcessedResultViewer`. Neither is called by `JpegStacker`. Dependency direction is processing save -> result discovery -> viewer.
- **CONFIRMED** Safe loader: `ProcessedResultsRepository` owns `SafeImageLoader` for viewer/comparison decoding. `JpegStacker` does not call it. Dependency direction is saved result -> result repository -> safe loader.
- **CONFIRMED** JPEG editor: `ProcessedResultsScreen` calls `ProcessedImageEditorScreen`, which uses `ProcessedImageEditor.saveEditedCopy` to write `Edited_*.jpg`. Processing does not call the editor. Dependency direction is saved result -> result screen -> editor -> additional saved result.
- **CONFIRMED** ZIP export: `SessionDetailsScreen` independently calls `SessionZipExporter.export`. Its `collectMediaStoreSources` or `collectFileSources` scans the whole session tree, including `Processed/`. Processing does not call export. Dependency direction is session files, including processing outputs -> ZIP exporter.

## Recommended next isolated stage

- **CONFIRMED** Recommended next stage: extract the active frame-eligibility selection from `JpegStackingBlock` into one pure Kotlin function and add focused unit tests for `bad`, `autoBad`, `favorite`, Lights/JPEG, and Darks/JPEG behavior. This is the smallest foundational change that makes the actual processing input contract testable without touching Camera2, preview, capture, navigation, `ProcessedResults`, the viewer, editor, or ZIP export.
