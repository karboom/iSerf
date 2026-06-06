package me.karboom.java.iSerf.server;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.server.ContainerServer;
import me.karboom.java.iSerf.server.messageBus.Pulsar;
import me.karboom.java.iSerf.server.metaData.RedisSingle;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * 测试用 ContainerServer 子类，内置消息总线和元数据存储，实现 Agent 工厂方法
 */
public class TestContainer extends ContainerServer {

    public TestContainer() {
        super("127.0.0.1", new Pulsar("pulsar://localhost:6650"), new RedisSingle("redis://:RGluZ1NoZW5nMTIz@localhost:6379"));
    }

    @Override
    protected Agent createAgent(Context ctx, ObjectNode params) {
        var llm = new OpenAI("qwen-plus", new HashMap<>(), System.getenv("OPENAI_API_KEY"),
                "https://dashscope.aliyuncs.com/compatible-mode/v1", 3);
        var provider = new FixedLlmProvider(llm);
        var agent = new Agent(UUID.randomUUID().toString(), "test transport agent", provider, null).run();
        return agent;
    }

    @Override
    protected List<Agent> listAgent(Context ctx, ObjectNode params) {
        return new ArrayList<>();
    }

    @Override
    protected Agent removeAgent(Context ctx, ObjectNode params) {
        return null;
    }
}