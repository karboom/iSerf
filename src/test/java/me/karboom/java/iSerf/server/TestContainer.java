package me.karboom.java.iSerf.server;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentConfig;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;

import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.server.bo.Context;
import me.karboom.java.iSerf.server.lifecycle.IAgentLifecycle;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * 测试用 AgentLifecycle 实现，Agent 工厂方法
 */
public class TestContainer implements IAgentLifecycle {

    @Override
    public Agent createAgent(Context ctx, ObjectNode params) {
        var llm = new OpenAI("qwen-plus", new HashMap<>(), System.getenv("OPENAI_API_KEY"),
                "https://dashscope.aliyuncs.com/compatible-mode/v1", 3);
        var provider = new FixedLlmProvider(llm);
        var config = new AgentConfig();
        var metadata = new AgentMetadata();
        metadata.setId(UUID.randomUUID().toString());
        config.setMetadata(metadata);
        config.setPrompt("test transport agent");
        config.setLlm(provider);
        var agent = new Agent(config).run();
        return agent;
    }

    @Override
    public List<Agent> listAgent(Context ctx, ObjectNode params) {
        return new ArrayList<>();
    }

    @Override
    public Agent removeAgent(Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public Agent editAgent(Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public Agent detailAgent(Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public ObjectNode serializeAgent(Agent agent) {
        return JSONUtil.convert(agent);
    }


}