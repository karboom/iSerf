package me.karboom.java.iSerf.server;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.server.ContainerServer;
import me.karboom.java.iSerf.server.messageBus.Pulsar;
import me.karboom.java.iSerf.server.metaData.RedisSingle;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * 测试用 AgentLifecycle 实现，Agent 工厂方法
 */
public class TestContainer extends AgentLifecycle {

    @Override
    public Agent createAgent(ContainerServer.Context ctx, ObjectNode params) {
        var llm = new OpenAI("qwen-plus", new HashMap<>(), System.getenv("OPENAI_API_KEY"),
                "https://dashscope.aliyuncs.com/compatible-mode/v1", 3);
        var provider = new FixedLlmProvider(llm);
        var agent = new Agent(UUID.randomUUID().toString(), "test transport agent", provider, null).run();
        return agent;
    }

    @Override
    public List<Agent> listAgent(ContainerServer.Context ctx, ObjectNode params) {
        return new ArrayList<>();
    }

    @Override
    public Agent removeAgent(ContainerServer.Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public Agent editAgent(ContainerServer.Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public Agent detailAgent(ContainerServer.Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public ObjectNode serializeAgent(Agent agent) {
        return JSONUtil.convert(agent);
    }
}