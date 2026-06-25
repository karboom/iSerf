package me.karboom.java.iSerf.rag.category;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import me.karboom.java.iSerf.rag.category.Agentic;
import me.karboom.java.iSerf.rag.store.MilvusStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Document 测试类
 */
public class AgenticTest {

    private Agentic agentic;
    private String indexFilePath;

    @BeforeEach
    void setUp() {
        var uri = System.getenv("MILVUS_URI");
        if (uri == null || uri.isEmpty()) {
            uri = "http://localhost:19530";
        }
        var milvusClient = new MilvusClientV2(ConnectConfig.builder().uri(uri).build());
        var structStore = new MilvusStore<>(milvusClient, "test_doc_task");
        agentic = new Agentic(structStore);
    }

    @Test
    void testBuildIndex() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            var mdPath = Paths.get("src/test/resources/rag/2023-annual-report.md");
            
            assertTrue(java.nio.file.Files.exists(mdPath), "Markdown 文件应该存在");
            
            var taskId = agentic.buildIndex(mdPath);
            
            assertNotNull(taskId, "任务 ID 不应为空");
            assertFalse(taskId.isEmpty(), "任务 ID 不应为空字符串");
            
            System.out.println("任务 ID: " + taskId);
            
            var task = agentic.structStore.getById(taskId);
            assertNotNull(task, "任务应该被存储");
            assertNotNull(task.getResult(), "任务结果应该存在");
            
            System.out.println("任务状态：" + task.getStatus());
        });
    }

    @Test
    void testSearch() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            var mdPath = Paths.get("src/test/resources/rag/2023-annual-report.md");
            
            var taskId = agentic.buildIndex(mdPath);
//            var taskId = "2029195928765407232";
            assertNotNull(taskId, "任务 ID 不应为空");
            
            var query = "What is the company's revenue in 2023?";
            var result = agentic.search(query, taskId);
            
            assertNotNull(result, "搜索结果不应为空");
            
            System.out.println("搜索查询：" + query);
            System.out.println("搜索结果：");
            System.out.println(result);
        });
    }
}