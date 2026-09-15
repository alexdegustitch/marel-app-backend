package com.aleksandarparipovic.marel_app.order_note;

/**
 * Which kind of order a note hangs off. The note table points at either a
 * production order or a sample order through its own nullable foreign key; this
 * says which of the two a given request is about, so one service and one
 * controller serve both walls without either forgetting which column to write.
 */
public enum OrderNoteParentType {
    PRODUCTION,
    SAMPLE
}
