package com.faction.clientportal.service;

import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.edition.FeatureNotLicensedException;
import org.springframework.stereotype.Service;

/**
 * The open source answer: refuse.
 *
 * <p>Unreachable in normal operation — the report pipeline checks
 * {@link Feature#ENCRYPTED_PDF} before it gets here and skips the encrypted variant
 * entirely. This exists so that a call site added later without that check fails as a
 * clear 402 rather than silently producing an unprotected file.
 */
@Service
public class UnavailableReportEncryptor implements ReportEncryptor {

    @Override
    public String generatePassword(int length) {
        throw new FeatureNotLicensedException(Feature.ENCRYPTED_PDF);
    }

    @Override
    public byte[] encrypt(byte[] pdfBytes, String password) {
        throw new FeatureNotLicensedException(Feature.ENCRYPTED_PDF);
    }
}
