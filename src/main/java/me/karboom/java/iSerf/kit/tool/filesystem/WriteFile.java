package me.karboom.java.iSerf.kit.tool.filesystem;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import me.karboom.java.iSerf.util.ErrorUtil;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 向文件写入内容
 */
@Slf4j
public class WriteFile implements FunctionWrapper<WriteFile.Parameter> {

    public static String description = "向文件写入内容";

    public static class Parameter {
        @JsonPropertyDescription("要写入的文件路径")
        @JsonProperty(required = true)
        public String path;

        @JsonPropertyDescription("要写入文件的内容")
        @JsonProperty(required = true)
        public String content;
    }

    @Override
    @SneakyThrows
    public CallResult run(Context ctx, Parameter params) {
        var rootPath = ctx.agent.workDir;
        if (rootPath == null) {
            throw ErrorUtil.make("Agent workDir is null");
        }

        var filePath = Path.of(params.path);
        var fullPath = rootPath.resolve(filePath).normalize();

        if (!fullPath.startsWith(rootPath)) {
            throw ErrorUtil.make("访问被拒绝：路径在根目录之外");
        }

        log.debug("writeFile: path=%s".formatted(fullPath));

        Files.createDirectories(fullPath.getParent());
        Files.writeString(fullPath, params.content);

        return CallResult.builder()
                .llm("文件写入成功")
                .build();
    }
}