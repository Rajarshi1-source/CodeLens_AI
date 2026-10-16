package com.codelensai.websocket;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

/**
 * Resumable stream (master plan §9.5). A client subscribes to {@code /app/pr/{id}/replay} with headers
 * {@code after-seq} and {@code session-id}; the reply (sent once, to that subscription only) is the batch of
 * buffered messages it missed. It must keep the live {@code /topic} messages that arrive meanwhile in a
 * buffer and apply them after the batch — a SUBSCRIBE to a {@code /topic} goes straight to the broker, so
 * replay has to be this separate {@code /app} request. Authorization is enforced by
 * {@code StompAuthChannelInterceptor} on SUBSCRIBE.
 */
@Controller
public class ReviewReplayController {

    private final ReviewReplayService replay;

    public ReviewReplayController(ReviewReplayService replay) {
        this.replay = replay;
    }

    @SubscribeMapping("/pr/{prId}/replay")
    public ReviewReplayService.ReplayBatch replay(@DestinationVariable long prId,
                                                  @Header(name = "after-seq", defaultValue = "0") long afterSeq,
                                                  @Header(name = "session-id", defaultValue = "0") long sessionId) {
        return replay.replay(prId, afterSeq, sessionId);
    }
}
