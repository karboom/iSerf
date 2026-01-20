package me.karboom.java.iSlogger.team;

import me.karboom.java.iSlogger.agent.Agent;
import me.karboom.java.iSlogger.llm.text.OpenAI;
import me.karboom.java.iSlogger.tool.Tool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TeamTest {

    private Team team;
    private String apiKey;
    private String url;
    private Map<String, Object> llmConfig;

    @BeforeEach
    public void setUp() {
        // 从环境变量获取 API Key
        apiKey = System.getenv("OPENAI_API_KEY");
        url = System.getenv("OPENAI_API_URL");

        // 如果环境变量未设置，使用测试默认值
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "sk-1d926b2b2c614ca09e3a6d89a9851ea4";
        }
        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        // 配置 LLM 参数
        llmConfig = new HashMap<>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("max_tokens", 1000);

        var llm = new OpenAI("qwen-plus", llmConfig, apiKey, url, 3);

        // 创建5个agent：测试工程师一个、前端两个、后端两个
        var tester = new Agent("tester", "你是一个测试工程师", llm, null) {
        };
        
        var frontend1 = new Agent("frontend1", "你是一个前端工程师", llm, null) {
        };
        
        var frontend2 = new Agent("frontend2", "你是一个前端工程师", llm, null) {
        };
        
        var backend1 = new Agent("backend1", "你是一个后端工程师", llm, null) {
        };
        
        var backend2 = new Agent("backend2", "你是一个后端工程师", llm, null) {
        };

        var leader = new Agent("leader", "你是一个软件团队的管理者", llm, null) {
        };

        // 将所有agent作为构造函数参数传入team中
        var members = List.of(tester, frontend1, frontend2, backend1, backend2);
        team = new Team(leader, members);
    }

    @Test
    public void testSend() throws Exception {
        var receivedMessages = new ArrayList<Message>();
        var receivedEvents = new ArrayList<Event>();

        team.subscribe(message -> {
            if (message.getItem().getIsSegment() == 1) return;
            System.out.println("Received message: " + message);
            receivedMessages.add(message);
        });

        // 订阅事件总线
        team.eventBroadcast.subscribe(event -> {
            System.out.println("Received event: " + event);
            receivedEvents.add(event);
        });

        team.send("做一个留言板网页，采用vue");

        // 等待异步响应
        Thread.sleep(100000);


    }


}
