package com.p2picks.app.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.common.IdempotencyGuard;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

class FeedbackIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private IdempotencyGuard idempotency;

    @Test
    void feedbackSubmissionIsValidatedPrivateAndRetrySafe() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'feedbackwrite', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'feedbackother', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        Cookie session = loginAs("feedbackwrite");
        Cookie otherSession = loginAs("feedbackother");
        Csrf csrf = csrf();
        String first = feedbackBody("gif", "\uFEFF\nFive ideas\u00A0", " \u00A0HTTPS://example.com/gif\uFEFF ");
        mvc.perform(post("/feedback").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/feedback").cookie(session).header("Origin", "http://127.0.0.1:8080")
                        .header("Idempotency-Key", "feedback-first").contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isForbidden());
        mvc.perform(feedbackPost(session, csrf, "bad", feedbackBody("gif", "a".repeat(9), null)))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", feedbackBody("gif", "a".repeat(1001), null)))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", feedbackBody("gif", "valid text!", "https://")))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", feedbackBody("gif", "valid text!", "https://x/\uFEFFy")))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", feedbackBody("gif", "valid\u0000text!", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", "{\"kind\":\"bug\",\"text\":\"valid idea \\uD800\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", "{\"kind\":\"bug\",\"text\":\"valid text!\",\"link\":null}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                .isZero();

        MvcResult saved = mvc.perform(feedbackPost(session, csrf, "feedback-first", first))
                .andExpect(status().isCreated()).andReturn();
        assertThat(saved.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(saved.getResponse().getContentAsString()).isEmpty();
        mvc.perform(feedbackPost(session, csrf, "feedback-first",
                feedbackBody("gif", "Five ideas", "HTTPS://example.com/gif"))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT text FROM feedback WHERE user_id = ?", String.class, user))
                .isEqualTo("Five ideas");
        assertThat(jdbc.queryForObject("SELECT link FROM feedback WHERE user_id = ?", String.class, user))
                .isEqualTo("HTTPS://example.com/gif");
        assertThat(jdbc.queryForObject("SELECT response_status FROM http_idempotency WHERE user_id = ?",
                Integer.class, user)).isEqualTo(201);
        var firstSubmission = new FeedbackService.Submission("gif", "Five ideas", "HTTPS://example.com/gif");
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(status -> idempotency.claim(user, "feedback-first",
                "feedback.submit.v1", firstSubmission.canonical())))
                .isInstanceOf(IllegalStateException.class);
        mvc.perform(feedbackPost(session, csrf, "feedback-first", feedbackBody("gif", "other idea!", null)))
                .andExpect(status().isConflict());
        mvc.perform(invitePreferencesPut(session, csrf, "feedback-first", "{\"friends\":\"open\",\"following\":\"request\"}"))
                .andExpect(status().isConflict());
        mvc.perform(feedbackPost(otherSession, csrf, "feedback-first", feedbackBody("bug", "other idea!", null)))
                .andExpect(status().isCreated());
        mvc.perform(feedbackPost(session, csrf, "emoji", feedbackBody("other", "\uD83D\uDE00".repeat(5), " ")))
                .andExpect(status().isCreated());
        mvc.perform(feedbackPost(session, csrf, "max-emoji", feedbackBody("other", "\uD83D\uDE00".repeat(500), null)))
                .andExpect(status().isCreated());
        mvc.perform(feedbackPost(session, csrf, "too-long-emoji",
                feedbackBody("other", "\uD83D\uDE00".repeat(501), null))).andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "non-ascii-url",
                feedbackBody("other", "valid text!", "https://x/\u0085y"))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback WHERE user_id = ?", Integer.class, user))
                .isEqualTo(4);

        Jwt validated = decoder.decode(session.getValue());
        assertThatThrownBy(() -> feedbackService.submit(validated, "db-invalid",
                new FeedbackService.Submission("bug", "a", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                .isEqualTo(4);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(feedbackService.submit(validated, "feedback-first",
                new FeedbackService.Submission("gif", "Five ideas", "HTTPS://example.com/gif")))
                .isEqualTo(FeedbackService.Outcome.UNAUTHORIZED);
        mvc.perform(feedbackPost(session, csrf, "feedback-first", first)).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentFeedbackRetriesCreateOneEntry() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'feedbackrace', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt jwt = decoder.decode(loginAs("feedbackrace").getValue());
        var submission = new FeedbackService.Submission("bug", "A test issue", null);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return feedbackService.submit(jwt, "same-key", submission);
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return feedbackService.submit(jwt, "same-key", submission);
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(FeedbackService.Outcome.CREATED, FeedbackService.Outcome.REPLAYED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String feedbackBody(String kind, String text, String link) throws Exception {
        var body = json.createObjectNode().put("kind", kind).put("text", text);
        if (link != null) body.put("link", link);
        return json.writeValueAsString(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder feedbackPost(
            Cookie session, Csrf csrf, String key, String body) {
        return post("/feedback").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
