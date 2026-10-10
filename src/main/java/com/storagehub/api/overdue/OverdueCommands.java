package com.storagehub.api.overdue;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;

public final class OverdueCommands {
    private OverdueCommands() {}
    public enum Type { NOTE, REMINDER }
    public record FollowUp(@NotNull Type type,@NotBlank @Size(max=2000) String content,@NotNull @PositiveOrZero Long expectedVersion){@JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unknown follow-up field: "+key);}}
    public record Recovery(@NotBlank @Size(max=2000) String reason,@NotNull @PositiveOrZero Long expectedVersion){@JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unknown recovery field: "+key);}}
}
