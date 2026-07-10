package com.chargedserver.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Low-overhead event executor for internal and API events. Dispatch is a
 * single ConcurrentHashMap lookup plus iteration over a CopyOnWriteArrayList —
 * no reflection, no HandlerList priority sorting, no allocation per post.
 */
public class ChargedEventBus {

    private final Map<Class<?>, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> void subscribe(Class<T> eventType, Consumer<T> handler) {
        listeners.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>())
                .add((Consumer<Object>) handler);
    }

    public <T> void unsubscribe(Class<T> eventType, Consumer<T> handler) {
        List<Consumer<Object>> registered = listeners.get(eventType);
        if (registered != null) {
            registered.remove(handler);
        }
    }

    public void post(Object event) {
        List<Consumer<Object>> registered = listeners.get(event.getClass());
        if (registered == null) {
            return;
        }
        for (Consumer<Object> handler : registered) {
            handler.accept(event);
        }
    }
}