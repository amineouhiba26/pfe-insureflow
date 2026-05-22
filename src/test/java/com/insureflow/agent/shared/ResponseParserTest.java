package com.insureflow.agent.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseParserTest {

    // ── extractJson ───────────────────────────────────────────────────────────

    @Test
    void extractJson_plainObject_returnedAsIs() {
        String json = "{\"key\":\"value\"}";
        assertThat(ResponseParser.extractJson(json)).isEqualTo(json);
    }

    @Test
    void extractJson_stripsMarkdownFence() {
        String raw = "```json\n{\"key\":\"value\"}\n```";
        assertThat(ResponseParser.extractJson(raw)).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void extractJson_stripsLeadingText() {
        String raw = "Sure, here you go: {\"confidence\":0.9}";
        assertThat(ResponseParser.extractJson(raw)).isEqualTo("{\"confidence\":0.9}");
    }

    @Test
    void extractJson_nestedObject_findsMatchingBrace() {
        String raw = "{\"outer\":{\"inner\":1}}";
        assertThat(ResponseParser.extractJson(raw)).isEqualTo(raw);
    }

    @Test
    void extractJson_nullInput_returnsEmptyObject() {
        assertThat(ResponseParser.extractJson(null)).isEqualTo("{}");
    }

    @Test
    void extractJson_blankInput_returnsEmptyObject() {
        assertThat(ResponseParser.extractJson("   ")).isEqualTo("{}");
    }

    @Test
    void extractJson_noObjectInInput_returnsEmptyObject() {
        assertThat(ResponseParser.extractJson("no json here")).isEqualTo("{}");
    }

    // ── getString ─────────────────────────────────────────────────────────────

    @Test
    void getString_existingKey_returnsValue() {
        assertThat(ResponseParser.getString("{\"status\":\"APPROVED\"}", "status", "UNKNOWN"))
                .isEqualTo("APPROVED");
    }

    @Test
    void getString_missingKey_returnsDefault() {
        assertThat(ResponseParser.getString("{}", "status", "UNKNOWN"))
                .isEqualTo("UNKNOWN");
    }

    @Test
    void getString_nullValue_returnsDefault() {
        assertThat(ResponseParser.getString("{\"status\":null}", "status", "UNKNOWN"))
                .isEqualTo("UNKNOWN");
    }

    @Test
    void getString_invalidJson_returnsDefault() {
        assertThat(ResponseParser.getString("not-json", "key", "default"))
                .isEqualTo("default");
    }

    // ── getDouble ─────────────────────────────────────────────────────────────

    @Test
    void getDouble_existingKey_returnsValue() {
        assertThat(ResponseParser.getDouble("{\"confidence\":0.87}", "confidence", 0.0))
                .isEqualTo(0.87);
    }

    @Test
    void getDouble_missingKey_returnsDefault() {
        assertThat(ResponseParser.getDouble("{}", "confidence", 0.5))
                .isEqualTo(0.5);
    }

    @Test
    void getDouble_nullValue_returnsDefault() {
        assertThat(ResponseParser.getDouble("{\"confidence\":null}", "confidence", 0.5))
                .isEqualTo(0.5);
    }

    @Test
    void getDouble_zeroValue_returnsZero() {
        assertThat(ResponseParser.getDouble("{\"score\":0}", "score", 1.0))
                .isEqualTo(0.0);
    }

    // ── getBoolean ────────────────────────────────────────────────────────────

    @Test
    void getBoolean_trueValue_returnsTrue() {
        assertThat(ResponseParser.getBoolean("{\"fraudulent\":true}", "fraudulent", false))
                .isTrue();
    }

    @Test
    void getBoolean_falseValue_returnsFalse() {
        assertThat(ResponseParser.getBoolean("{\"fraudulent\":false}", "fraudulent", true))
                .isFalse();
    }

    @Test
    void getBoolean_missingKey_returnsDefault() {
        assertThat(ResponseParser.getBoolean("{}", "fraudulent", true))
                .isTrue();
    }
}
