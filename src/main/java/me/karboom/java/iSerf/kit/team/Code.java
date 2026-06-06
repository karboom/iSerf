package me.karboom.java.iSerf.kit.team;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.team.Team;

import java.util.HashMap;
import java.util.List;

public class Code {

    public Code() {

    }
    /**
     * 创建一个传统软件团队，包含一个技术负责人、一个前端、一个后端、一个测试
     */
    public Team traditional() {
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

        var llm = new FixedLlmProvider(new OpenAI("qwen-plus", llmConfig, apiKey, url, 3));

        // 创建团队成员
        var leader = new Agent("leader", "你是一个技术负责人，负责整个项目的架构设计和技术决策", llm, null) {}.run();
        var frontend = new Agent("frontend", "你是一个前端工程师，负责用户界面和用户体验开发", llm, null) {}.run();
        var backend = new Agent("backend", "你是一个后端工程师，负责服务端逻辑和数据库设计", llm, null) {}.run();
        var tester = new Agent("tester", "你是一个测试工程师，负责软件质量保证和测试工作", llm, null) {}.run();

        // 创建团队
        var members = List.of(frontend, backend, tester);
        return new Team(leader, members);
    }
}
