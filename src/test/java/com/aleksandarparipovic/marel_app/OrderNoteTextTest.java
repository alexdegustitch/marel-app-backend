package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.order_note.NoteText;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a rich-text note document flattens to — the plain text the wall searches,
 * sorts and previews on. The rule the SQL and the screen both trust: whatever
 * text is in the document comes out, joined by single spaces, and a document
 * with no text comes out empty (which is what refuses an empty note).
 */
class OrderNoteTextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** The document as the HTTP layer hands it to the service — plain lists and maps. */
    private Object json(String raw) {
        try {
            return mapper.readValue(raw, Object.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("A paragraph's inline text comes out, runs of space collapsed")
    void flattensParagraph() {
        Object doc = json("""
                [{"type":"paragraph","content":[
                    {"type":"text","text":"Materijal je stigao,"},
                    {"type":"text","text":" čeka boju."}
                ]}]
                """);
        assertThat(NoteText.flatten(doc)).isEqualTo("Materijal je stigao, čeka boju.");
    }

    @Test
    @DisplayName("Nested list items and headings all contribute their text")
    void flattensNestedBlocks() {
        Object doc = json("""
                {"type":"doc","content":[
                  {"type":"heading","content":[{"type":"text","text":"Rokovi"}]},
                  {"type":"bulletList","content":[
                    {"type":"listItem","content":[
                      {"type":"paragraph","content":[{"type":"text","text":"prva isporuka"}]},
                      {"type":"bulletList","content":[
                        {"type":"listItem","content":[
                          {"type":"paragraph","content":[{"type":"text","text":"petak"}]}
                        ]}
                      ]}
                    ]}
                  ]}
                ]}
                """);
        assertThat(NoteText.flatten(doc)).isEqualTo("Rokovi prva isporuka petak");
    }

    @Test
    @DisplayName("A table's cells are reached through the same content nesting")
    void flattensTable() {
        Object doc = json("""
                {"type":"doc","content":[
                  {"type":"table","content":[
                    {"type":"tableRow","content":[
                      {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"kom"}]}]},
                      {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"120"}]}]}
                    ]}
                  ]}
                ]}
                """);
        assertThat(NoteText.flatten(doc)).isEqualTo("kom 120");
    }

    @Test
    @DisplayName("An empty or whitespace-only document flattens to empty — this is what rejects an empty note")
    void flattensEmpty() {
        assertThat(NoteText.flatten(json("[]"))).isEmpty();
        assertThat(NoteText.flatten(json("null"))).isEmpty();
        assertThat(NoteText.flatten(json("""
                [{"type":"paragraph","content":[{"type":"text","text":"   "}]}]
                """))).isEmpty();
        assertThat(NoteText.flatten(null)).isEmpty();
    }
}
