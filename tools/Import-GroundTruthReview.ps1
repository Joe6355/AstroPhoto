param(
    [Parameter(Mandatory = $true)][string]$ReviewQueue,
    [string]$InputGroundTruth = 'app\src\test\resources\jpeg-stage6\urban-window-30\ground-truth.csv',
    [string]$OutputGroundTruth = 'app\build\reports\ground-truth-review\urban-window-30\imported-ground-truth.csv',
    [string]$AuditLog = 'app\build\reports\ground-truth-review\urban-window-30\import-audit.json'
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

$inputPath = Resolve-RepositoryPath $InputGroundTruth
$queuePath = Resolve-RepositoryPath $ReviewQueue
$outputPath = Resolve-RepositoryPath $OutputGroundTruth
$auditPath = Resolve-RepositoryPath $AuditLog
if (-not [System.IO.File]::Exists($inputPath)) {
    throw "Ground-truth input does not exist: $inputPath"
}
if (-not [System.IO.File]::Exists($queuePath)) {
    throw "Review queue does not exist: $queuePath"
}
if ($inputPath -eq $outputPath) {
    throw 'OutputGroundTruth must differ from InputGroundTruth.'
}

$gradle = [System.IO.Path]::Combine($repositoryRoot, 'gradlew.bat')
$env:ASTROPHOTO_GROUND_TRUTH_IMPORT_INPUT = $inputPath
$env:ASTROPHOTO_GROUND_TRUTH_IMPORT_QUEUE = $queuePath
$env:ASTROPHOTO_GROUND_TRUTH_IMPORT_OUTPUT = $outputPath
$env:ASTROPHOTO_GROUND_TRUTH_IMPORT_AUDIT = $auditPath
try {
    & $gradle `
        ':app:cleanTestDebugUnitTest' `
        ':app:testDebugUnitTest' `
        '--tests' `
        'com.example.astrophoto.GroundTruthReviewCommandTest.importConfiguredReviewQueue'
    if ($LASTEXITCODE -ne 0) {
        throw "Ground-truth review import failed with exit code $LASTEXITCODE"
    }
} finally {
    Remove-Item Env:ASTROPHOTO_GROUND_TRUTH_IMPORT_INPUT -ErrorAction SilentlyContinue
    Remove-Item Env:ASTROPHOTO_GROUND_TRUTH_IMPORT_QUEUE -ErrorAction SilentlyContinue
    Remove-Item Env:ASTROPHOTO_GROUND_TRUTH_IMPORT_OUTPUT -ErrorAction SilentlyContinue
    Remove-Item Env:ASTROPHOTO_GROUND_TRUTH_IMPORT_AUDIT -ErrorAction SilentlyContinue
}

Write-Host "Imported reviewed ground truth to $outputPath"
Write-Host "Audit log: $auditPath"
