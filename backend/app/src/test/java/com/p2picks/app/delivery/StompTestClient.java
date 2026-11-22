package com.p2picks.app.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** A STOMP client that connects like the browser: session and CSRF cookies, Origin header, CSRF on CONNECT. */
final class StompTestClient {
    private final StompSession session;
    /** Completes when the server ends the connection, with an ERROR frame or by closing it. */
    final CompletableFuture<String> ended;

    private StompTestClient(StompSession session, CompletableFuture<String> ended) {
        this.session = session;
        this.ended = ended;
    }

    static StompTestClient connect(int port, String origin, List<String> cookies, String csrfToken) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add("Origin", origin);
        handshake.add("Cookie", String.join("; ", cookies));
        StompHeaders connect = new StompHeaders();
        if (csrfToken != null) connect.add("X-CSRF-TOKEN", csrfToken);
        CompletableFuture<String> ended = new CompletableFuture<>();
        StompSession session = client.connectAsync("ws://127.0.0.1:" + port + "/ws", handshake, connect,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        ended.complete("error: " + headers.getFirst("message"));
                    }

                    @Override
                    public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                            byte[] payload, Throwable exception) {
                        ended.complete("exception: " + exception.getMessage());
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        ended.complete("closed: " + exception.getClass().getSimpleName());
                    }
                }).get(10, TimeUnit.SECONDS);
        return new StompTestClient(session, ended);
    }

    BlockingQueue<JsonNode> subscribe(String destination) {
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((JsonNode) payload);
            }
        });
        return messages;
    }

    void send(String destination, String body) {
        session.send(destination, body);
    }

    void disconnect() {
        if (session.isConnected()) session.disconnect();
    }
}
