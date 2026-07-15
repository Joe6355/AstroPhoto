param(
    [Parameter(Mandatory = $true)][string]$InputDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [int]$CropX = 0,
    [int]$CropY = 0,
    [int]$CropWidth = 0,
    [int]$CropHeight = 0,
    [ValidateRange(2, 15)][int]$MaxFrames = 15,
    [ValidateRange(128, 960)][int]$MaxDimension = 640,
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

$codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
    Where-Object { $_.MimeType -eq 'image/jpeg' } |
    Select-Object -First 1
$encoder = [System.Drawing.Imaging.Encoder]::Quality
$parameters = [System.Drawing.Imaging.EncoderParameters]::new(1)
$parameters.Param[0] = [System.Drawing.Imaging.EncoderParameter]::new($encoder, [long]$JpegQuality)
$outputNames = [System.Collections.Generic.List[string]]::new()

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
    'name=local-stage6-real-series'
    "frames=$($outputNames -join ',')"
    "referenceFrame=$($outputNames[0])"
    'referenceStars=reference-stars.csv'
)
[System.IO.File]::WriteAllLines([System.IO.Path]::Combine($outputRoot, 'manifest.properties'), $manifest)
[System.IO.File]::WriteAllLines(
    [System.IO.Path]::Combine($outputRoot, 'reference-stars.csv'),
    @('# x,y,flux,background,contrast,width,ellipticity,confidence')
)
Write-Host "Generated $($outputNames.Count) anonymized fixture frames in $outputRoot"
