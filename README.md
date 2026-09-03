# AstroPhoto

Android-приложение для ручной астрофотографии, серийной JPEG/RAW-съёмки,
работы с сессиями и JPEG stacking. Package: `com.joe6355.astrophoto`.

## Сборка

Требуются JDK 21 и Android SDK. Полная локальная проверка:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

CI выполняет тот же gate для pull request и push в ветки `main` и `beta`.
Release собирается с R8 и сокращением ресурсов. Правила для сериализуемых
checkpoint-моделей: `app/src/main/keepRules/checkpoints.keep`.
Используется [DSL optimization для AGP 9.3+](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization).
CI сохраняет отчёты, APK и mapping R8; без локального ключа release APK не подписан.

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
Проверки на телефоне и новый crash logging в текущий объём работ не входят.

Подробная инструкция пользователя: [README_ASTROPHOTO.md](README_ASTROPHOTO.md).
