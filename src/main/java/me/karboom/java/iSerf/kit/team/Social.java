package me.karboom.java.iSerf.kit.team;

import lombok.SneakyThrows;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentConfig;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.agent.llmProvider.ILlmProvider;
import me.karboom.java.iSerf.agent.tool.Loader;
import me.karboom.java.iSerf.kit.tool.filesystem.ListDirectory;
import me.karboom.java.iSerf.kit.tool.filesystem.ReadFile;
import me.karboom.java.iSerf.kit.tool.filesystem.WriteFile;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.team.Team;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

public class Social {
    @SneakyThrows
    public Team text(Path workBase) {
        // 获取环境变量中的API密钥和URL
        var apiKey = System.getenv("OPENAI_API_KEY");
        var url = System.getenv("OPENAI_API_URL");

        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        // 配置 LLM 参数
        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("max_tokens", 1000);

        var llm = new OpenAI("qwen3.6-flash", llmConfig, apiKey, url, 3);

        var provider = new FixedLlmProvider(llm);
        var rootPath = Path.of("resources/kit/team/social");

        // 安装文件系统工具
        var loader = new Loader(2000);
        var fileSystemTools = List.of(
                loader.fromFunction(ReadFile.class),
                loader.fromFunction(WriteFile.class),
                loader.fromFunction(ListDirectory.class)
        );

        // 创建团队成员
        var leader = createAgent("leader", provider, rootPath.resolve("leader"), loader);
        var topic = createAgent("topic", provider, rootPath.resolve("topic"), loader);
        var tree = createAgent("tree", provider, rootPath.resolve("tree"), loader);
        var editor = createAgent("editor", provider, rootPath.resolve("editor"), loader);
        var validator = createAgent("validator", provider, rootPath.resolve("validator"), loader);
        var channel = createAgent("channel", provider, rootPath.resolve("channel"), loader);

        // 设置工作目录并安装文件系统工具
        for (var member : List.of(leader, topic, tree, editor, validator, channel)) {
            member.workDir = workBase.resolve(member.metadata.getId());
            member.toolHandler.getTools().addAll(fileSystemTools);
        }

        // 创建团队
        var members = List.of(topic, tree, editor, validator, channel);
        return new Team(leader, members);
    }

    /**
     * 从文件系统创建 Agent
     * path/system-prompt.md 系统提示词
     * path/tools.yaml 工具配置
     */
    @SneakyThrows
    private Agent createAgent(String id, ILlmProvider provider, Path path, Loader loader) {
        var tools = loader.fromToolFile(path.resolve("tools.yaml"), null);
        var promptPath = path.resolve("system-prompt.md");
        var prompt = Files.exists(promptPath) ? Files.readString(promptPath, StandardCharsets.UTF_8) : "";

        var config = new AgentConfig();
        var metadata = new AgentMetadata();
        metadata.setId(id);
        config.setMetadata(metadata);
        config.setPrompt(prompt);
        config.setLlm(provider);
        config.setTools(tools);
        config.setWorkDir(path);

        return new Agent(config) {}.run();
    }
}
