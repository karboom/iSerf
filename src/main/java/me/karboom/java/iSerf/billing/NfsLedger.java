package me.karboom.java.iSerf.billing;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.util.CBORUtil;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import oshi.SystemInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class NfsLedger implements ILedger {
    private static final String CPU_MODEL = new SystemInfo()
            .getHardware().getProcessor().getProcessorIdentifier().getName();

    private final Path filePath;
    private final String format;

    public static class Format {
        public static final String CBOR = "CBOR";
        public static final String JSONL = "JSONL";
    }

    @SneakyThrows
    public NfsLedger(String filePath, String format) {
        this.filePath = Paths.get(filePath);
        this.format = format;
        Files.createDirectories(this.filePath.getParent());
    }

    @Override
    @SneakyThrows
    public void record(Cost cost) {
        byte[] data;
        if (Format.CBOR.equals(format)) {
            data = CBORUtil.toByte(cost);
        } else {
            data = (JSONUtil.stringify(cost) + "\n").getBytes(StandardCharsets.UTF_8);
        }
        Files.write(filePath, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        log.debug("record Cost: {} -> {} format: {}", cost.id, filePath, format);
    }

    @Override
    @SneakyThrows
    public List<Cost> query(String targetType, String targetId) {
        var result = new ArrayList<Cost>();
        if (!Files.exists(filePath)) {
            return result;
        }

        if (Format.CBOR.equals(format)) {
            var bytes = Files.readAllBytes(filePath);
            if (bytes == null || bytes.length == 0) {
                return result;
            }
            // CBOR 格式：使用流式解析读取多个对象
            var parser = CBORUtil.mapper.createParser(bytes);
            var iterator = CBORUtil.mapper.readerFor(Cost.class).<Cost>readValues(parser);
            while (iterator.hasNext()) {
                var cost = iterator.next();
                if (targetType.equals(cost.getTargetType()) && targetId.equals(cost.getTargetId())) {
                    result.add(cost);
                }
            }
        } else {
            // JSONL 格式：逐行读取
            try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    var cost = JSONUtil.parse(line, Cost.class);
                    if (targetType.equals(cost.getTargetType()) && targetId.equals(cost.getTargetId())) {
                        result.add(cost);
                    }
                }
            }
        }

        log.debug("query targetType: {} targetId: {} found: {}", targetType, targetId, result.size());
        return result;
    }

    @Override
    @SneakyThrows
    public List<Cost> queryByUser(String userId) {
        var result = new ArrayList<Cost>();
        if (!Files.exists(filePath)) {
            return result;
        }

        if (Format.CBOR.equals(format)) {
            var bytes = Files.readAllBytes(filePath);
            if (bytes == null || bytes.length == 0) {
                return result;
            }
            var parser = CBORUtil.mapper.createParser(bytes);
            var iterator = CBORUtil.mapper.readerFor(Cost.class).<Cost>readValues(parser);
            while (iterator.hasNext()) {
                var cost = iterator.next();
                if (userId.equals(cost.getUserId())) {
                    result.add(cost);
                }
            }
        } else {
            try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    var cost = JSONUtil.parse(line, Cost.class);
                    if (userId.equals(cost.getUserId())) {
                        result.add(cost);
                    }
                }
            }
        }

        log.debug("queryByUser userId: {} found: {}", userId, result.size());
        return result;
    }

    // region ========== 语义方法 ==========

    @Override
    public void recordMemory(Agent agent, long bytes) {
        record(Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agent.metadata.getId())
                .memory((int) bytes)
                .captureTime(Instant.now())
                .build());
    }

    @Override
    public void recordCpu(Agent agent, long nanos) {
        record(Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agent.metadata.getId())
                .cpuModel(CPU_MODEL)
                .cpu(nanos)
                .captureTime(Instant.now())
                .build());
    }

    @Override
    public void recordToken(Agent agent, int tokens) {
        record(Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agent.metadata.getId())
                .token(tokens)
                .captureTime(Instant.now())
                .build());
    }

    @Override
    public void recordDisk(Agent agent, int bytes) {
        record(Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agent.metadata.getId())
                .disk(bytes)
                .captureTime(Instant.now())
                .build());
    }

    @Override
    public void recordTraffic(Agent agent, int bytes) {
        record(Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agent.metadata.getId())
                .traffic(bytes)
                .captureTime(Instant.now())
                .build());
    }

    @Override
    public long usageCount(Agent agent) {
        return query("agent", agent.metadata.getId()).size();
    }

    @Override
    public long totalTokens(Agent agent) {
        return query("agent", agent.metadata.getId()).stream()
                .filter(c -> c.getToken() != null)
                .mapToLong(Cost::getToken)
                .sum();
    }

    // endregion

    // region ========== 用户维度统计 ==========

    @Override
    public UsageSummary queryUsageSummary(String userId) {
        var costs = queryByUser(userId);
        return UsageSummary.builder()
                .count((long) costs.size())
                .cpu(costs.stream().filter(c -> c.getCpu() != null).mapToLong(Cost::getCpu).sum())
                .memory(costs.stream().filter(c -> c.getMemory() != null).mapToLong(Cost::getMemory).sum())
                .disk(costs.stream().filter(c -> c.getDisk() != null).mapToLong(Cost::getDisk).sum())
                .token(costs.stream().filter(c -> c.getToken() != null).mapToLong(Cost::getToken).sum())
                .traffic(costs.stream().filter(c -> c.getTraffic() != null).mapToLong(Cost::getTraffic).sum())
                .build();
    }

    // endregion
}
