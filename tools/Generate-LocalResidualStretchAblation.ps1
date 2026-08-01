param(
    [string]$OutputDirectory = 'app\build\reports\local-residual-stretch-ablation\urban-window-30'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine($PSScriptRoot, '..')
)
$outputRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine($repositoryRoot, $OutputDirectory)
)
$gradle = [System.IO.Path]::Combine($repositoryRoot, 'gradlew.bat')
$userProfileDirectory = [Environment]::GetFolderPath('UserProfile')
$configuredJava = [Environment]::GetEnvironmentVariable('JAVA_HOME')
$javaCandidates = @(
    $configuredJava,
    [System.IO.Path]::Combine($userProfileDirectory, '.gradle', 'jdks', 'eclipse_adoptium-21-amd64-windows.2'),
    [System.IO.Path]::Combine($userProfileDirectory, '.gradle', 'jdks', 'temurin-17.0.20+8'),
    'C:\Program Files\Android\Android Studio\jbr'
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$javaHome = $javaCandidates | Where-Object {
    Test-Path ([System.IO.Path]::Combine($_, 'bin', 'java.exe'))
} | Select-Object -First 1
if (-not $javaHome) {
    throw 'A Java 17 or newer runtime is required; set JAVA_HOME before running this tool.'
}
$env:JAVA_HOME = $javaHome
$env:ASTROPHOTO_LOCAL_RESIDUAL_STRETCH_ABLATION_OUTPUT_DIR = $outputRoot

Push-Location $repositoryRoot
try {
    & $gradle `
        ':app:testDebugUnitTest' `
        '--no-daemon' `
        '--tests' `
        'com.example.astrophoto.LocalResidualStretchAblationTest.generatedReportIsCompleteAndSecondRunByteIdentical' `
        '--rerun-tasks' `
        '--console=plain'
    if ($LASTEXITCODE -ne 0) {
        throw "Local residual stretch ablation failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

Write-Host "Generated deterministic test-only local residual stretch ablation in $outputRoot"
