# История изменений

## 0.1.0 (2026-11-14)

Первая сборка.

- Skeleton: сборка APK без Gradle (aapt2/javac/d8/apksigner), темы, иконка.
- `Http` + `SseParser`: стриминг чата, JSON-ответы.
- Сторы: SQLite (models/chats/messages/settings), секреты на AndroidKeyStore (AES-GCM).
- Свой JSON-парсер (проверяется на JVM тестами).
- Live-движки: OpenAI Realtime и Gemini Live, с реконнектом и чисткой ключей из ошибок.
- Каскад: системное распознавание/синтез и HTTP STT→чат→TTS с фолбэком на системный TTS.
- Экраны: чаты, каталог моделей (3 проверки), переписка (микрофон + Live), Live-экран,
  настройки голоса, экспорт чатов в JSON.
- Запись аудио реплик в wav (опция).
- Релизный конвейер: `release.ps1`, `version.txt`, `update-manifest.json`.
