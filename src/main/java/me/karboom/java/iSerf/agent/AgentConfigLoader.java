package me.karboom.java.iSerf.agent;

import lombok.SneakyThrows;
import me.karboom.java.iSerf.agent.tool.Loader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Agent 配置加载器
 * 从各种来源加载 AgentConfig
 */
public class AgentConfigLoader {
    private final Loader toolLoader;

    public AgentConfigLoader() {
        this.toolLoader = new Loader(2000);
    }

    public AgentConfigLoader(Loader toolLoader) {
        this.toolLoader = toolLoader;
    }

    /**
     * 从文件系统创建配置
     * 读取 path/system-prompt.md 作为提示词
     * 读取 path/tools.yaml 作为工具配置
     *
     * @param path 配置目录
     * @return AgentConfig 实例
     */
    @SneakyThrows
    public AgentConfig fromPath(Path path) {
        var config = new AgentConfig();

        var promptPath = path.resolve("system-prompt.md");
        var prompt = Files.exists(promptPath) ? Files.readString(promptPath, StandardCharsets.UTF_8) : "";
        config.setPrompt(prompt);

        var tools = toolLoader.fromToolFile(path.resolve("tools.yaml"), null);
        config.setTools(tools);

        return config;
    }
}
