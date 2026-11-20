package com.storagehub.service.support.ai;

import java.util.List;

public interface SupportAiProvider {
    record Message(String role, String content) {}
    enum Action { ANSWER, CLARIFY, HANDOFF, CAPABILITIES, HOURS, PHONE, CONTACT, PRICE, AVAILABILITY }
    record Lookup(String facility, String unitType, String startDate, String endDate) {}
    record Answer(List<String> sourceIds, String model, boolean truncated, Action action, String text, Lookup lookup) {
        Answer(List<String> sourceIds, String model, boolean truncated) {
            this(sourceIds, model, truncated, sourceIds.isEmpty() ? Action.HANDOFF : Action.ANSWER, null, null);
        }
    }
    void requireAvailable();
    Answer answer(List<Message> messages);
}
