# AstroPhoto

Android-приложение для ручной астрофотографии, серийной JPEG/RAW-съёмки,
работы с сессиями и JPEG stacking. Package: `com.joe6355.astrophoto`.

## Сборка

Требуются JDK 21 и Android SDK. Полная локальная проверка:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

CI выполняет тот же gate для pull request и push в ветки `main` и `beta`.

## Release signing

Локальный `keystore.properties` и каталог `signing/` исключены из Git.
Шаблон настроек: `keystore.properties.example`. Release-ключ необходимо хранить
в резервной копии: без него нельзя публиковать обновления существующего приложения.

Подробная инструкция пользователя: [README_ASTROPHOTO.md](README_ASTROPHOTO.md).
