# Stage 6 real-series fixture

Private sessions are not copied into the repository. Generate a compact, anonymized fixture on Windows:

```powershell
.\tools\Generate-Stage6Fixture.ps1 `
  -InputDirectory C:\path\to\exported-jpegs `
  -OutputDirectory D:\safe\stage6-fixture `
  -CropX 0 -CropY 0 -CropWidth 1600 -CropHeight 1200
```

The crop should retain weak stars, city glow, a window reflection, a building edge, wires, JPEG noise, and fixed camera-space artifacts. Review every generated image before sharing it. Add reliable reference coordinates to `reference-stars.csv`.

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
reference-stars.csv
```

No absolute private path is stored in the manifest or test code.
