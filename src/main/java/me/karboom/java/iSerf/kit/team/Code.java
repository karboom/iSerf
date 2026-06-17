package me.karboom.java.iSerf.kit.team;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentConfig;
import me.karboom.java.iSerf.agent.AgentMetadata;
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
        var leaderConfig = new AgentConfig();
        leaderConfig.setMetadata(new AgentMetadata() {{ setId("leader"); }});
        leaderConfig.setPrompt("你是一个技术负责人，负责整个项目的架构设计和技术决策");
        leaderConfig.setLlm(llm);
        var leader = new Agent(leaderConfig) {}.run();

        var frontendConfig = new AgentConfig();
        frontendConfig.setMetadata(new AgentMetadata() {{ setId("frontend"); }});
        frontendConfig.setPrompt("你是一个前端工程师，负责用户界面和用户体验开发");
        frontendConfig.setLlm(llm);
        var frontend = new Agent(frontendConfig) {}.run();

        var backendConfig = new AgentConfig();
        backendConfig.setMetadata(new AgentMetadata() {{ setId("backend"); }});
        backendConfig.setPrompt("你是一个后端工程师，负责服务端逻辑和数据库设计");
        backendConfig.setLlm(llm);
        var backend = new Agent(backendConfig) {}.run();

        var testerConfig = new AgentConfig();
        testerConfig.setMetadata(new AgentMetadata() {{ setId("tester"); }});
        testerConfig.setPrompt("你是一个测试工程师，负责软件质量保证和测试工作");
        testerConfig.setLlm(llm);
        var tester = new Agent(testerConfig) {}.run();

        // 创建团队
        var members = List.of(frontend, backend, tester);
        return new Team(leader, members);
    }
}
