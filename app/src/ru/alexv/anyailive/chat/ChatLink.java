package ru.alexv.anyailive.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ru.alexv.anyailive.data.ChatStore;
import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.voice.CascadeSystem;

/**
 * Мост между голосовыми движками каскада и обычным текстовым чатом.
 *
 * Каскаду нужно «отдать историю — получить ответ», а у нас есть {@link ChatClient}, который
 * говорит на {@link ChatStore.Msg}. Этот класс переводит одно в другое, чтобы voice/* не знал
 * про data/*, а data/* не знал про движки.
 */
public final class ChatLink implements CascadeSystem.Chat {

    private final ChatClient client;
    private final Model model;
    private final String apiKey;

    public ChatLink(ChatClient client, Model model, String apiKey) {
        this.client = client;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public String complete(String systemPrompt, List<CascadeSystem.ChatMsg> messages, int timeoutMs)
            throws IOException {
        if (model == null) {
            throw new IOException("Модель не выбрана");
        }
        List<ChatStore.Msg> history = new ArrayList<ChatStore.Msg>();
        if (messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                CascadeSystem.ChatMsg m = messages.get(i);
                if (m == null || m.text == null) {
                    continue;
                }
                ChatStore.Msg row = new ChatStore.Msg();
                row.role = m.role;
                row.content = m.text;
                history.add(row);
            }
        }
        String reply = client.complete(model, apiKey, systemPrompt, history, timeoutMs);
        if (reply == null) {
            throw new IOException("Модель не ответила");
        }
        return reply;
    }
}
