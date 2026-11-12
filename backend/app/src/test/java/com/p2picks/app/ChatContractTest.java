package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import java.io.IOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ChatContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER = "95586d98-8dae-455a-b642-51bd5c8cb8f8";
    private static JsonSchema chatSchema;

    @BeforeAll
    static void loadContract() throws IOException {
        chatSchema = OpenApiSchemas.load("ChatMessage");
    }

    @Test
    void messageKindsRequireTheirOwnContentAndCannotExposeBetPicks() {
        ObjectNode text = message("text");
        text.put("authorId", USER);
        text.put("text", "Hello");
        assertThat(chatSchema.validate(text)).isEmpty();
        text.put("gifId", "gif");
        assertThat(chatSchema.validate(text)).isNotEmpty();
        text.remove("gifId");
        text.remove("authorId");
        assertThat(chatSchema.validate(text)).isNotEmpty();

        ObjectNode bet = message("bet");
        bet.put("authorId", USER);
        bet.put("betId", USER);
        assertThat(chatSchema.validate(bet)).isEmpty();
        bet.putArray("picks").add(USER);
        assertThat(chatSchema.validate(bet)).isNotEmpty();

        ObjectNode gif = message("gif");
        gif.put("authorId", USER);
        gif.put("gifId", "gif");
        assertThat(chatSchema.validate(gif)).isEmpty();
        ObjectNode system = message("system");
        system.put("text", "Picks locked");
        assertThat(chatSchema.validate(system)).isEmpty();
        system.put("authorId", USER);
        assertThat(chatSchema.validate(system)).isNotEmpty();
    }

    @Test
    void settlementSummaryContainsStaticAmountsWithoutReceiptStatus() {
        ObjectNode message = message("settlement");
        message.put("settlementId", USER);
        ObjectNode summary = message.putObject("settlementSummary");
        summary.put("hostId", USER);
        ObjectNode line = summary.putArray("lines").addObject();
        line.put("payerId", USER);
        line.put("payeeId", "1f006a5f-fd02-41ff-aee8-52b87d11d53e");
        line.put("amountCents", 100);
        assertThat(chatSchema.validate(message)).isEmpty();
        line.put("status", "sent");
        assertThat(chatSchema.validate(message)).isNotEmpty();
        line.remove("status");
        message.remove("settlementSummary");
        assertThat(chatSchema.validate(message)).isNotEmpty();
    }

    @Test
    void unreadStateRequiresAnIdExactlyWhenMessagesAreUnread() throws IOException {
        JsonSchema unreadSchema = OpenApiSchemas.load("ChatUnreadState");
        ObjectNode unread = JSON.createObjectNode();
        unread.put("unreadCount", 0);
        unread.putNull("firstUnreadMessageId");
        assertThat(unreadSchema.validate(unread)).isEmpty();
        unread.put("unreadCount", 1);
        assertThat(unreadSchema.validate(unread)).isNotEmpty();
        unread.put("firstUnreadMessageId", USER);
        assertThat(unreadSchema.validate(unread)).isEmpty();
        unread.put("unreadCount", 0);
        assertThat(unreadSchema.validate(unread)).isNotEmpty();
    }

    private static ObjectNode message(String kind) {
        ObjectNode message = JSON.createObjectNode();
        message.put("id", "52a73783-b3cf-438a-8174-ac235c54eb6e");
        message.put("tableId", "1f006a5f-fd02-41ff-aee8-52b87d11d53e");
        message.put("kind", kind);
        message.put("createdAt", "2026-11-11T12:00:00Z");
        return message;
    }
}
