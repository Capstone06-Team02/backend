package capstone2.voisk.service;

import capstone2.voisk.dto.OrderProgressStatusEventResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class CustomerOrderSseService {

    private static final long TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByCartId = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String cartId) {
        if (cartId == null || cartId.isBlank()) {
            throw new IllegalArgumentException("cartId is required.");
        }

        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        emittersByCartId.computeIfAbsent(cartId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(cartId, emitter));
        emitter.onTimeout(() -> remove(cartId, emitter));
        emitter.onError(error -> remove(cartId, emitter));

        sendConnectEvent(cartId, emitter);
        return emitter;
    }

    public void publishStatusChanged(OrderProgressStatusEventResponse statusEvent) {
        if (statusEvent == null || statusEvent.cartId() == null || statusEvent.cartId().isBlank()) {
            return;
        }

        List<SseEmitter> emitters = emittersByCartId.getOrDefault(statusEvent.cartId(), new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("ORDER_STATUS_CHANGED")
                        .id(statusEvent.cartId())
                        .data(statusEvent, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException error) {
                remove(statusEvent.cartId(), emitter);
                log.warn("Failed to send customer order SSE. cartId={}", statusEvent.cartId(), error);
            }
        }
    }

    private void sendConnectEvent(String cartId, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data(Map.of("cartId", cartId)));
        } catch (IOException | IllegalStateException error) {
            remove(cartId, emitter);
        }
    }

    private void remove(String cartId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByCartId.get(cartId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByCartId.remove(cartId, emitters);
        }
    }
}
