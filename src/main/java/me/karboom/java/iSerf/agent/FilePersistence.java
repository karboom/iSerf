package me.karboom.java.iSlogger.agent;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSlogger.memory.Item;
import me.karboom.java.iSlogger.util.JSONUtil;
import tools.jackson.core.type.TypeReference;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
public class FilePersistence extends Persistence {
    public String baseDir;
    public String memoryFileTemplate;
    public String eventFileTemplate;

    public FilePersistence(String baseDir) {
        this.baseDir = baseDir;
        this.memoryFileTemplate = "%s/memory.json".formatted(baseDir);
        this.eventFileTemplate = "%s/event.json".formatted(baseDir);
        initDir();
    }

    private void initDir() {
        var path = Paths.get(baseDir);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new RuntimeException("create dir failed", e);
            }
        }
    }

    @Override
    @SneakyThrows
    protected List<Item> loadMemory(String agentId) {
        var fileName = "%s/memory_%s.json".formatted(baseDir, agentId);
        var path = Paths.get(fileName);
        var file = path.toFile();
        
        if (!file.exists()) {
            log.debug("loadMemory file not exists: {}", fileName);
            return List.of();
        }
        
        var content = Files.readString(path);
        if (content.isBlank()) {
            log.debug("loadMemory content is blank: {}", fileName);
            return List.of();
        }
        
        log.debug("loadMemory load from file: {}", fileName);
        var arrayNode = JSONUtil.parseArray(content);
        return JSONUtil.convert(arrayNode, new TypeReference<List<Item>>() {});
    }

    @Override
    @SneakyThrows
    protected void saveMemory(String agentId, List<Item> data) {
        var fileName = "%s/memory_%s.json".formatted(baseDir, agentId);
        var path = Paths.get(fileName);
        
        initDir();
        var json = JSONUtil.stringify(data);
        Files.writeString(path, json);
        log.debug("saveMemory save to file: {}", fileName);
    }

    @Override
    @SneakyThrows
    protected List<Event> loadEvent(String agentId) {
        var fileName = "%s/event_%s.json".formatted(baseDir, agentId);
        var path = Paths.get(fileName);
        var file = path.toFile();
        
        if (!file.exists()) {
            log.debug("loadEvent file not exists: {}", fileName);
            return List.of();
        }
        
        var content = Files.readString(path);
        if (content.isBlank()) {
            log.debug("loadEvent content is blank: {}", fileName);
            return List.of();
        }
        
        log.debug("loadEvent load from file: {}", fileName);
        var arrayNode = JSONUtil.parseArray(content);
        return JSONUtil.convert(arrayNode, new TypeReference<List<Event>>() {});
    }

    @Override
    @SneakyThrows
    protected void saveEvent(String agentId, List<Event> data) {
        var fileName = "%s/event_%s.json".formatted(baseDir, agentId);
        var path = Paths.get(fileName);
        
        initDir();
        var json = JSONUtil.stringify(data);
        Files.writeString(path, json);
        log.debug("saveEvent save to file: {}", fileName);
    }
}