package me.karboom.java.iSerf.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.memory.Item;
import me.karboom.java.iSerf.util.CBORUtil;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 使用 CBOR 格式存储（比JSON解析快40%，体积小60%）
 * 目录结构：baseDir/orgId/userId/agentId.cbor
 * Todo 构造的时候支持选择json / cbor
 * 注意事项：由于都是小文件，需要做好nft的挂载优化
 */
@Slf4j
public class NfsPersistence implements IPersistence {
    public String baseDir;

    public NfsPersistence(String baseDir) {
        this.baseDir = baseDir;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static public class Format {
        public List<Item> memories;
        public List<Event> events;
    }

    /**
     * 将文件内容读取为Format类，然后返回对应字段
     * @param orgId
     * @param userId
     * @param agentId
     * @return
     */
    @Override
    @SneakyThrows
    public Tuple2<List<Item>, List<Event>> load(String orgId, String userId, String agentId) {
        var dir = "%s/%s/%s".formatted(baseDir, orgId, userId);
        var fileName = "%s/%s.cbor".formatted(dir, agentId);
        var path = Paths.get(fileName);

        if (!Files.exists(path)) {
            log.debug("load file not exists: {}", fileName);
            return Tuples.of(List.of(), List.of());
        }

        var bytes = Files.readAllBytes(path);
        if (bytes == null || bytes.length == 0) {
            log.debug("load file is empty: {}", fileName);
            return Tuples.of(List.of(), List.of());
        }

        var format = CBORUtil.parse(bytes, Format.class);


        log.debug("load from file: {}", fileName);
        return Tuples.of(format.memories, format.events);
    }

    /**
     *   1. 将参数装入Format类，序列化为byte[]
     *   2. 先写入.tmp文件，然后调用fsyncio确保文件成功后，重命名
     * @param orgId
     * @param userId
     * @param agentId
     * @param events
     * @param memories
     */
    @Override
    @SneakyThrows
    public void save(String orgId, String userId, String agentId, List<Event> events, List<Item> memories) {
        var dir = "%s/%s/%s".formatted(baseDir, orgId, userId);
        var dirPath = Paths.get(dir);

        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }

        var fileName = "%s/%s.cbor".formatted(dir, agentId);
        var tmpFileName = fileName + ".tmp";
        var path = Paths.get(fileName);
        var tmpPath = Paths.get(tmpFileName);

        var format = Format.builder()
                .memories(memories)
                .events(events)
                .build();

        var bytes = CBORUtil.toByte(format);

        try (var channel = FileChannel.open(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(true);
        }

        Files.move(tmpPath, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        log.debug("save to file: {}", fileName);
    }
}
