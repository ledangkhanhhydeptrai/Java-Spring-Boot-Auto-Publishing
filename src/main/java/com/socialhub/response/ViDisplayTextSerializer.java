package com.socialhub.response;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/** Jackson 3 HTTP serialization only; never changes the original Java/DB value. */
public final class ViDisplayTextSerializer extends ValueSerializer<String> {
    @Override
    public void serialize(String value, JsonGenerator generator, SerializationContext context) {
        generator.writeString(ViApiMessages.detail(value));
    }
}
