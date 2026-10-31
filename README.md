# AstroPhoto

Android-приложение для ручной астрофотографии, серийной JPEG-съёмки,
работы с сессиями и JPEG stacking. Package: `com.joe6355.astrophoto`.

## Продолжение работы и сборки

При переходе между ПК и ноутбуком сначала откройте **[CONTINUE_HERE_RU.md](CONTINUE_HERE_RU.md)**: там зафиксированы текущая сборка, завершённые проверки, сохранность данных и следующий шаг. Это помогает продолжать с актуального места, не повторяя закрытые задачи.

Проверенная основная сборка: **[предварительный релиз v0.9.0-beta.4](https://github.com/Joe6355/AstroPhoto/releases/tag/v0.9.0-beta.4)**. APK, пакет для тестирования и контрольные суммы находятся во вложениях релиза. [Памятка](docs/BETA_TESTING_RU.md) · [Приёмка](docs/beta4-main-acceptance-2026-10-30.md) · [Состав и SHA-256 вложений](docs/releases/v0.9.0-beta.4-assets.json).

Следующий кандидат — **beta.5 / code6**, подготовлен к подписи на ПК: [результаты сборки](docs/beta5-preparation-2026-10-31.md) · [инструкция подписи](docs/BETA5_SIGN_ON_PC_RU.md). Неподписанный комплект не является опубликованным или установленным релизом.

## Сборка

Требуются JDK 21 и Android SDK. Полная локальная проверка:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleDebugAndroidTest assembleRelease
```

`assembleDebugAndroidTest` компилирует instrumentation-тесты и собирает тестовый APK без устройства.

CI выполняет тот же gate для pull request и push в ветки `main` и `beta`.

Для обновления прежней установки `com.example.astrophoto` без потери данных
собирайте APK с `-Pastrophoto.installApplicationId=com.example.astrophoto`.
Подпись должна совпадать с установленным APK; установка выполняется через `adb install -r`.
Без параметра сохраняется основной ID `com.joe6355.astrophoto`.
Release собирается с R8 и сокращением ресурсов. Правила для сериализуемых
checkpoint-моделей: `app/src/main/keepRules/checkpoints.keep`.
Используется [DSL optimization для AGP 9.3+](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization).
CI сохраняет отчёты, APK и mapping R8; без локального ключа release APK не подписан.

Если ключ установленного приложения остался на другом компьютере, для проверок
можно собрать отдельный пакет с заметным именем, сохранив прежнюю установку и её данные:

```powershell
.\gradlew.bat assembleDebug '-Pastrophoto.installApplicationId=com.joe6355.astrophoto.qa' '-Pastrophoto.installAppLabel=AstroPhoto QA'
```

`astrophoto.installAppLabel` меняет только отображаемое имя этой сборки; по умолчанию
сохраняется `@string/app_name`. Отдельный пакет после разрешения доступа к изображениям
читает общие сессии `Pictures/AstroPhoto`, но его внутренние настройки и метаданные
отдельны. Такая установка не заменяет проверку совместимого обновления прежним ключом.

## Восстановление обработки

Deep Sky сохраняет завершённый анализ кадров, регистрацию, полноразмерное
уточнение alignment, интеграцию и результат адаптивной постобработки.
При повторном запуске той же обработки подходящие checkpoints используются автоматически.
Метаданные и пиксельные файлы проверяются SHA-256; повреждённый комплект этапа
удаляется целиком и пересчитывается, без частичного восстановления его файлов.
Исходники и опубликованные результаты при этом не удаляются.

Ограничения: текущий незавершённый этап повторяется; исходные полноразмерные
кеши кадров декодируются заново. Checkpoints требуют свободного места и не являются
резервной копией. При смене формата checkpoint старые данные пересчитываются.
Fingerprint пользовательских кадров основан на имени, размере и временной метке,
а не на полном хеше исходного JPEG.

ETA показывает оставшееся время **текущего этапа**, когда уже есть достаточно
измерений. При смене этапа, повторном запуске и восстановлении оценка собирается заново.

## Основные границы кода

- `JpegStacker.kt` — координация профиля и существующие ручные режимы.
- `ProfileFrameAnalysisCoordinator.kt`, `ProfileRegistrationStage.kt`,
  `ProfileIntegrationStage.kt`, `ProfilePostProcessingStage.kt`,
  `ProfileQualityStage.kt`, `ProfileOutputStage.kt` — этапы обработки.
- `JpegProcessingScreen.kt` — экран обработки.
- `MainActivity.kt`, `AppNavigationController.kt`, `CameraScreen.kt`,
  `CameraControlsState.kt` — оболочка, навигация, экран камеры и состояние её настроек.

## Release signing

Локальный `keystore.properties` и каталог `signing/` исключены из Git.
Шаблон настроек: `keystore.properties.example`. Release-ключ необходимо хранить
в резервной копии: без него нельзя публиковать обновления существующего приложения.

Автоматические проверки не заменяют запуск оптимизированного APK на Android.
Актуальные результаты проверок на телефоне и оставшиеся сценарии: [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

Подробная инструкция пользователя: [README_ASTROPHOTO.md](README_ASTROPHOTO.md).
