# anyAIlive

Android-клиент для живого голосового разговора с любой моделью: свой API-ключ, свой base URL,
никакого сервера посередине. Два голосовых движка в одном приложении:

- **Live** — настоящий speech-to-speech по realtime-протоколу (OpenAI Realtime API, Gemini Live).
  Аудио идёт потоком, модель слышит перебивание и отвечает звуком за ~0.8 с.
- **Каскад** — распознавание речи → обычный `/chat/completions` → синтез речи.
  Работает с любым OpenAI-совместимым агрегатором, почти бесплатно.

Какая модель умеет Live, видно в каталоге: у каждой есть флаг протокола и три кнопки проверки
(чат, ключ Gemini, realtime).

## Что внутри

- Текстовый чат со стримингом (SSE) по любому OpenAI-совместимому эндпоинту.
- Голосовой режим прямо в переписке: кнопка «Микрофон» запускает каскад, «Live» открывает
  отдельный экран с пульсирующим кругом и субтитрами обеих сторон.
- Каталог моделей: имя, base URL, модель, ключ, при желании отдельный realtime URL и модель.
- Настройки голоса: движок (авто/Live/каскад), язык, модели STT и TTS, голос, перебивание,
  запись аудио реплик, хранение истории.
- Экспорт переписки в JSON — одного чата или всех сразу, через системный диалог сохранения.

## Безопасность

Ключи не покидают устройство: хранятся в `SharedPreferences`, зашифрованные ключом
AndroidKeyStore (AES/GCM). Сервера, телеметрии и аккаунтов нет. Подробности — в [SECURITY.md](SECURITY.md).

## Сборка

Нужны Android SDK (build-tools 34, platform android-34) и JDK 17+ (`JAVA_HOME`).

```powershell
$env:JAVA_HOME = "C:\Users\you\java\jdk-17"
powershell -File build.ps1
```

Результат — `anyailive.apk`, подписанный отладочным ключом `debug.keystore` (alias `anyailive`,
пароль `android`). Gradle не нужен: сборка идёт напрямую через
`aapt2 → javac → jar → d8 → zipalign → apksigner`.

Тесты без устройства:

```powershell
java -cp "build/classes;app/libs/nv-websocket-client-2.14.jar" ru.alexv.anyailive.TestMain
```

## Релиз

```powershell
powershell -File release.ps1 -Repo "owner/anyailive" -Notes "what changed"
```

Кладёт `dist/anyailive-<версия>.apk` и `dist/update-manifest.json` (версия, sha256, размер, URL).
Дальше — тег и релиз на GitHub. Версия живёт в `version.txt`.

## Как настроить модель

1. «Модели» → «Добавить»: имя, base URL (`https://api.openai.com/v1`, `https://openrouter.ai/api/v1`,
   `https://tooken.club/v1` …), id модели, ключ.
2. Нажать «Проверить чат» — должен прийти ответ модели.
3. Если нужен нативный голос: указать протокол Live (`openai` или `gemini`), realtime URL/модель
   и проверить кнопкой «Проверить realtime».

## Ограничения

- Дуплекс честный только при доступном AEC на устройстве; иначе микрофон глушится, пока
  модель говорит (полудуплекс).
- Realtime тарифицируется за аудио-минуты: на OpenAI это центы за минуту, на Gemini Live
  на порядок дешевле. Каскад почти бесплатный.
- Агрегаторы realtime не проксируют: Live работает только с провайдером, который сам отдаёт
  realtime-эндпоинт.
- minSdk 24 (Android 7.0).

## Лицензия

MIT — см. [LICENSE](LICENSE).
