package com.codelensai;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.Repository;
import com.codelensai.model.entity.User;
import com.codelensai.service.ReviewService;
import com.codelensai.support.RequireDockerExtension;
import com.codelensai.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real server + real SockJS/STOMP client: CONNECT needs an authenticated session, SUBSCRIBE needs repository
 * membership, the live review stream and the replay batch carry session-scoped sequence numbers, and
 * presence snapshots fan out through Redis.
 */
@ExtendWith(RequireDockerExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"codelens.indexing.enabled=false", "codelens.presence.ttl-seconds=30"})
class StompRealtimeIT {

    @LocalServerPort
    int port;
    @Autowired
    TestFixtures fixtures;
    @Autowired
    ReviewService reviewService;

    WebSocketStompClient stomp;
    User alice;
    User mallory;
    PullRequest pr;

    @BeforeEach
    void setUp() {
        stomp = new WebSocketStompClient(new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        stomp.setMessageConverter(new SimpleMessageConverter());
        alice = fixtures.user("ws-alice");
        mallory = fixtures.user("ws-mallory");
        Repository repo = fixtures.repo(alice);
        pr = fixtures.pr(repo, """
                --- a/Dao.java
                +++ b/Dao.java
                @@ -1,1 +1,2 @@
                 class Dao {
                +    String sql = "SELECT * FROM t WHERE x = '" + q + "'";
                """);
    }

    @AfterEach
    void tearDown() {
        stomp.stop();
    }

    @Test
    void anonymousConnectIsRejected() {
        assertThatThrownBy(() -> connect(null).get(10, TimeUnit.SECONDS)).hasRootCauseInstanceOf(Exception.class);
    }

    @Test
    void nonMemberCannotSubscribeToAnotherUsersPr() throws Exception {
        Session s = new Session();
        StompSession session = connect(login(mallory), s).get(10, TimeUnit.SECONDS);
        session.subscribe("/topic/pr/" + pr.getId() + "/review", s.collector());
        String error = s.errors.poll(10, TimeUnit.SECONDS);
        assertThat(error).as("ERROR frame for a forbidden destination").isNotNull();
    }

    @Test
    void memberGetsLiveTokensReplayAndPresence() throws Exception {
        Session s = new Session();
        StompSession session = connect(login(alice), s).get(10, TimeUnit.SECONDS);
        BlockingQueue<String> review = new LinkedBlockingQueue<>();
        BlockingQueue<String> status = new LinkedBlockingQueue<>();
        BlockingQueue<String> presence = new LinkedBlockingQueue<>();
        session.subscribe("/topic/pr/" + pr.getId() + "/review", queueing(review));
        session.subscribe("/topic/pr/" + pr.getId() + "/status", queueing(status));
        session.subscribe("/topic/pr/" + pr.getId() + "/presence", queueing(presence));
        Thread.sleep(500); // let the subscriptions register before publishing

        reviewService.processReview(pr.getId(), pr.getHeadSha());

        String firstToken = review.poll(15, TimeUnit.SECONDS);
        assertThat(firstToken).contains("\"type\":\"token\"").contains("\"seq\":1").contains("\"sessionId\"");
        String completed = null;
        for (int i = 0; i < 5 && completed == null; i++) {
            String st = status.poll(15, TimeUnit.SECONDS);
            if (st != null && st.contains("COMPLETED")) {
                completed = st;
            }
        }
        assertThat(completed).contains("\"sessionId\"");

        StompHeaders replay = new StompHeaders();
        replay.setDestination("/app/pr/" + pr.getId() + "/replay");
        replay.add("after-seq", "1");
        replay.add("session-id", "0"); // stale/unknown session -> full replay of the current one
        BlockingQueue<String> batch = new LinkedBlockingQueue<>();
        session.subscribe(replay, queueing(batch));
        String body = batch.poll(10, TimeUnit.SECONDS);
        assertThat(body).contains("\"status\":\"COMPLETED\"").contains("\"seq\":1");

        StompHeaders send = new StompHeaders();
        send.setDestination("/app/pr/" + pr.getId() + "/presence");
        send.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
        session.send(send, "{\"type\":\"FOCUS\",\"file\":\"Dao.java\",\"line\":2}".getBytes(StandardCharsets.UTF_8));
        String snapshot = presence.poll(10, TimeUnit.SECONDS);
        assertThat(snapshot).contains(alice.getUsername()).contains("\"line\":2");
    }

    // --- helpers -----------------------------------------------------------------------------

    private String login(User user) throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/auth/test-login?userId=" + user.getId()))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        return res.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("JSESSIONID")).map(c -> c.split(";", 2)[0]).findFirst().orElseThrow();
    }

    private CompletableFuture<StompSession> connect(String cookie) {
        return connect(cookie, new Session());
    }

    private CompletableFuture<StompSession> connect(String cookie, Session handler) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:5173");
        if (cookie != null) {
            headers.add("Cookie", cookie);
        }
        return stomp.connectAsync("http://localhost:" + port + "/ws", headers, handler);
    }

    private static StompFrameHandler queueing(BlockingQueue<String> q) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                q.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        };
    }

    static class Session extends StompSessionHandlerAdapter {
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.add("ERROR " + headers.getFirst("message"));
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errors.add("TRANSPORT " + exception.getMessage());
        }

        StompFrameHandler collector() {
            return queueing(new LinkedBlockingQueue<>());
        }
    }
}
