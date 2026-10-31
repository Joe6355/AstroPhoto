[CmdletBinding()]
param(
    [ValidateSet('com.joe6355.astrophoto','com.example.astrophoto')]
    [string]$ApplicationId = 'com.joe6355.astrophoto'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $repo
try {
    $sourceCommit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось определить коммит проекта.' }
    if (@(& git status --porcelain).Count -ne 0) {
        throw 'В рабочем дереве есть изменения. Сохраните их перед сборкой кандидата.'
    }
    if (-not (Test-Path -LiteralPath 'keystore.properties' -PathType Leaf)) {
        throw 'Нужен прежний локальный keystore.properties и его ключ подписи с ПК.'
    }
    & git check-ignore --quiet keystore.properties
    if ($LASTEXITCODE -ne 0) { throw 'keystore.properties должен быть исключён из Git.' }

    $sdk = $env:ANDROID_HOME
    if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = $env:ANDROID_SDK_ROOT }
    if (Test-Path -LiteralPath 'local.properties') {
        $sdkLine = Get-Content -LiteralPath 'local.properties' -Encoding UTF8 |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -Last 1
        if ($sdkLine) { $sdk = ($sdkLine -split '=',2)[1].Trim().Replace('\:',':').Replace('\\','\') }
    }
    if ([string]::IsNullOrWhiteSpace($sdk)) { throw 'Укажите Android SDK в local.properties или ANDROID_HOME.' }
    if (-not (Test-Path -LiteralPath (Join-Path $sdk 'build-tools') -PathType Container)) {
        throw "Android SDK не найден по пути '$sdk'. Проверьте sdk.dir в local.properties на этом ПК."
    }
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $buildTools) { throw 'Не найдены стабильные Android SDK Build Tools.' }
    $apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
    $aapt = Join-Path $buildTools.FullName 'aapt2.exe'
    if (-not (Test-Path -LiteralPath $apksigner) -or -not (Test-Path -LiteralPath $aapt)) {
        throw 'Для проверки APK нужны apksigner и aapt2 из Android SDK Build Tools.'
    }

    & .\gradlew.bat :app:assembleRelease "-Pastrophoto.installApplicationId=$ApplicationId" --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Сборка release завершилась ошибкой.' }
    if ((& git rev-parse HEAD).Trim() -ne $sourceCommit -or @(& git status --porcelain).Count -ne 0) {
        throw 'Исходники изменились во время сборки. Повторите сборку из сохранённого коммита.'
    }
    $outputRoot = [IO.Path]::GetFullPath((Join-Path $repo 'app/build/outputs/apk/release'))
    $metadata = Get-Content -LiteralPath (Join-Path $outputRoot 'output-metadata.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($metadata.applicationId -ne $ApplicationId -or @($metadata.elements).Count -ne 1) {
        throw 'Неверный package ID или неоднозначный APK в выходных метаданных.'
    }
    $element = $metadata.elements[0]
    if ([long]$element.versionCode -le 5) { throw 'Для обновления beta.4 нужен versionCode не ниже 6.' }
    $apk = [IO.Path]::GetFullPath((Join-Path $outputRoot $element.outputFile))
    if (-not $apk.StartsWith($outputRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Путь APK выходит за пределы выходной папки сборки.'
    }
    $badging = (& $aapt dump badging $apk) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='([^']+)'") { throw 'Не удалось проверить манифест APK.' }
    if ($Matches[1] -ne $ApplicationId -or $badging -match 'application-debuggable') {
        throw 'APK имеет неверный package ID или включённую отладку.'
    }
    if ($badging -notmatch "versionCode='([^']+)'" -or [long]$Matches[1] -ne [long]$element.versionCode) {
        throw 'Версия APK не совпадает с метаданными сборки.'
    }
    if ($badging -notmatch "versionName='([^']+)'" -or $Matches[1] -ne $element.versionName) {
        throw 'Название версии APK не совпадает с метаданными сборки.'
    }
    $signature = (& $apksigner verify --verbose --print-certs $apk) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $signature -notmatch 'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)') {
        throw 'APK не имеет действительной подписи.'
    }
    $certificate = $Matches[1]
    # Public certificate fingerprints of the two original phone installs, checked 27.10.2026.
    $expected = @{
        'com.joe6355.astrophoto' = '79e37ad16c7cceaf6bae398d926e548e3f64ea0a6e248975cceb2a6e3d374f93'
        'com.example.astrophoto' = '59ad5f75e7ae06cf4d098b160a4090654970413ed51dea37dd12ef5a4311a843'
    }
    if ($certificate -ne $expected[$ApplicationId]) {
        throw 'Подпись отличается от исходного приложения на телефоне. Этот APK нельзя использовать для его обновления.'
    }
    $folder = Join-Path $repo ("artifacts/pc-signed-release-" + (Get-Date -Format 'yyyyMMdd_HHmmss'))
    New-Item -ItemType Directory -Path $folder | Out-Null
    $copy = Join-Path $folder ("AstroPhoto-" + $element.versionName + '.apk')
    Copy-Item -LiteralPath $apk -Destination $copy
    $mappingCopy = Join-Path $folder 'mapping.txt'
    Copy-Item -LiteralPath 'app/build/outputs/mapping/release/mapping.txt' -Destination $mappingCopy
    $report = [ordered]@{
        sourceCommit = $sourceCommit
        applicationId = $ApplicationId
        versionName = $element.versionName
        versionCode = $element.versionCode
        certificateSha256 = $certificate
        apkSha256 = (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash.ToLowerInvariant()
        apkBytes = (Get-Item -LiteralPath $copy).Length
        debuggable = $false
        mappingFile = 'mapping.txt'
        mappingSha256 = (Get-FileHash -LiteralPath $mappingCopy -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $candidateJson = Join-Path $folder 'candidate.json'
    $report | ConvertTo-Json | Set-Content -LiteralPath $candidateJson -Encoding UTF8
    $archive = Join-Path $folder ("AstroPhoto-" + $element.versionName + '-signed-candidate.zip')
    Compress-Archive -LiteralPath $copy,$candidateJson,$mappingCopy -DestinationPath $archive
    Write-Output "Готовый подписанный кандидат: $copy"
    Write-Output "SHA-256: $($report.apkSha256)"
    Write-Output "Архив для переноса на ноутбук: $archive"
    Write-Output 'В архиве только APK, candidate.json и соответствующий mapping.txt; ключи не включаются.'
} finally {
    Pop-Location
}
