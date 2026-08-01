param(
    [string]$OutputDirectory = 'app\build\reports\adaptive-asinh-ablation\urban-window-30'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine($PSScriptRoot, '..')
)
$outputRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine($repositoryRoot, $OutputDirectory)
)
$gradle = [System.IO.Path]::Combine($repositoryRoot, 'gradlew.bat')
$userProfile = [Environment]::GetFolderPath('UserProfile')
$configuredJava = [Environment]::GetEnvironmentVariable('JAVA_HOME')
$javaCandidates = @(
    $configuredJava,
    [System.IO.Path]::Combine($userProfile, '.gradle', 'jdks', 'temurin-17.0.20+8'),
    'C:\Program Files\Android\Android Studio\jbr'
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$javaHome = $javaCandidates | Where-Object {
    Test-Path ([System.IO.Path]::Combine($_, 'bin', 'java.exe'))
} | Select-Object -First 1
if (-not $javaHome) {
    throw 'A Java 17 runtime is required; set JAVA_HOME before running this tool.'
}
$env:JAVA_HOME = $javaHome
$env:ASTROPHOTO_ADAPTIVE_ASINH_ABLATION_OUTPUT_DIR = $outputRoot

Push-Location $repositoryRoot
try {
    & $gradle `
        ':app:testDebugUnitTest' `
        '--tests' `
        'com.example.astrophoto.AdaptiveAsinhAblationTest.generatedReportIsCompleteAndSecondRunByteIdentical' `
        '--rerun-tasks' `
        '--console=plain'
    if ($LASTEXITCODE -ne 0) {
        throw "AdaptiveAsinhStretch ablation failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

Write-Host "Generated deterministic test-only AdaptiveAsinhStretch ablation in $outputRoot"
