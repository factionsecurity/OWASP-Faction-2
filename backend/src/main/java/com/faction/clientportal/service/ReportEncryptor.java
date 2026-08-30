package com.faction.clientportal.service;

/**
 * Produces the password-protected PDF variant of a report.
 *
 * <p>The plain DOCX and PDF are open source; only this last step is paid, so it is the
 * one thing the report pipeline reaches for through an interface.
 *
 * <p>Every call site is already behind an {@code ENCRYPTED_PDF} check, so the open source
 * implementation is never reached in practice — it refuses loudly rather than returning
 * something plausible, because a silently unencrypted "encrypted" report is far worse
 * than an error.
 */
public interface ReportEncryptor {

    /** A random password of the given length, for a report that does not have one yet. */
    String generatePassword(int length);

    /** The same PDF, password-protected. */
    byte[] encrypt(byte[] pdfBytes, String password) throws java.io.IOException;
}
