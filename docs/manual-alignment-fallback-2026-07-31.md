# Manual alignment: запрет молчаливого legacy fallback

Дата проверки: 2026-07-31. Базовый commit: `de75149a7c4d67a7015c73d7daaf69b193510984`.

## Аудит переходов

| Условие | Поведение до исправления | Поведение после исправления | Legacy разрешён |
|---|---|---|---|
| Меньше 8 входных кадров | `null` и legacy | `LEGACY_SHORT_SEQUENCE` | Да |
| Недостаточно звёзд в reference | `Unavailable` и legacy | `LEGACY_TOO_FEW_REFERENCE_STARS` | Да |
| Не пройден quality gate | `Unavailable` и legacy | `LEGACY_SEQUENCE_QUALITY_GATE` | Да |
| Не пройден residual gate | `Unavailable` и legacy | `LEGACY_SEQUENCE_RESIDUAL_GATE` | Да |
| Не пройден dynamic-shift gate | `Unavailable` и legacy | `LEGACY_DYNAMIC_SHIFT_GATE` | Да |
| Несовместимые входные данные | `Unavailable` и legacy | Контролируемая ошибка `SEQUENCE_PLANNER_UNSUPPORTED_INPUT` | Нет |
| Некорректный reference | `Unavailable` и legacy | Контролируемая ошибка `SEQUENCE_PLANNER_INVALID_REFERENCE` | Нет |
| Недостаточно принятых кадров | Контролируемая ошибка | Контролируемая ошибка `SEQUENCE_PLANNER_INSUFFICIENT_ACCEPTED_FRAMES` | Нет |
| Неожиданный `Exception` planner | Перехватывался и превращался в legacy | `SEQUENCE_PLANNER_INTERNAL_ERROR`, операция завершается без legacy и без результата | Нет |

Один selection-result используется Average aligned, Median aligned, Sigma aligned и dark-subtracted aligned average. Математика регистрации, пороги, transforms, weights, sensor mask, automatic JPEG v2 и RAW/DNG не изменялись.

## Модель и отчёт

Добавлена отдельная схема `astrophoto.manual.alignment/1`. Она не меняет automatic processing schema `astrophoto.jpeg.processing/3`.

Поля manual report:

- `manualAlignmentPath`;
- `manualAlignmentPathReason`;
- `manualAlignmentAttempted`;
- `legacyFallbackAllowed`;
- `legacyFallbackUsed`;
- `sequencePlannerFailureType`;
- `sequencePlannerFailureMessage`;
- `processingOutcome`;
- `outputPublished`;
- `cleanupCompleted`.

Неизвестные значения enum читаются как `UNKNOWN`; отсутствие manual-полей в старых отчётах безопасно. Для internal failure standalone JSON записывается существующим `ProcessingReportWriter`. Он создаётся после cleanup, содержит `FAILED`, `outputPublished=false` и `legacyFallbackUsed=false`. `CancellationException` пробрасывается; `Throwable` selection-слой не перехватывает.

## Проверки

- Focused manual alignment, rejected-frame, sensor-defect и Stage 6: 58/58.
- Processing report и backward compatibility: 165/165, 5 suites.
- Полный `testDebugUnitTest`: 949/949, 73 suites, 0 failures, 0 errors, 0 skipped.
- `assembleDebug`: успешно.
- APK: 14 134 313 байт, SHA-256 `a2e96be0bcda6b423b50f0a11e1320f4104413563ba3ef6799b44873d9a39ff1`.
- `git diff --check`: успешно до добавления этого отчёта; финальная проверка выполняется перед commit.

Regression-тесты внедряют ошибки до planner, после его результата и перед integration без global mutable state. Проверены все четыре manual aligned mode, отсутствие legacy/integration/publication, cleanup, cancellation и старые/неизвестные report-поля.

## Xiaomi smoke-test

Устройство: Xiaomi `23021RAA2Y`, serial `5cc69b3d`.

Установка выполнена только командой:

```text
C:\Users\79870\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r -t D:\YniUni\_AndroidProject\app\build\outputs\apk\debug\app-debug.apk
```

Результат обеих установок через эту же команду: `Success`. Финальная APK установлена после итоговой сборки. `firstInstallTime` остался `2026-07-13 12:07:00`; финальный `lastUpdateTime` — `2026-07-31 19:54:28`. Uninstall и очистка данных не выполнялись.

На существующей `Session_20260713_123724` запущен ручной `Average + SAFE`, source `Original JPEG`:

- path: `SEQUENCE_AWARE`;
- reason: `SEQUENCE_AWARE_SELECTED`;
- legacy fallback: `false`;
- reference frame: 9;
- input: 30;
- accepted: 28;
- rejected original indices: 23, 24;
- integrated original indices: 1–22, 25–30;
- sensor-defect regions/pixels: 8/552;
- excluded samples: 15 158;
- affected output pixels: 8 078;
- insufficient coverage: 0;
- crash: отсутствует.

Manual pipeline штатно публикует JPEG, а не PNG. Новый `StackedAligned_20260731_193757.jpg` имеет размер 297 803 байт, декодируется как JPEG 1364×1841 и имеет SHA-256 `7b7a2da6fba88787cda749bfdd6459e264ac47a7262e1a04d9b3f4c58d349b20`. Хэш полностью совпал с прежним `StackedAligned_20260728_224613.jpg`, поэтому successful baseline результата не изменился. MediaStore: `_size=297803`, `IS_PENDING=0`.

Существующий published PNG `RecoveredStars_20260731_171604.png` отдельно проверен: 1 410 320 байт, 1440×1920 RGBA, SHA-256 `efd312f9ed9d20b51188484338858021b407d944504f204714813c9f7a400a4c`; signature, IHDR/IDAT/IEND, CRC всех chunks и zlib decompression корректны, `IS_PENDING=0`.

До установки: 24 сессии, 175 файлов, 139 JPEG. После smoke-test: 24 сессии, 176 файлов, 140 JPEG; прирост — только новый manual result. В target source set осталось 30 кадров, aggregate SHA-256 не изменился: `9fd20bd9901d0555b19597d928e411778898e200fcb6537c98f997dfb6971d0e`.

У импортированной target-сессии нет `session_info.txt`; это существующее ограничение scoped-storage пути. Success-report подтверждён итоговым статусом приложения и `AstroPhotoAlignment` logcat. Короткой существующей сессии с 2–7 `Lights/JPEG` на устройстве нет, поэтому разрешённый short-sequence legacy path проверен unit-тестом без создания или удаления пользовательских кадров. После финальной повторной установки сохранность данных и запуск приложения подтверждены; повторный идентичный manual run не выполнялся, потому что устройство перешло под защищённый графическим ключом keyguard. Между успешным smoke-run и финальной APK production-код изменился только выравниванием отступа одной строки; после этого заново прошли focused/full tests и `assembleDebug`.
