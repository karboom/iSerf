package me.karboom.java.iSerf.persistence;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.team.Task;
import me.karboom.java.iSerf.team.Team;
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

/**
 * NFS Team 持久化实现
 * 使用 CBOR 格式存储
 * 目录结构：baseDir/orgId/userId/team/teamId/
 *   ├── metadata.cbor     # 全量覆盖
 *   └── tasks.cbor        # 全量覆盖
 */
@Slf4j
public class NfsTeamPersistence implements ITeamPersistence {
    public String baseDir;

    public NfsTeamPersistence(String baseDir) {
        this.baseDir = baseDir;
    }

    // region 文件路径

    private String getTeamDir(AgentMetadata metadata) {
        return "%s/%s/%s/team/%s".formatted(baseDir, metadata.getOrgId(), metadata.getUserId(), metadata.getId());
    }

    private Path getMetadataPath(AgentMetadata metadata) {
        return Paths.get(getTeamDir(metadata) + "/metadata.cbor");
    }

    private Path getTasksPath(AgentMetadata metadata) {
        return Paths.get(getTeamDir(metadata) + "/tasks.cbor");
    }

    // endregion

    // region 通用读写

    @SneakyThrows
    private <T> T readSingle(Path path, Class<T> clazz) {
        var bytes = Files.readAllBytes(path);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return CBORUtil.parse(bytes, clazz);
    }

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
        return result.stream()
                .map(item -> CBORUtil.convert(item, clazz))
                .toList();
    }

    @SneakyThrows
    private void writeSingle(Path path, Object obj) {
        var tmpPath = Paths.get(path.toString() + ".tmp");
        var bytes = CBORUtil.toByte(obj);

        try (var channel = FileChannel.open(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(true);
        }

        Files.move(tmpPath, path, StandardCopyOption.ATOMIC_MOVE);
        log.debug("writeSingle to file: {}", path);
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

    // endregion

    // region ITeamPersistence 实现

    @Override
    @SneakyThrows
    public TeamSnapshot load(AgentMetadata metadata) {
        var loadedMetadata = readSingle(getMetadataPath(metadata), AgentMetadata.class);
        var tasks = readList(getTasksPath(metadata), Task.class);

        log.debug("load from dir: {}", getTeamDir(metadata));

        return TeamSnapshot.builder()
                .metadata(loadedMetadata)
                .tasks(tasks)
                .build();
    }

    @Override
    @SneakyThrows
    public void remove(Team team) {
        var metadata = team.leader.metadata;
        var dirPath = Paths.get(getTeamDir(metadata));
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

        log.debug("remove dir: {}", getTeamDir(metadata));
    }

    @Override
    public void syncMetadata(Team team) {
        var metadata = team.leader.metadata;
        if (metadata == null) {
            return;
        }
        writeSingle(getMetadataPath(metadata), metadata);
    }

    @Override
    public void syncTasks(Team team) {
        var metadata = team.leader.metadata;
        if (team.tasks == null) {
            return;
        }
        writeList(getTasksPath(metadata), team.tasks);
    }

    @Override
    @SneakyThrows
    public List<TeamSnapshot> search(Map<String, Object> params) {
        var result = new ArrayList<TeamSnapshot>();
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

                                        // 遍历 team 目录
                                        var teamTypePath = userPath.resolve("team");
                                        if (!Files.exists(teamTypePath)) {
                                            return;
                                        }
                                        try (var teamStream = Files.list(teamTypePath)) {
                                            teamStream.filter(Files::isDirectory)
                                                    .forEach(teamPath -> {
                                                        var teamId = teamPath.getFileName().toString();

                                                        // 匹配关键字
                                                        if (lowerKeyword.isEmpty()
                                                                || orgId.toLowerCase().contains(lowerKeyword)
                                                                || userId.toLowerCase().contains(lowerKeyword)
                                                                || teamId.toLowerCase().contains(lowerKeyword)) {
                                                            var metadata = AgentMetadata.builder()
                                                                    .id(teamId)
                                                                    .orgId(orgId)
                                                                    .userId(userId)
                                                                    .build();
                                                            result.add(load(metadata));
                                                        }
                                                    });
                                        } catch (Exception e) {
                                            log.error("search failed at team level: {}", userPath, e);
                                        }
                                    });
                        } catch (Exception e) {
                            log.error("search failed at user level: {}", orgPath, e);
                        }
                    });
        }

        log.debug("search params: {} found {} teams", params, result.size());
        return result;
    }

    @Override
    @SneakyThrows
    public Boolean create(Team team) {
        var metadata = team.leader.metadata;
        var teamDir = Paths.get(getTeamDir(metadata));

        // 如果目录已存在，返回 false
        if (Files.exists(teamDir)) {
            log.debug("create team dir already exists: {}", teamDir);
            return false;
        }

        var metadataPath = getMetadataPath(metadata);
        var tasksPath = getTasksPath(metadata);

        // 创建目录结构
        Files.createDirectories(teamDir);

        // 创建空文件
        Files.createFile(metadataPath);
        Files.createFile(tasksPath);

        // 写入初始 metadata
        if (metadata != null) {
            writeSingle(metadataPath, metadata);
        }

        log.debug("create team dir: {}", teamDir);
        return true;
    }

    // endregion
}
