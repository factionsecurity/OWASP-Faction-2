package com.faction.clientportal.repository;

import com.faction.clientportal.model.EmailReplyToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EmailReplyTokenRepository extends JpaRepository<EmailReplyToken, String> {

    /** Fallback lookup when the provider stripped sub-addressing from the Reply-To. */
    Optional<EmailReplyToken> findByOutboundMessageId(String outboundMessageId);

    List<EmailReplyToken> findByExpiresAtBefore(LocalDateTime cutoff);
}
