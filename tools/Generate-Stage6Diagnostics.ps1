param(
    [string]$OutputDirectory = 'app\src\test\resources\jpeg-stage6\urban-window-30\diagnostics'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine($PSScriptRoot, '..')
)
$outputRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine($repositoryRoot, $OutputDirectory)
)
$gradle = [System.IO.Path]::Combine($repositoryRoot, 'gradlew.bat')
$env:ASTROPHOTO_STAGE6_DIAGNOSTICS_OUTPUT_DIR = $outputRoot

& $gradle `
    ':app:cleanTestDebugUnitTest' `
    ':app:testDebugUnitTest' `
    '--tests' `
    'com.example.astrophoto.Stage6CandidateDiagnosticsTest.requestedDiagnosticArtifactsAreGenerated'

if ($LASTEXITCODE -ne 0) {
    throw "Stage 6 diagnostics failed with exit code $LASTEXITCODE"
}

Write-Host "Generated Stage 6 diagnostics in $outputRoot"
