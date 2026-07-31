param(
    [string]$FixtureDirectory = 'app\src\test\resources\jpeg-stage6\urban-window-30',
    [string]$OutputDirectory = 'app\build\reports\ground-truth-review\urban-window-30'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine($PSScriptRoot, '..')
)

function Resolve-RepositoryPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath(
        [System.IO.Path]::Combine($repositoryRoot, $Path)
    )
}

$fixtureRoot = Resolve-RepositoryPath $FixtureDirectory
$outputRoot = Resolve-RepositoryPath $OutputDirectory
$requiredReportRoot = Resolve-RepositoryPath 'app\build\reports'
if (-not [System.IO.Directory]::Exists($fixtureRoot)) {
    throw "Fixture directory does not exist: $fixtureRoot"
}
if (-not $outputRoot.StartsWith($requiredReportRoot + [System.IO.Path]::DirectorySeparatorChar)) {
    throw "Review output must be below $requiredReportRoot"
}

$gradle = [System.IO.Path]::Combine($repositoryRoot, 'gradlew.bat')
$env:ASTROPHOTO_GROUND_TRUTH_FIXTURE_DIR = $fixtureRoot
$env:ASTROPHOTO_GROUND_TRUTH_REVIEW_OUTPUT_DIR = $outputRoot
try {
    & $gradle `
        ':app:cleanTestDebugUnitTest' `
        ':app:testDebugUnitTest' `
        '--tests' `
        'com.example.astrophoto.GroundTruthReviewCommandTest.generateConfiguredReviewPackage'
    if ($LASTEXITCODE -ne 0) {
        throw "Ground-truth review generation failed with exit code $LASTEXITCODE"
    }
} finally {
    Remove-Item Env:ASTROPHOTO_GROUND_TRUTH_FIXTURE_DIR -ErrorAction SilentlyContinue
    Remove-Item Env:ASTROPHOTO_GROUND_TRUTH_REVIEW_OUTPUT_DIR -ErrorAction SilentlyContinue
}

Write-Host "Generated ground-truth review package in $outputRoot"
