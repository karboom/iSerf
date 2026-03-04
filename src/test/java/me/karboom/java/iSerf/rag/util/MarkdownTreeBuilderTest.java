package me.karboom.java.iSerf.rag.util;

import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.rag.structStore.RedisStructStore;
import me.karboom.java.iSerf.rag.util.MarkdownTreeBuilder.BuildOptions;
import me.karboom.java.iSerf.rag.util.MarkdownTreeBuilder.TreeNode;
import me.karboom.java.iSerf.rag.util.MarkdownTreeBuilder.BuildResult;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MarkdownTreeBuilder 测试类
 */
public class MarkdownTreeBuilderTest {

    private MarkdownTreeBuilder builder;
    private RedisStructStore<MarkdownTreeBuilder.MdToTreeTask> structStore;
    private RedisClient redisClient;
    private OpenAI openAI;

    @BeforeEach
    void setUp() {
        redisClient = RedisClient.create("redis://localhost");
        structStore = new RedisStructStore<>(redisClient, "test:task:%s"){};
        
        openAI = new OpenAI("qwen-plus", Map.of("temperature", 0.0), System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_BASE_URL"), 3);
        
        builder = new MarkdownTreeBuilder(structStore, openAI);
    }

    @Test
    void testMdToTree() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            String mdPath = "src/test/resources/rag/2023-annual-report.md";
            
            BuildOptions options = BuildOptions.builder()
                    .ifThinning(false)
                    .minTokenThreshold(100)
                    .ifAddNodeSummary(true)
                    .summaryTokenThreshold(200)
                    .ifAddNodeText(true)
                    .ifAddNodeId(true)
                    .ifAddDocDescription(true)
                    .build();
            
            String taskId = builder.mdToTree(mdPath, options);
            
            assertNotNull(taskId, "任务 ID 不应为空");
            MarkdownTreeBuilder.MdToTreeTask task = structStore.getById(taskId);
            assertNotNull(task, "任务不应为空");
            assertEquals(MarkdownTreeBuilder.MdToTreeTask.Status.COMPLETED, task.getStatus(), "任务应该完成");
            assertNotNull(task.getResult(), "任务结果不应为空");
            
            BuildResult result = task.getResult();
            assertNotNull(result.getDocName(), "文档名称不应为空");
            assertNotNull(result.getStructure(), "树结构不应为空");
            
            System.out.println("文档名称：" + result.getDocName());
            System.out.println("根节点数量：" + result.getStructure().size());
            
            builder.printToc(result.getStructure());
        });
    }

    @Test
    void testMdToTreeWithThinning() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            String mdPath = "src/test/resources/rag/2023-annual-report.md";
            
            BuildOptions options = BuildOptions.builder()
                    .ifThinning(true)
                    .minTokenThreshold(500)
                    .ifAddNodeSummary(false)
                    .summaryTokenThreshold(200)
                    .ifAddNodeText(true)
                    .ifAddNodeId(true)
                    .ifAddDocDescription(false)
                    .build();
            
            String taskId = builder.mdToTree(mdPath, options);
            
            assertNotNull(taskId, "任务 ID 不应为空");
            MarkdownTreeBuilder.MdToTreeTask task = structStore.getById(taskId);
            assertNotNull(task, "任务不应为空");
            assertEquals(MarkdownTreeBuilder.MdToTreeTask.Status.COMPLETED, task.getStatus(), "任务应该完成");
            
            BuildResult result = task.getResult();
            assertNotNull(result, "结果不应为空");
            
            System.out.println("剪枝后根节点数量：" + result.getStructure().size());
        });
    }

    @Test
    void testMdToTreeWithSummary() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            String mdPath = "src/test/resources/rag/2023-annual-report.md";
            
            BuildOptions options = BuildOptions.builder()
                    .ifThinning(false)
                    .minTokenThreshold(100)
                    .ifAddNodeSummary(true)
                    .summaryTokenThreshold(200)
                    .ifAddNodeText(true)
                    .ifAddNodeId(true)
                    .ifAddDocDescription(true)
                    .build();
            
            String taskId = builder.mdToTree(mdPath, options);
            
            assertNotNull(taskId, "任务 ID 不应为空");
            MarkdownTreeBuilder.MdToTreeTask task = structStore.getById(taskId);
            assertNotNull(task, "任务不应为空");
            assertEquals(MarkdownTreeBuilder.MdToTreeTask.Status.COMPLETED, task.getStatus(), "任务应该完成");
            
            BuildResult result = task.getResult();
            assertNotNull(result, "结果不应为空");
            
            verifySummaries(result.getStructure());
        });
    }

    private void verifySummaries(List<TreeNode> nodes) {
        for (TreeNode node : nodes) {
            if (node.getSummary() != null) {
                System.out.println("节点 [" + node.getTitle() + "] 摘要：" + node.getSummary().substring(0, Math.min(node.getSummary().length(), 50)) + "...");
            }
            if (node.getNodes() != null && !node.getNodes().isEmpty()) {
                verifySummaries(node.getNodes());
            }
        }
    }

    @Test
    void testTreeToJson() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            String mdPath = "src/test/resources/rag/2023-annual-report.md";
            
            BuildOptions options = BuildOptions.builder()
                    .ifThinning(false)
                    .minTokenThreshold(100)
                    .ifAddNodeSummary(false)
                    .summaryTokenThreshold(200)
                    .ifAddNodeText(true)
                    .ifAddNodeId(true)
                    .ifAddDocDescription(false)
                    .build();
            
            String taskId = builder.mdToTree(mdPath, options);
            
            assertNotNull(taskId, "任务 ID 不应为空");
            MarkdownTreeBuilder.MdToTreeTask task = structStore.getById(taskId);
            assertNotNull(task, "任务不应为空");
            
            BuildResult result = task.getResult();
            
            ObjectNode jsonNode = builder.treeToJson(result.getStructure());
            
            assertNotNull(jsonNode, "JSON 节点不应为空");
            System.out.println("JSON 输出：" + jsonNode.toString().substring(0, Math.min(jsonNode.toString().length(), 500)) + "...");
        });
    }

    @Test
    void testCleanTreeForOutput() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            String mdPath = "src/test/resources/rag/2023-annual-report.md";
            
            BuildOptions options = BuildOptions.builder()
                    .ifThinning(false)
                    .minTokenThreshold(100)
                    .ifAddNodeSummary(false)
                    .summaryTokenThreshold(200)
                    .ifAddNodeText(true)
                    .ifAddNodeId(true)
                    .ifAddDocDescription(false)
                    .build();
            
            String taskId = builder.mdToTree(mdPath, options);
            
            assertNotNull(taskId, "任务 ID 不应为空");
            MarkdownTreeBuilder.MdToTreeTask task = structStore.getById(taskId);
            assertNotNull(task, "任务不应为空");
            
            BuildResult result = task.getResult();
            
            List<TreeNode> cleanedTree = builder.cleanTreeForOutput(result.getStructure());
            
            assertNotNull(cleanedTree, "清理后的树不应为空");
            System.out.println("清理后根节点数量：" + cleanedTree.size());
        });
    }
}