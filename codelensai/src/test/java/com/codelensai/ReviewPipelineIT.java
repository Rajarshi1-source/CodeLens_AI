package com.codelensai;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.Repository;
import com.codelensai.model.entity.ReviewCommentEntity;
import com.codelensai.model.entity.ReviewSession;
import com.codelensai.model.entity.User;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.ReviewCommentRepository;
import com.codelensai.repository.ReviewSessionRepository;
import com.codelensai.service.ReviewService;
import com.codelensai.service.index.RepoIndexService;
import com.codelensai.support.CodelensIntegrationTest;
import com.codelensai.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static com.codelensai.support.TestFixtures.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end review pipeline against real Postgres (pgvector) + Redis with the offline provider:
 * G1 retrieval from the code index finds a broken caller, G3 risk is persisted, findings stream into the
 * replay buffer, feedback survives a re-review, membership scopes every endpoint, and apply-suggestion is
 * idempotent.
 */
@CodelensIntegrationTest
class ReviewPipelineIT {

    static final Map<String, String> REPO_FILES = Map.of(
            "src/main/java/shop/payment/PriceCalculator.java", """
                    package shop.payment;

                    public class PriceCalculator {
                        public java.math.BigDecimal calculateTotal(java.util.List<LineItem> items) {
                            return items.stream().map(LineItem::price).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                        }
                    }
                    """,
            "src/main/java/shop/checkout/CartController.java", """
                    package shop.checkout;

                    public class CartController {
                        private final PriceCalculator priceCalculator = new PriceCalculator();

                        public Receipt checkout(Cart cart) {
                            java.math.BigDecimal total = priceCalculator.calculateTotal(cart.items());
                            return new Receipt(total);
                        }
                    }
                    """);

    static final String DIFF = """
            diff --git a/src/main/java/shop/payment/PriceCalculator.java b/src/main/java/shop/payment/PriceCalculator.java
            --- a/src/main/java/shop/payment/PriceCalculator.java
            +++ b/src/main/java/shop/payment/PriceCalculator.java
            @@ -2,6 +2,7 @@ package shop.payment;

             public class PriceCalculator {
            -    public java.math.BigDecimal calculateTotal(java.util.List<LineItem> items) {
            +    public java.math.BigDecimal calculateTotal(java.util.List<LineItem> items, String discountCode) {
            +        String apiKey = "sk-live-9f8a7b6c5d4e3f2a1b";
                     return items.stream().map(LineItem::price).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                 }
             }
            """;

    @Autowired
    WebApplicationContext context;
    @Autowired
    TestFixtures fixtures;
    @Autowired
    ReviewService reviewService;
    @Autowired
    RepoIndexService indexService;
    @Autowired
    ReviewSessionRepository sessions;
    @Autowired
    ReviewCommentRepository comments;
    @Autowired
    PullRequestRepository pullRequests;

    MockMvc mvc;
    User alice;
    User mallory;
    Repository repo;
    PullRequest pr;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = fixtures.user("alice");
        mallory = fixtures.user("mallory");
        repo = fixtures.repo(alice);
        indexService.indexFromContents(repo.getId(), "main", REPO_FILES);
        pr = fixtures.pr(repo, DIFF);
    }

    @Test
    void reviewFindsTheBrokenCallerScoresRiskAndPersistsEverything() throws Exception {
        reviewService.processReview(pr.getId(), pr.getHeadSha());

        ReviewSession session = sessions.findByPrIdAndHeadSha(pr.getId(), pr.getHeadSha()).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(session.getRiskLevel()).isEqualTo("HIGH");
        assertThat(session.getInputTokens()).isPositive();
        assertThat(session.getMetadata()).containsKey("servedBy");
        @SuppressWarnings("unchecked")
        Map<String, Object> retrieval = (Map<String, Object>) session.getMetadata().get("retrieval");
        assertThat((Integer) retrieval.get("snippets")).isPositive();

        List<ReviewCommentEntity> rows = comments.findBySessionId(session.getId());
        assertThat(rows).extracting(ReviewCommentEntity::getRuleKey)
                .contains("local.caller-arity", "local.hardcoded-secret");
        assertThat(rows).allSatisfy(c -> assertThat(c.getFingerprint()).hasSize(32));
        assertThat(rows).filteredOn(c -> "local.hardcoded-secret".equals(c.getRuleKey()))
                .first().satisfies(c -> assertThat(c.getCodeSuggestion()).contains("System.getenv"));

        PullRequest after = pullRequests.findById(pr.getId()).orElseThrow();
        assertThat(after.getRiskLevel()).isEqualTo("HIGH");
        assertThat(after.getLatestSessionId()).isEqualTo(session.getId());

        mvc.perform(get("/api/prs/{id}/summary", pr.getId()).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.riskReasons[0].code").exists())
                .andExpect(jsonPath("$.summary").isNotEmpty());

        mvc.perform(get("/api/prs/{id}/stream", pr.getId()).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(session.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].type").value("token"));

        mvc.perform(get("/api/prs/{id}/diff", pr.getId()).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diff").value(DIFF));
    }

    @Test
    void nonMembersGet404EverywhereAndListsAreScoped() throws Exception {
        reviewService.processReview(pr.getId(), pr.getHeadSha());
        long commentId = comments.findBySessionId(pullRequests.findById(pr.getId()).orElseThrow().getLatestSessionId())
                .get(0).getId();
        for (String url : List.of("/api/prs/{id}", "/api/prs/{id}/comments", "/api/prs/{id}/diff",
                "/api/prs/{id}/summary", "/api/prs/{id}/stream")) {
            mvc.perform(get(url, pr.getId()).with(as(mallory))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/prs/{id}/re-review", pr.getId()).with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(put("/api/comments/{id}/feedback", commentId).with(as(mallory))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote\":-1}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/prs").with(as(mallory)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + pr.getId() + ")]").isEmpty());
        mvc.perform(get("/api/prs").with(as(alice)))
                .andExpect(jsonPath("$[?(@.id == " + pr.getId() + ")]").isNotEmpty());
        mvc.perform(get("/api/prs/{id}", pr.getId())).andExpect(status().isUnauthorized());
    }

    @Test
    void feedbackIsUpsertedAndSurvivesASameShaReReview() throws Exception {
        reviewService.processReview(pr.getId(), pr.getHeadSha());
        ReviewCommentEntity secret = latestComments().stream()
                .filter(c -> "local.hardcoded-secret".equals(c.getRuleKey())).findFirst().orElseThrow();

        mvc.perform(put("/api/comments/{id}/feedback", secret.getId()).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.up").value(1));
        mvc.perform(put("/api/comments/{id}/feedback", secret.getId()).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote\":-1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.up").value(0))
                .andExpect(jsonPath("$.down").value(1))
                .andExpect(jsonPath("$.myVote").value(-1));
        mvc.perform(put("/api/comments/{id}/feedback", secret.getId()).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote\":7}"))
                .andExpect(status().isBadRequest());

        reviewService.processReview(pr.getId(), pr.getHeadSha()); // same SHA: comments replaced

        ReviewCommentEntity again = latestComments().stream()
                .filter(c -> c.getFingerprint().equals(secret.getFingerprint())).findFirst().orElseThrow();
        assertThat(again.getId()).isNotEqualTo(secret.getId());
        mvc.perform(get("/api/prs/{id}/comments", pr.getId()).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + again.getId() + ")].feedback.down").value(1))
                .andExpect(jsonPath("$[?(@.id == " + again.getId() + ")].feedback.myVote").value(-1));
    }

    @Test
    void applySuggestionIsIdempotentAndRejectsStaleOrMissingSuggestions() throws Exception {
        reviewService.processReview(pr.getId(), pr.getHeadSha());
        List<ReviewCommentEntity> rows = latestComments();
        ReviewCommentEntity withSuggestion = rows.stream().filter(c -> c.getCodeSuggestion() != null).findFirst().orElseThrow();
        ReviewCommentEntity without = rows.stream().filter(c -> c.getCodeSuggestion() == null).findFirst().orElseThrow();

        mvc.perform(post("/api/comments/{id}/apply", without.getId()).with(as(alice)))
                .andExpect(status().isUnprocessableEntity());

        // The fixture user has no GitHub token: the real client refuses, the attempt is recorded as FAILED (retryable).
        mvc.perform(post("/api/comments/{id}/apply", withSuggestion.getId()).with(as(alice)))
                .andExpect(status().isBadGateway());
        assertThat(comments.findById(withSuggestion.getId()).orElseThrow().getMetadata())
                .extractingByKey("apply").asString().contains("FAILED");

        PullRequest moved = pullRequests.findById(pr.getId()).orElseThrow();
        moved.setHeadSha("0000000000000000000000000000000000000000");
        pullRequests.saveAndFlush(moved);
        mvc.perform(post("/api/comments/{id}/apply", withSuggestion.getId()).with(as(alice)))
                .andExpect(status().isConflict());
    }

    @Test
    void internalEvalEndpointShowsWhatRetrievalAdds() throws Exception {
        String body = """
                {"diff": %s, "contextFiles": {"src/main/java/shop/checkout/CartController.java": %s}, "retrieval": %s}""";
        String diff = quote(DIFF);
        String caller = quote(REPO_FILES.get("src/main/java/shop/checkout/CartController.java"));
        mvc.perform(post("/api/internal/review-diff").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(diff, caller, "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments[?(@.ruleKey == 'local.caller-arity')]").isNotEmpty())
                .andExpect(jsonPath("$.usage.inputTokens").isNumber())
                .andExpect(jsonPath("$.risk.level").value("HIGH"));
        mvc.perform(post("/api/internal/review-diff").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(diff, caller, "false")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments[?(@.ruleKey == 'local.caller-arity')]").isEmpty());
    }

    private List<ReviewCommentEntity> latestComments() {
        Long sid = pullRequests.findById(pr.getId()).orElseThrow().getLatestSessionId();
        return comments.findBySessionId(sid);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
