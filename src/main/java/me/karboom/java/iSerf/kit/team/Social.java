package me.karboom.java.iSerf.kit.team;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.team.Team;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

public class Social {
    public Team text() throws IOException {
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
        // 创建团队成员
        var leader = new Agent("leader", provider, rootPath.resolve("leader")) {} ;
        var topic = new Agent("topic", provider, rootPath.resolve("topic")) {};
        var tree = new Agent("tree", provider, rootPath.resolve("tree")) {};
        var editor = new Agent("editor", provider, rootPath.resolve("editor")) {};
        var validator = new Agent("validator", provider, rootPath.resolve("validator")) {};
        var channel = new Agent("channel", provider, rootPath.resolve("channel")) {};

        // 创建团队
        var members = List.of(topic, tree, editor, validator, channel);
        return new Team(leader, members);
    }
}
