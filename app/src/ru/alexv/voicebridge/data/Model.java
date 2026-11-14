package ru.alexv.voicebridge.data;

/** Модель из каталога пользователя. Чистый Java: тестируется на JVM. */
public class Model {

    public static final String LIVE_NONE = "";
    public static final String LIVE_OPENAI = "openai";
    public static final String LIVE_GEMINI = "gemini";

    public long id;
    public String name = "";
    /** База для чата, например https://openrouter.ai/api/v1 */
    public String baseUrl = "";
    public String modelId = "";
    /** Имя записи в SecretStore, сам ключ в БД не хранится. */
    public String keyRef = "";
    /** "", "openai" или "gemini". */
    public String liveProtocol = LIVE_NONE;
    /** Отдельный WSS-адрес, если отличается от baseUrl. */
    public String liveUrl = "";
    public String liveModelId = "";
    public String voice = "";
    public String sttModel = "";
    public String ttsModel = "";
    /** Дополнительные HTTP-заголовки, по строке "Name: value" через перевод строки. */
    public String headers = "";
    public String lastCheck = "";
    public long createdAt;

    public boolean live() {
        return liveProtocol != null && liveProtocol.length() > 0;
    }

    @Override
    public String toString() {
        return name == null ? "" : name;
    }
}
