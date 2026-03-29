package me.karboom.java.iSerf.billing;

import lombok.extern.slf4j.Slf4j;
import lombok.SneakyThrows;
import me.karboom.java.iSerf.util.DataUtil;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Slf4j
public class ExecutorService implements Executor {

    /**
     * 配置 Map：type-usage -> 最大并发数
     * 如果 Map 为空或某个 type-usage 没有配置，默认并发数为 1
     */
    public static final Map<String, Integer> usageLimits = new HashMap<>();

    /**
     * Semaphore Map：type-id-usage -> Semaphore
     */
    private static final ConcurrentMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    /**
     * 目标类型
     */
    public String type;
    /**
     * 目标 ID
     */
    public String id;
    /**
     * 用途
     */
    public String usage;

    public ILedger ledger;

    public ExecutorService(String type, String id, String usage) {
        this.type = type;
        this.id = id;
        this.usage = usage;
    }

    @Override
    @SneakyThrows
    public void execute(@NonNull Runnable command) {
        var typeUsageKey = "%s-%s".formatted(type, usage);
        var typeIdUsageKey = "%s-%s-%s".formatted(type, id, usage);
        var maxConcurrent = usageLimits.getOrDefault(typeUsageKey, 1);

        var semaphore = semaphores.computeIfAbsent(typeIdUsageKey, k -> new Semaphore(maxConcurrent));

        // 阻塞等待可用槽位
        semaphore.acquire();

        var startTime = System.nanoTime();
        Thread.ofVirtual()
                .name("%s-%s-%s".formatted(type, id, usage))
                .start(() -> {
                    try {
                        command.run();
                        var cpu = (int) ((System.nanoTime() - startTime) / 1000);
                        ledger.record(Cost.builder()
                                .id(DataUtil.getFlakeId())
                                .targetType(type)
                                .targetId(id)
                                .usage(usage)
                                .cpu(cpu)
                                .build());
                    } finally {
                        semaphore.release();
                    }
                });
    }
}