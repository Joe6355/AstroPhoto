# Stage 6 real-series fixture

The checked-in `urban-window-30` directory is the mandatory anonymized real-device
regression fixture. To regenerate it from the original exported series on Windows:

```powershell
.\tools\Generate-Stage6Fixture.ps1 `
  -InputDirectory C:\path\to\exported-jpegs `
  -OutputDirectory app\src\test\resources\jpeg-stage6\urban-window-30 `
  -CropX 0 -CropY 0 -CropWidth 1200 -CropHeight 1600 `
  -MaxFrames 30 -ReferenceFrameIndex 8 -MaxDimension 960 -JpegQuality 90
```

The crop retains weak stars, city glow, a window reflection, a building edge,
wires, JPEG noise, and fixed camera-space artifacts. Review every generated image.
Classify sources in versioned `ground-truth.csv` as `star`, `sensor_defect`, or
`uncertain`; keep coordinate definitions in `ground-truth-metadata.properties`.
Stars use sky coordinates and sensor defects use camera coordinates. Strict
retention/recall metrics include only `confirmed` rows whose
`annotation_source` is `manual` or `catalog`. Automatic, unreviewed, rejected,
unknown, and `uncertain` rows remain diagnostic-only.

Generate the offline review package with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Generate-GroundTruthReview.ps1
```

Edit the generated `review-queue.csv`, then import explicit decisions into a new
build-output CSV with `tools\Import-GroundTruthReview.ps1`. The importer never
overwrites the source ground truth.

Run a local fixture test with:

```powershell
.\gradlew.bat testDebugUnitTest -Dastrophoto.stage6.fixtureDir=D:\safe\stage6-fixture
```

Required layout:

```text
manifest.properties
frame-000.jpg
frame-001.jpg
...
ground-truth.csv
ground-truth-metadata.properties
```

No absolute private path is stored in the manifest or test code.
