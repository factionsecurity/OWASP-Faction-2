package com.faction.clientportal.exception;

/**
 * A deliberate refusal: the request is well-formed, but the thing it acts on is not in a state
 * that allows it — an assessment locked for peer review, a review being completed out of order,
 * a report asked for before its template has a file.
 *
 * <p>Extends {@link IllegalStateException} on purpose. That is what these all used to be, and
 * several call sites still catch that type to degrade gracefully; widening rather than replacing
 * keeps every one of them working while giving {@code GlobalExceptionHandler} something specific
 * enough to map. An {@code IllegalStateException} that is not one of these stays a 500, which is
 * right — "SHA-256 unavailable" is a broken JVM, not a business rule.
 *
 * <p>Answered as 409 Conflict, with the message intact. The generic handler replaces the message
 * with "An unexpected error occurred", and these messages are written to be read: several name
 * the exact screen the operator should go and fix.
 */
public class BusinessRuleException extends IllegalStateException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
