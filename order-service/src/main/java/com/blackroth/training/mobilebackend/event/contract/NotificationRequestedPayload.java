package com.blackroth.training.mobilebackend.event.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationRequestedPayload(
        Long recipientUserId,
        String recipientChannel,
        String templateCode,
        String referenceId,
        String subject,
        String maskedContact
) {
    public void validate() {
        if (recipientUserId == null || recipientUserId <= 0) {
            throw new IllegalArgumentException("recipientUserId must be positive");
        }
        if (recipientChannel == null || recipientChannel.isBlank()) {
            throw new IllegalArgumentException("recipientChannel is required and cannot be blank");
        }
        if (templateCode == null || templateCode.isBlank()) {
            throw new IllegalArgumentException("templateCode is required and cannot be blank");
        }
        if (referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("referenceId is required and cannot be blank");
        }
    }
}
