# JPEG registration replay

Stage 8 includes a deterministic replay with stationary star-like detections, coherent sky
motion, missing observations, noise and a stronger raw zero-shift mode.

Run only that replay without installing the application:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.example.astrophoto.JpegV2Stage8Test
```

To replay a local exported session ZIP, keep the source archive unchanged and pass its path via
an environment variable. The helper extracts it only below `app/build/tmp`, runs thumbnail
analysis and registration, then removes the extracted copy:

```powershell
$env:ASTROPHOTO_REGISTRATION_REPLAY_ZIP='C:\path\AstroPhoto_Session_20260713_123724_20260716_150152.zip'
.\gradlew.bat testDebugUnitTest --tests com.example.astrophoto.JpegV2Stage8Test.realSessionReplayWhenZipIsProvided
```

The committed test contains no private JPEG data and does not access application sessions or
public storage.
