# Что проверить руками на устройстве

Часть слоёв нельзя проверить на JVM (SQLite, Keystore, микрофон, экран).
Сборка и headless-тесты: `powershell -File build.ps1`, затем
`java -cp "build/classes;app/libs/nv-websocket-client-2.14.jar" ru.alexv.anyailive.TestMain`.

Устройство: `adb devices` (телефон по Wi-Fi `192.168.3.32:5555`; если `offline` — `adb connect 192.168.3.32:5555`).

## Task 4 — хранилища

1. Поставить apk: `adb install -r anyailive.apk`.
2. Открыть приложение, свернуть, открыть снова — не падает (БД создаётся в `onCreate`).
3. `adb shell run-as ru.alexv.anyailive ls -l databases/` → есть `anyailive.db`, `-wal`, `-shm`.
4. `adb shell run-as ru.alexv.anyailive ls -l shared_prefs/` → `secrets.xml` появляется только после первого сохранения ключа.
5. Сохранить ключ, убить процесс (`adb shell am force-stop ru.alexv.anyailive`), открыть снова:
   ключ читается (SecretStore.get не вернул null) — значит запись в Keystore пережила перезапуск.
6. `adb shell run-as ru.alexv.anyailive cat shared_prefs/secrets.xml` → в файле только base64-блок, ключа открытым текстом нет.
