param(
    [Parameter(Mandatory = $true)][string]$InputDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [int]$CropX = 0,
    [int]$CropY = 0,
    [int]$CropWidth = 0,
    [int]$CropHeight = 0,
    [ValidateRange(2, 30)][int]$MaxFrames = 30,
    [ValidateRange(0, 29)][int]$ReferenceFrameIndex = 0,
    [ValidateRange(128, 1920)][int]$MaxDimension = 640,
    [ValidateRange(60, 95)][int]$JpegQuality = 82
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$inputRoot = [System.IO.Path]::GetFullPath($InputDirectory)
$outputRoot = [System.IO.Path]::GetFullPath($OutputDirectory)
if (-not [System.IO.Directory]::Exists($inputRoot)) {
    throw "Input directory does not exist: $inputRoot"
}
[System.IO.Directory]::CreateDirectory($outputRoot) | Out-Null

$files = Get-ChildItem -LiteralPath $inputRoot -File |
    Where-Object { $_.Extension -in '.jpg', '.jpeg', '.JPG', '.JPEG' } |
    Sort-Object Name |
    Select-Object -First $MaxFrames
if ($files.Count -lt 2) {
    throw 'At least two JPEG frames are required.'
}
if ($ReferenceFrameIndex -ge $files.Count) {
    throw "ReferenceFrameIndex $ReferenceFrameIndex is outside the selected $($files.Count)-frame series."
}

$codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
    Where-Object { $_.MimeType -eq 'image/jpeg' } |
    Select-Object -First 1
$encoder = [System.Drawing.Imaging.Encoder]::Quality
$parameters = [System.Drawing.Imaging.EncoderParameters]::new(1)
$parameters.Param[0] = [System.Drawing.Imaging.EncoderParameter]::new($encoder, [long]$JpegQuality)
$outputNames = [System.Collections.Generic.List[string]]::new()
$fixtureWidth = 0
$fixtureHeight = 0

try {
    for ($index = 0; $index -lt $files.Count; $index++) {
        $source = [System.Drawing.Image]::FromFile($files[$index].FullName)
        try {
            $x = [Math]::Max(0, $CropX)
            $y = [Math]::Max(0, $CropY)
            $width = if ($CropWidth -gt 0) { $CropWidth } else { $source.Width - $x }
            $height = if ($CropHeight -gt 0) { $CropHeight } else { $source.Height - $y }
            $width = [Math]::Min($width, $source.Width - $x)
            $height = [Math]::Min($height, $source.Height - $y)
            if ($width -le 0 -or $height -le 0) { throw 'Crop is outside the source frame.' }
            $scale = [Math]::Min(1.0, $MaxDimension / [double][Math]::Max($width, $height))
            $targetWidth = [Math]::Max(1, [int][Math]::Round($width * $scale))
            $targetHeight = [Math]::Max(1, [int][Math]::Round($height * $scale))
            if ($fixtureWidth -eq 0) {
                $fixtureWidth = $targetWidth
                $fixtureHeight = $targetHeight
            } elseif ($fixtureWidth -ne $targetWidth -or $fixtureHeight -ne $targetHeight) {
                throw 'All generated fixture frames must have identical dimensions.'
            }
            $bitmap = [System.Drawing.Bitmap]::new($targetWidth, $targetHeight)
            try {
                $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
                try {
                    $graphics.DrawImage(
                        $source,
                        [System.Drawing.Rectangle]::new(0, 0, $targetWidth, $targetHeight),
                        [System.Drawing.Rectangle]::new($x, $y, $width, $height),
                        [System.Drawing.GraphicsUnit]::Pixel
                    )
                } finally {
                    $graphics.Dispose()
                }
                $name = 'frame-{0:D3}.jpg' -f $index
                $bitmap.Save([System.IO.Path]::Combine($outputRoot, $name), $codec, $parameters)
                $outputNames.Add($name)
            } finally {
                $bitmap.Dispose()
            }
        } finally {
            $source.Dispose()
        }
    }
} finally {
    $parameters.Dispose()
}

$manifest = @(
    'name=urban-window-30'
    "frames=$($outputNames -join ',')"
    "referenceFrame=$($outputNames[$ReferenceFrameIndex])"
    'groundTruth=ground-truth.csv'
    'groundTruthMetadata=ground-truth-metadata.properties'
)
[System.IO.File]::WriteAllLines([System.IO.Path]::Combine($outputRoot, 'manifest.properties'), $manifest)
$groundTruthPath = [System.IO.Path]::Combine($outputRoot, 'ground-truth.csv')
if (-not [System.IO.File]::Exists($groundTruthPath)) {
    [System.IO.File]::WriteAllLines(
        $groundTruthPath,
        @(
            '# schemaVersion=astrophoto.ground-truth/2'
            'id,x,y,class,confidence,annotation_source,review_status,reviewed_by,reviewed_at,notes,coordinate_space,support_frames,sky_residual_px,camera_residual_px'
        )
    )
} else {
    Write-Host "Preserved existing manual ground truth: $groundTruthPath"
}
$metadataPath = [System.IO.Path]::Combine($outputRoot, 'ground-truth-metadata.properties')
if (-not [System.IO.File]::Exists($metadataPath)) {
    [System.IO.File]::WriteAllLines(
        $metadataPath,
        @(
            'schemaVersion=astrophoto.ground-truth/2'
            "fixtureWidth=$fixtureWidth"
            "fixtureHeight=$fixtureHeight"
            "referenceFrameIndex=$ReferenceFrameIndex"
            'fixtureCoordinates=decoded fixture pixels, origin top-left'
            "referenceCoordinates=$($outputNames[$ReferenceFrameIndex]) pixels, identical to fixture coordinates"
            'cameraCoordinates=source frame pixels before sky transform'
            'outputCoordinates=aligned output pixels in reference-frame geometry'
            'confirmedManualIds='
            'automaticIdPrefixes=candidate-'
        )
    )
}
Write-Host "Generated $($outputNames.Count) anonymized fixture frames in $outputRoot"
