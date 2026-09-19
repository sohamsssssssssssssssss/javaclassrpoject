package com.jarvis.services.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for the minimal Vosk result JSON reader (no JSON dependency). */
class TextJsonTest {

    @Test
    void extractsFinalResultText() {
        String json = "{\"text\":\"hello jarvis\"}";
        assertEquals("hello jarvis", TextJson.extractText(json));
    }

    @Test
    void extractsPartialResultText() {
        String json = "{\"partial\":\"open cal\"}";
        assertEquals("open cal", TextJson.extractText(json, "partial"));
    }

    @Test
    void emptyWhenFieldMissing() {
        assertEquals("", TextJson.extractText("{\"partial\":\"x\"}"));
        assertEquals("", TextJson.extractText(""));
    }

    @Test
    void extractsSpeakerVector() {
        String json = "{\"text\":\"hi\",\"spk\":[0.5,-0.25,0.125]}";
        assertArrayEquals(new double[]{0.5, -0.25, 0.125}, TextJson.extractArray(json, "spk"));
    }

    @Test
    void nullWhenVectorMissingOrMalformed() {
        assertNull(TextJson.extractArray("{\"text\":\"hi\"}", "spk"));
        assertNull(TextJson.extractArray("{\"spk\":[0.5,oops]}", "spk"));
        assertNull(TextJson.extractArray("{\"spk\":[]}", "spk"));
    }
}
