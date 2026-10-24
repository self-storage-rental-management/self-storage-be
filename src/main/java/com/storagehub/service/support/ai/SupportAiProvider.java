package com.storagehub.service.support.ai;

import java.util.List;

public interface SupportAiProvider {
    record Message(String role, String content) {}
    record Answer(String text, String model, boolean truncated) {}
    void requireAvailable();
    Answer answer(List<Message> messages);
}
