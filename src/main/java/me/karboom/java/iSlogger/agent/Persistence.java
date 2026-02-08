package me.karboom.java.iSlogger.agent;

import io.netty.util.concurrent.ThreadPerTaskExecutor;
import me.karboom.java.iSlogger.memory.Item;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class Persistence {
    public ExecutorService threadPool;

    public Persistence () {
        threadPool = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("", 0).factory());
    }


    public void save(String agentId, Object data) {
        threadPool.submit(() -> {
            if (data instanceof List) {
                var first = ((List) data).getFirst();
                switch (first) {
                    case Item i -> {
                        this.saveMemory(agentId, (List<Item>) data);
                    }
                    case Event e -> {
                        this.saveEvent(agentId, (List<Event>) data);
                    }
                    default -> throw new IllegalStateException("Unexpected value: " + first);
                }
            }
        });
    }

    public <T> Mono<List<T>> load(String agentId, Class<T> clz) {
        return Mono.<List<T>>create(sink -> {
            if (Item.class.isAssignableFrom(clz)) {
                sink.success( (List<T>) loadMemory(agentId) );
            } else if (Event.class.isAssignableFrom(clz)) {
                sink.success(( List<T>) loadEvent(agentId) );
            }
        })
        .publishOn(Schedulers.fromExecutor(threadPool))
        ;

    }

    protected abstract List<Item> loadMemory(String agentId);
    protected abstract void saveMemory(String agentId, List<Item> data);

    protected abstract List<Event> loadEvent(String agentId);
    protected abstract void saveEvent(String agentId, List<Event> data);
}
