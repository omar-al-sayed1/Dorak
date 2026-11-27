package app.clinicreminders;

import java.time.Instant;

import app.clinicreminders.whatsapp.FakeWhatsAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base class for Testcontainers tests. Every subclass shares one Spring context and one Postgres
 * container. Tests commit for real (no rollback), so the database is truncated before each test;
 * that keeps concurrency tests (M3, M5) honest. WhatsApp uses the fake client, and webhook
 * deliveries are processed only when a test calls {@code WebhookInboxProcessor.drain()}, reminders
 * only when it calls {@code ReminderSender.sendDue()}.
 */
@SpringBootTest(properties = {
        "whatsapp.client=fake",
        "whish.client=fake",
        "app.base-url=https://reminders.test",
        "whatsapp.app-secret=test-app-secret",
        "whatsapp.verify-token=test-verify-token",
        "whatsapp.token-encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "whatsapp.inbox.async=false",
        "reminders.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
public abstract class IntegrationTest {

    protected static final Instant DEFAULT_NOW = Instant.parse("2026-10-14T09:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected TestData data;

    @Autowired
    protected FakeWhatsAppClient whatsApp;

    @Autowired
    protected RecordingSleeper sleeper;

    @Autowired
    protected RecordingOperatorAlerts alerts;

    @BeforeEach
    void resetState() {
        jdbc.execute("truncate table clinic, message_rate, webhook_inbox, deleted_clinic restart identity cascade");
        jdbc.execute("update billing_plan set monthly_fee_usd = 0, discount_6_percent = 0, discount_12_percent = 0, "
                + "grace_days = 14, message_costs = 'INCLUDED'");
        clock.setInstant(DEFAULT_NOW);
        whatsApp.reset();
        sleeper.reset();
        alerts.reset();
    }
}
