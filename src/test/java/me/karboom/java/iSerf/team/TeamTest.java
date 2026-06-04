package me.karboom.java.iSerf.team;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.llm.text.OpenAITest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TeamTest {

    private Team team;
    private String apiKey;
    private String url;
    private Map<String, Object> llmConfig;
    private OpenAITest agentTest = new OpenAITest();

    @BeforeEach
    public void setUp() {
        var llm = agentTest.getLlm();
        var llmProvider = new FixedLlmProvider(llm);

        // 创建5个agent：测试工程师一个、前端两个、后端两个
        var tester = new Agent("tester", "你是一个测试工程师", llmProvider, null) {
        };
        
        var frontend1 = new Agent("frontend1", "你是一个前端工程师", llmProvider, null) {
        };
        
        var frontend2 = new Agent("frontend2", "你是一个前端工程师", llmProvider, null) {
        };
        
        var backend1 = new Agent("backend1", "你是一个后端工程师", llmProvider, null) {
        };
        
        var backend2 = new Agent("backend2", "你是一个后端工程师", llmProvider, null) {
        };

        var leader = new Agent("leader", "你是一个软件团队的管理者", llmProvider, null) {
        };

        // 将所有agent作为构造函数参数传入team中
        var members = List.of(tester, frontend1, frontend2, backend1, backend2);
        team = new Team(leader, members);
    }

    @Test
    public void testSend() throws Exception {
        var receivedMessages = new ArrayList<Message>();
        var receivedEvents = new ArrayList<Event>();

        team.subscribe(communication -> {
            if (communication.getMessage().getIsSegment() == 1) return;
            System.out.println("Received communication: " + communication);
            receivedMessages.add(communication);
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
