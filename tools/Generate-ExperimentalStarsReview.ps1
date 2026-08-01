param(
    [string]$OutputDirectory = 'app\build\reports\experimental-stars-production\urban-window-30'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($PSScriptRoot, '..'))
$outputRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($repositoryRoot, $OutputDirectory))
$gradle = [System.IO.Path]::Combine($repositoryRoot, 'gradlew.bat')
$userProfileDirectory = [Environment]::GetFolderPath('UserProfile')
$javaCandidates = @(
    [Environment]::GetEnvironmentVariable('JAVA_HOME'),
    [System.IO.Path]::Combine($userProfileDirectory, '.gradle', 'jdks', 'eclipse_adoptium-21-amd64-windows.2'),
    [System.IO.Path]::Combine($userProfileDirectory, '.gradle', 'jdks', 'temurin-17.0.20+8'),
    'C:\Program Files\Android\openjdk\jdk-21.0.8'
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$javaHome = $javaCandidates | Where-Object {
    Test-Path ([System.IO.Path]::Combine($_, 'bin', 'java.exe'))
} | Select-Object -First 1
if (-not $javaHome) {
    throw 'A Java 17 or newer runtime is required.'
}
$env:JAVA_HOME = $javaHome
$env:ASTROPHOTO_EXPERIMENTAL_STARS_REVIEW_OUTPUT_DIR = $outputRoot

Push-Location $repositoryRoot
try {
    & $gradle `
        ':app:testDebugUnitTest' `
        '--tests' `
        'com.example.astrophoto.ExperimentalStarsProductionPresetTest' `
        '--no-daemon' `
        '--console=plain'
    if ($LASTEXITCODE -ne 0) {
        throw "Experimental Stars review generation failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

Write-Host "Generated Experimental Stars review package in $outputRoot"
