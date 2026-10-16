package com.codelensai;

import com.codelensai.demo.DemoContent;
import com.codelensai.demo.DemoDataSeeder;
import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.ReviewCommentEntity;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.RepositoryRepository;
import com.codelensai.repository.ReviewCommentRepository;
import com.codelensai.support.CodelensIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The demo profile seeds the storefront repository through the real pipeline, exposes a session-producing
 * demo login (loopback only), and is idempotent across restarts / resets.
 */
@CodelensIntegrationTest
@ActiveProfiles("demo")
class DemoProfileIT {

    @Autowired
    WebApplicationContext context;
    @Autowired
    DemoDataSeeder seeder;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    PullRequestRepository pullRequests;
    @Autowired
    ReviewCommentRepository comments;

    @Test
    void seedsReviewedPullRequestsWithRiskSuggestionsAndCrossFileFindings() {
        seeder.reset();
        long repoId = repositories.findByFullName(DemoContent.REPO).orElseThrow().getId();
        List<PullRequest> prs = pullRequests.findAll().stream()
                .filter(p -> p.getRepoId().equals(repoId))
                .sorted(Comparator.comparing(PullRequest::getPrNumber)).toList();
        assertThat(prs).hasSize(5);
        assertThat(prs).extracting(PullRequest::getRiskLevel).containsExactly("HIGH", "HIGH", "MEDIUM", "LOW", null);
        assertThat(prs.get(4).getStatus()).isEqualTo(ReviewStatus.PENDING);

        List<ReviewCommentEntity> pr1 = comments.findBySessionId(prs.get(0).getLatestSessionId());
        assertThat(pr1).extracting(ReviewCommentEntity::getRuleKey).contains("local.hardcoded-secret", "local.sql-concat");
        assertThat(pr1).allSatisfy(c -> assertThat(c.getCodeSuggestion()).isNotBlank());

        List<ReviewCommentEntity> pr2 = comments.findBySessionId(prs.get(1).getLatestSessionId());
        assertThat(pr2).extracting(ReviewCommentEntity::getRuleKey).contains("local.caller-arity");

        assertThat(comments.findBySessionId(prs.get(3).getLatestSessionId())).isEmpty();

        assertThat(seeder.seed().get("reviewedNow")).as("second seed is a no-op").isEqualTo(0);
    }

    @Test
    void demoLoginCreatesASessionThatScopesTheApi() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/auth/config")).andExpect(jsonPath("$.demoEnabled").value(true));
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/demo-login").param("as", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("demo-bob"))
                .andReturn().getRequest().getSession();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.login").value("demo-bob"));
        mvc.perform(get("/api/prs").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Support discount codes in price calculation')]").isNotEmpty());
        mvc.perform(get("/api/dashboard/stats").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.riskDistribution.HIGH").isNumber())
                .andExpect(jsonPath("$.budget.dailyLimit").isNumber());
        mvc.perform(post("/api/auth/demo-login").param("as", "eve")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/demo-login").with(r -> {
            r.setRemoteAddr("203.0.113.9");
            return r;
        })).andExpect(status().isForbidden());
    }
}
