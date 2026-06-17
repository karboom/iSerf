package me.karboom.java.iSerf.agent.persistence;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.agent.AgentEvent;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.agent.tool.CallCache;
import me.karboom.java.iSerf.schedule.Plan;
import me.karboom.java.iSerf.util.CBORUtil;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NFS 持久化实现
 * 使用 CBOR 格式存储（比JSON解析快40%，体积小60%）
 * 目录结构：baseDir/orgId/userId/agentId/
 *   ├── memory/           # 追加写入目录
 *   │   ├── 000001.cbor   # 序号递增的文件
 *   │   ├── 000002.cbor
 *   │   └── ...
 *   ├── toolCall/         # 追加写入目录
 *   │   └── ...
 *   ├── event.cbor        # 全量覆盖
 *   └── plan.cbor         # 全量覆盖
 * 
 * 追加模式采用日志追加策略：
 * - add 操作直接写入新文件，无需读取
 * - sync 操作合并所有文件为一个
 * - load 操作读取所有文件并合并
 * 
 * 注意事项：由于都是小文件，需要做好NFS的挂载优化
 */
@Slf4j
public class NfsPersistence implements IPersistence {
    public String baseDir;
    
    // 序号生成器（每个 Agent 独立）
    private static final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> counters = new java.util.concurrent.ConcurrentHashMap<>();
    
    // 合并阈值：文件数量超过此值时触发合并
    private static final int MERGE_THRESHOLD = 100;
    
    // 索引文件名
    private static final String INDEX_FILE = "index.txt";

    public NfsPersistence(String baseDir) {
        this.baseDir = baseDir;
    }

    // region 文件路径

    private String getAgentDir(AgentMetadata metadata) {
        return "%s/%s/%s/%s".formatted(baseDir, metadata.getOrgId(), metadata.getUserId(), metadata.getId());
    }

    private Path getMemoryDir(AgentMetadata metadata) {
        return Paths.get(getAgentDir(metadata) + "/memory");
    }

    private Path getToolCallDir(AgentMetadata metadata) {
        return Paths.get(getAgentDir(metadata) + "/toolCall");
    }

    private Path getEventPath(AgentMetadata metadata) {
        return Paths.get(getAgentDir(metadata) + "/event.cbor");
    }

    private Path getPlanPath(AgentMetadata metadata) {
        return Paths.get(getAgentDir(metadata) + "/plan.cbor");
    }

    // endregion

    // region 通用读写

    @SneakyThrows
    private <T> List<T> readList(Path path, Class<T> clazz) {
        var bytes = Files.readAllBytes(path);
        if (bytes == null || bytes.length == 0) {
            return new ArrayList<>();
        }
        var result = CBORUtil.parse(bytes, List.class);
        if (result == null) {
            return new ArrayList<>();
        }
        // CBOR 反序列化后需要转换为具体类型
        return result.stream()
                .map(item -> CBORUtil.convert(item, clazz))
                .toList();
    }

    @SneakyThrows
    private <T> List<T> readDir(Path dir, Class<T> clazz) {
        var result = new ArrayList<T>();
        try (var stream = Files.list(dir)) {
            var files = stream
                    .filter(p -> p.toString().endsWith(".cbor"))
                    .sorted()
                    .toList();
            for (var file : files) {
                result.addAll(readList(file, clazz));
            }
        }
        return result;
    }

    @SneakyThrows
    private void writeList(Path path, List<?> list) {
        var tmpPath = Paths.get(path.toString() + ".tmp");
        var bytes = CBORUtil.toByte(list);

        try (var channel = FileChannel.open(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(true);
        }

        Files.move(tmpPath, path, StandardCopyOption.ATOMIC_MOVE);
        log.debug("writeList to file: {}", path);
    }

    @SneakyThrows
    private <T> void appendToDir(Path dir, List<T> items, Class<T> clazz) {
        var key = dir.toString();
        var counter = counters.computeIfAbsent(key, k -> {
            // 从索引文件读取当前序号
            var indexFile = dir.resolve(INDEX_FILE);
            try {
                if (Files.exists(indexFile)) {
                    var content = Files.readString(indexFile).trim();
                    return new AtomicLong(Long.parseLong(content));
                }
            } catch (Exception e) {
                log.warn("read index file failed: {}", indexFile, e);
            }
            return new AtomicLong(0L);
        });
        
        var seq = counter.incrementAndGet();
        var fileName = "%06d.cbor".formatted(seq);
        var filePath = dir.resolve(fileName);
        
        writeList(filePath, items);
        
        // 更新索引文件
        var indexFile = dir.resolve(INDEX_FILE);
        Files.writeString(indexFile, String.valueOf(seq));
        
        log.debug("appendToDir: {} with {} items", filePath, items.size());
        
        // 检查是否需要触发合并
        checkAndTriggerMerge(dir);
    }
    
    @SneakyThrows
    private void checkAndTriggerMerge(Path dir) {
        // 统计文件数量
        long fileCount;
        try (var stream = Files.list(dir)) {
            fileCount = stream
                    .filter(p -> p.toString().endsWith(".cbor"))
                    .count();
        }
        
        // 超过阈值时触发合并
        if (fileCount >= MERGE_THRESHOLD) {
            log.info("trigger merge for dir: {}, file count: {}", dir, fileCount);
            // 推断类型进行合并（这里使用 Object 作为通用类型）
            mergeDirGeneric(dir);
        }
    }
    
    @SneakyThrows
    private void mergeDirGeneric(Path dir) {
        // 读取所有文件（作为通用 List）
        var allItems = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            var files = stream
                    .filter(p -> p.toString().endsWith(".cbor"))
                    .sorted()
                    .toList();
            for (var file : files) {
                var bytes = Files.readAllBytes(file);
                if (bytes != null && bytes.length > 0) {
                    var list = CBORUtil.parse(bytes, List.class);
                    if (list != null) {
                        allItems.addAll(list);
                    }
                }
            }
        }
        
        if (allItems.isEmpty()) {
            return;
        }
        
        // 删除所有现有文件
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".cbor"))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception e) {
                            log.error("delete file failed: {}", p, e);
                        }
                    });
        }
        
        // 写入合并后的单个文件
        var mergedPath = dir.resolve("000001.cbor");
        writeList(mergedPath, allItems);
        
        // 更新索引文件
        var indexFile = dir.resolve(INDEX_FILE);
        Files.writeString(indexFile, "1");
        
        // 重置计数器
        var key = dir.toString();
        counters.put(key, new AtomicLong(1L));
        
        log.debug("mergeDirGeneric: {} merged {} items", dir, allItems.size());
    }

    @SneakyThrows
    private <T> void mergeDir(Path dir, Class<T> clazz) {
        // 读取所有文件
        var allItems = readDir(dir, clazz);
        if (allItems.isEmpty()) {
            return;
        }
        
        // 删除所有现有文件
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".cbor"))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception e) {
                            log.error("delete file failed: {}", p, e);
                        }
                    });
        }
        
        // 写入合并后的单个文件
        var mergedPath = dir.resolve("000001.cbor");
        writeList(mergedPath, allItems);
        
        // 更新索引文件
        var indexFile = dir.resolve(INDEX_FILE);
        Files.writeString(indexFile, "1");
        
        // 重置计数器
        var key = dir.toString();
        counters.put(key, new AtomicLong(1L));
        
        log.debug("mergeDir: {} merged {} items", dir, allItems.size());
    }

    // endregion

    // region IPersistence 实现

    @Override
    @SneakyThrows
    public AgentSnapshot load(AgentMetadata metadata) {
        var memories = readDir(getMemoryDir(metadata), AgentMessage.class);
        var events = readList(getEventPath(metadata), AgentEvent.class);
        var toolCalls = readDir(getToolCallDir(metadata), CallCache.class);
        var plans = readList(getPlanPath(metadata), Plan.class);

        log.debug("load from dir: {}", getAgentDir(metadata));

        return AgentSnapshot.builder()
                .memories(memories)
                .events(events)
                .toolCalls(toolCalls)
                .plans(plans)
                .build();
    }

    @Override
    @SneakyThrows
    public void remove(Agent agent) {
        var dirPath = Paths.get(getAgentDir(agent.metadata));
        if (Files.exists(dirPath)) {
            try (var stream = Files.walk(dirPath)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (Exception e) {
                                log.error("delete file failed: {}", path, e);
                            }
                        });
            }
        }
        // 清理计数器
        counters.remove(getMemoryDir(agent.metadata).toString());
        counters.remove(getToolCallDir(agent.metadata).toString());
        
        log.debug("remove dir: {}", getAgentDir(agent.metadata));
    }

    @Override
    public void syncMemory(Agent agent) {
        var messages = agent.getMemoryManager().getMessagesRaw();
        var dir = getMemoryDir(agent.metadata);
        
        // 删除所有现有文件（包括索引文件）
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".cbor") || p.getFileName().toString().equals(INDEX_FILE))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception e) {
                            log.error("delete file failed: {}", p, e);
                        }
                    });
        } catch (Exception e) {
            log.error("list dir failed: {}", dir, e);
        }
        
        // 重置计数器
        counters.remove(dir.toString());
        
        // 写入合并后的数据
        if (!messages.isEmpty()) {
            appendToDir(dir, messages, AgentMessage.class);
        }
    }

    @Override
    public void addMemory(Agent agent) {
        var messages = agent.getMemoryManager().getMessagesRaw();
        if (messages.isEmpty()) {
            return;
        }
        // 追加最后一条（新增的消息）
        var lastMessage = messages.getLast();
        appendToDir(getMemoryDir(agent.metadata), List.of(lastMessage), AgentMessage.class);
    }

    @Override
    public void syncEvent(Agent agent) {
        // 事件处理完后清空
        writeList(getEventPath(agent.metadata), List.of());
    }

    @Override
    public void syncToolCall(Agent agent) {
        var caches = agent.getToolHandler().getCaches();
        var dir = getToolCallDir(agent.metadata);
        
        // 删除所有现有文件（包括索引文件）
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".cbor") || p.getFileName().toString().equals(INDEX_FILE))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception e) {
                            log.error("delete file failed: {}", p, e);
                        }
                    });
        } catch (Exception e) {
            log.error("list dir failed: {}", dir, e);
        }
        
        // 重置计数器
        counters.remove(dir.toString());
        
        // 写入合并后的数据
        if (!caches.isEmpty()) {
            appendToDir(dir, caches, CallCache.class);
        }
    }

    @Override
    public void addToolCall(Agent agent) {
        var caches = agent.getToolHandler().getCaches();
        if (caches.isEmpty()) {
            return;
        }
        // 追加最后一条（新增的缓存）
        var lastCache = caches.getLast();
        appendToDir(getToolCallDir(agent.metadata), List.of(lastCache), CallCache.class);
    }

    @Override
    public void syncPlan(Agent agent) {
        if (agent.schedule == null) {
            return;
        }
        // ISchedule 接口没有 getPlans 方法，需要转换为 Schedule 类型
        if (agent.schedule instanceof me.karboom.java.iSerf.schedule.Schedule schedule) {
            var plans = schedule.getPlans();
            writeList(getPlanPath(agent.metadata), plans);
        }
    }

    @Override
    @SneakyThrows
    public List<AgentSnapshot> search(Map<String, Object> params) {
        var result = new ArrayList<AgentSnapshot>();
        var basePath = Paths.get(baseDir);

        if (!Files.exists(basePath)) {
            return result;
        }

        var keyword = params != null ? (String) params.get("keyword") : null;
        var lowerKeyword = keyword != null ? keyword.toLowerCase() : "";

        // 遍历 orgId 目录
        try (var orgStream = Files.list(basePath)) {
            orgStream.filter(Files::isDirectory)
                    .forEach(orgPath -> {
                        var orgId = orgPath.getFileName().toString();

                        // 遍历 userId 目录
                        try (var userStream = Files.list(orgPath)) {
                            userStream.filter(Files::isDirectory)
                                    .forEach(userPath -> {
                                        var userId = userPath.getFileName().toString();

                                        // 遍历 agentId 目录
                                        try (var agentStream = Files.list(userPath)) {
                                            agentStream.filter(Files::isDirectory)
                                                    .forEach(agentPath -> {
                                                        var agentId = agentPath.getFileName().toString();

                                                        // 匹配关键字
                                                        if (lowerKeyword.isEmpty()
                                                                || orgId.toLowerCase().contains(lowerKeyword)
                                                                || userId.toLowerCase().contains(lowerKeyword)
                                                                || agentId.toLowerCase().contains(lowerKeyword)) {
                                                            var metadata = AgentMetadata.builder()
                                                                    .id(agentId)
                                                                    .orgId(orgId)
                                                                    .userId(userId)
                                                                    .build();
                                                            result.add(load(metadata));
                                                        }
                                                    });
                                        } catch (Exception e) {
                                            log.error("search failed at agent level: {}", userPath, e);
                                        }
                                    });
                        } catch (Exception e) {
                            log.error("search failed at user level: {}", orgPath, e);
                        }
                    });
        }

        log.debug("search params: {} found {} agents", params, result.size());
        return result;
    }

    @Override
    @SneakyThrows
    public Boolean create(Agent agent) {
        var agentDir = Paths.get(getAgentDir(agent.metadata));
        
        // 如果目录已存在，返回 false
        if (Files.exists(agentDir)) {
            log.debug("create agent dir already exists: {}", agentDir);
            return false;
        }

        var memoryDir = getMemoryDir(agent.metadata);
        var toolCallDir = getToolCallDir(agent.metadata);
        var eventPath = getEventPath(agent.metadata);
        var planPath = getPlanPath(agent.metadata);

        // 创建目录结构
        Files.createDirectories(agentDir);
        Files.createDirectories(memoryDir);
        Files.createDirectories(toolCallDir);

        // 创建空文件
        Files.createFile(eventPath);
        Files.createFile(planPath);

        log.debug("create agent dir: {}", agentDir);
        return true;
    }

    // endregion
}
