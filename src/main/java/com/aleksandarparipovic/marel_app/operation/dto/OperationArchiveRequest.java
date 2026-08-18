package com.aleksandarparipovic.marel_app.operation.dto;

/** Archiving asks for the actor's password AND the reason it is being archived. */
public record OperationArchiveRequest(String password, String reason) {
}
