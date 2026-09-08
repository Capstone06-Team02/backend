package capstone2.voisk.service;

import capstone2.voisk.dto.OwnerOrderEventResponse;
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
public class OwnerOrderSseService {

    private static final long TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emittersByStoreId = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long storeId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required.");
        }

        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        emittersByStoreId.computeIfAbsent(storeId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(storeId, emitter));
        emitter.onTimeout(() -> remove(storeId, emitter));
        emitter.onError(error -> remove(storeId, emitter));

        sendConnectEvent(storeId, emitter);
        return emitter;
    }

    public void publishOrderCreated(OwnerOrderEventResponse orderEvent) {
        if (orderEvent == null || orderEvent.storeId() == null) {
            return;
        }

        List<SseEmitter> emitters = emittersByStoreId.getOrDefault(orderEvent.storeId(), new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("ORDER_CREATED")
                        .id(orderEvent.cartId())
                        .data(orderEvent, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException error) {
                remove(orderEvent.storeId(), emitter);
                log.warn("Failed to send owner order SSE. storeId={}, cartId={}",
                        orderEvent.storeId(), orderEvent.cartId(), error);
            }
        }
    }

    public void publishStatusChanged(OrderProgressStatusEventResponse statusEvent) {
        if (statusEvent == null || statusEvent.storeId() == null) {
            return;
        }

        List<SseEmitter> emitters = emittersByStoreId.getOrDefault(statusEvent.storeId(), new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("ORDER_STATUS_CHANGED")
                        .id(statusEvent.cartId())
                        .data(statusEvent, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException error) {
                remove(statusEvent.storeId(), emitter);
                log.warn("Failed to send owner order status SSE. storeId={}, cartId={}",
                        statusEvent.storeId(), statusEvent.cartId(), error);
            }
        }
    }

    private void sendConnectEvent(Long storeId, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data(Map.of("storeId", storeId)));
        } catch (IOException | IllegalStateException error) {
            remove(storeId, emitter);
        }
    }

    private void remove(Long storeId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByStoreId.get(storeId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByStoreId.remove(storeId, emitters);
        }
    }
}
