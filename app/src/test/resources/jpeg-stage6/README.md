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
Classify sources in `ground-truth.csv` as `star`, `sensor_defect`, or `uncertain`.
Stars use sky coordinates; sensor defects use camera coordinates. `uncertain` rows
must not participate in recall or retention metrics.

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
```

No absolute private path is stored in the manifest or test code.
