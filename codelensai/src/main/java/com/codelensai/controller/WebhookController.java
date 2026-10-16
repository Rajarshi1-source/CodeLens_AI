package com.codelensai.controller;

import com.codelensai.repository.RepositoryRepository;
import com.codelensai.service.IdempotencyService;
import com.codelensai.service.WebhookService;
import com.codelensai.util.WebhookSignatureValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Idempotent GitHub webhook receiver. Verify authenticity FIRST, then dedup, then enqueue and ACK fast
 * (202). The review is NEVER run inline — it is processed asynchronously off the Redis Stream.
 *
 * <p>Hooks created by "connect repository" are signed with that repository's own secret, so the payload's
 * {@code repository.full_name} picks the per-repo secret to verify against; the global
 * {@code github.webhook-secret} covers manually configured hooks. Nothing from the body is trusted until
 * one of them verifies.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookSignatureValidator signatureValidator;
    private final IdempotencyService idempotency;
    private final WebhookService webhookService;
    private final RepositoryRepository repositories;

    public WebhookController(WebhookSignatureValidator signatureValidator,
                             IdempotencyService idempotency,
                             WebhookService webhookService,
                             RepositoryRepository repositories) {
        this.signatureValidator = signatureValidator;
        this.idempotency = idempotency;
        this.webhookService = webhookService;
        this.repositories = repositories;
    }

    @PostMapping("/github")
    public ResponseEntity<String> handleGithub(
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String rawBody) {

        if (!verified(rawBody, signature)) {
            log.warn("Rejected webhook: invalid signature (delivery={})", deliveryId);
            return ResponseEntity.status(401).body("invalid signature");
        }
        if (!"pull_request".equals(eventType)) {
            return ResponseEntity.ok("ignored event: " + eventType);
        }
        if (deliveryId == null) {
            return ResponseEntity.badRequest().body("missing X-GitHub-Delivery");
        }
        if (!idempotency.isFirstDelivery(deliveryId)) {
            log.info("Duplicate webhook ignored (delivery={})", deliveryId);
            return ResponseEntity.ok("duplicate ignored");
        }

        webhookService.parseAndEnqueue(rawBody);
        return ResponseEntity.accepted().body("queued");
    }

    private boolean verified(String rawBody, String signature) {
        boolean perRepo = webhookService.repositoryOf(rawBody)
                .flatMap(repositories::findByFullName)
                .map(r -> r.getWebhookSecret())
                .filter(secret -> secret != null && !secret.isBlank())
                .map(secret -> signatureValidator.isValid(rawBody, signature, secret))
                .orElse(false);
        return perRepo || signatureValidator.isValid(rawBody, signature);
    }
}
