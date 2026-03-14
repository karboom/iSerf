package me.karboom.java.iSerf.rag.category;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import me.karboom.java.iSerf.llm.embedding.Dashscope;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.rag.scene.Memory;
import me.karboom.java.iSerf.rag.store.MilvusVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;

/**
 * Memory 测试类
 */
public class MemoryTest {

    private Memory memory;
    private MilvusClientV2 milvusClient;
    private static final String COLLECTION_NAME = "test_memory_collection_2";

    @BeforeEach
    void setUp() {
        // 初始化 Milvus 客户端
        var uri = System.getenv("MILVUS_URI");
        if (uri == null || uri.isEmpty()) {
            uri = "http://localhost:19530";
        }
        milvusClient = new MilvusClientV2(ConnectConfig.builder().uri(uri).build());

        // 初始化 LLM
        var apiKey = System.getenv("OPENAI_API_KEY");
        var url = System.getenv("OPENAI_API_URL");
        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("max_tokens", 2000);
        llmConfig.put("top_p", 0.9);

        var llm = new OpenAI("qwen-plus", llmConfig, apiKey, url, 1);

        // 初始化 Embedding
        var embedding = new Dashscope("text-embedding-v3", new HashMap<>(), apiKey);

        // 初始化 VectorStore
        var vectorStore = new MilvusVectorStore<Memory.VectorItem>(milvusClient, COLLECTION_NAME){};


        CreateCollectionReq createCollectionReq = CreateCollectionReq.builder()
                .collectionName(COLLECTION_NAME)
                .enableDynamicField(true)
                .dimension(1024)
                .build();
        milvusClient.createCollection(createCollectionReq);


        // 初始化 Memory
        memory = new Memory(llm, embedding, vectorStore);
    }

    @Test
    void testUpdate() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var userId = "test_user_001";

            // 构造对话内容
            var messages = List.of(
                Map.of("role", "user", "content", "我叫张三，今年 28 岁，是一名软件工程师，工作于北京。"),
                Map.of("role", "assistant", "content", "很高兴认识你，张三！"),
                Map.of("role", "user", "content", "我喜欢打篮球和游泳，平时喜欢看书和听音乐。"),
                Map.of("role", "user", "content", "我的目标是成为一名技术专家，正在学习人工智能相关的知识。")
            );

            // 调用 update 方法
            memory.update(userId, messages);

            // 验证数据已写入
            var existingMemories = memory.vectorStore.mixedSearch(Map.of("userId", userId));
            assertNotNull(existingMemories);
            assertTrue(existingMemories.size() > 0, "应该提取出记忆数据");

            System.out.println("提取的记忆数量：" + existingMemories.size());
            for (var item : existingMemories) {
                System.out.println("[" + item.getCategory() + "] " + item.getContent());
            }
        });
    }

    @Test
    @DisplayName("测试记忆匹配功能")
    void testMatch() {
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            var userId = "test_user_match_001";

            // 1. 先存储记忆数据（用户口吻）
            var messages = List.of(
                Map.of("role", "user", "content", "我叫张三，今年 28 岁，是一名软件工程师，工作于北京。"),
                Map.of("role", "assistant", "content", "很高兴认识你，张三！"),
                Map.of("role", "user", "content", "我喜欢打篮球和游泳，平时喜欢看书和听音乐。"),
                Map.of("role", "user", "content", "我的目标是成为一名技术专家，正在学习人工智能相关的知识。")
            );
            memory.update(userId, messages);

            // 2. 验证记忆已存储
            var existingMemories = memory.vectorStore.mixedSearch(Map.of("userId", userId));
            assertNotNull(existingMemories);
            assertTrue(existingMemories.size() > 0, "应该先存储记忆数据");
            System.out.println("已存储的记忆数量：" + existingMemories.size());

            // 3. 测试匹配 - 间接查询职业背景
            var matchResults = memory.match(userId, "最近想跳槽，有技术专家可以推荐吗？");
            assertNotNull(matchResults, "匹配结果不应为空");
            System.out.println("职业背景查询结果数量：" + matchResults.size());
            for (var item : matchResults) {
                System.out.println("[匹配] [" + item.getCategory() + "] " + item.getContent());
            }

            // 4. 测试匹配 - 间接查询兴趣爱好
            var hobbyResults = memory.match(userId, "周末有什么活动可以推荐？");
            assertNotNull(hobbyResults, "爱好查询结果不应为空");
            System.out.println("爱好相关查询结果数量：" + hobbyResults.size());
            for (var item : hobbyResults) {
                System.out.println("[匹配] [" + item.getCategory() + "] " + item.getContent());
            }

            // 5. 测试匹配 - 间接查询学习目标
            var goalResults = memory.match(userId, "想学点新东西，有什么方向推荐？");
            assertNotNull(goalResults, "目标查询结果不应为空");
            System.out.println("学习目标查询结果数量：" + goalResults.size());
            for (var item : goalResults) {
                System.out.println("[匹配] [" + item.getCategory() + "] " + item.getContent());
            }
        });
    }
}
