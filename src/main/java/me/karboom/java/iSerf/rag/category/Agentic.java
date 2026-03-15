package me.karboom.java.iSerf.rag.category;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Message;
import me.karboom.java.iSerf.llm.text.OpenAI;
import io.lettuce.core.RedisClient;
import me.karboom.java.iSerf.rag.store.IStructStore;
import me.karboom.java.iSerf.rag.store.RedisStructStore;
import me.karboom.java.iSerf.rag.util.MarkdownTreeBuilder;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 基于Agentic模式检索文档
 * Todo 建立全局 ID，跨文档搜索，和递归搜索
 */
@Slf4j
public class Agentic {
    public String dir;
    public RedisClient redisClient;
    public RedisStructStore<MarkdownTreeBuilder.MdToTreeTask> structStore;
    public MarkdownTreeBuilder builder;

    public Agentic(IStructStore structStore) {
        this.builder = new MarkdownTreeBuilder(
            structStore,
            new OpenAI("qwen-plus", Map.of("temperature", 0.0), System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_BASE_URL"), 3)
        );
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexHit {
        public String thinking;
        public List<String> nodeIds;
    }

    /**
     * 调用 MarkdownTreeBuilder 生成文档索引
     * @param filePath 输入的 md 文件
     * @return 任务 ID
     */
    @SneakyThrows
    public String buildIndex(Path filePath) {
        log.debug("buildIndex filePath: {}", filePath);


        var options = MarkdownTreeBuilder.BuildOptions.builder()
                .ifThinning(false)
                .minTokenThreshold(100)
                .ifAddNodeSummary(true)
                .ifAddNodeText(false)
                .summaryTokenThreshold(200)
                .ifAddNodeId(true)
                .ifAddDocDescription(true)
                .build();

        return builder.mdToTree(filePath.toString(), options);
    }

    /**
     * 根据用户输入，检索文档片段
     * 1. 调用 llm，输入 query + 索引文件，输 IndexHit 出
     * 2. 通过 IndexHit 从 索引文件拉取内容
     * @param query 用户提问
     * @param taskId 分析任务的ID
     * @return 匹配到的文档片段
     */
    @SneakyThrows
    public String search(String query, String taskId) {
        log.debug("search query: {}, taskId: {}", query, taskId);

        var task = structStore.getById(taskId);
        if (task == null || task.getResult() == null) {
            return "Task not found or not completed: " + taskId;
        }

        var buildResult = task.getResult();
        var indexJson = JSONUtil.convert(buildResult);

        var llm = new OpenAI(
                "qwen-plus",
                Map.of("temperature", 0.0),
                System.getenv("OPENAI_API_KEY"),
                System.getenv("OPENAI_BASE_URL"),
                3
        );

        var indexContent = indexJson.toString();
        var prompt = """
                You are a document retrieval assistant. Given a query and a document index structure, 
                identify which nodes are most relevant to the query.
                
                Query: %s
                
                Document Index:
                %s
                
                """.formatted(query, indexContent);

        var messages = List.of(Message.builder().text(prompt).role(Message.ROLE.USER).type(Message.TYPE.TEXT).build());
        var output = llm.query(messages, Agentic.IndexHit.class);

        var content = output.getChoices().get(0).getText();
        var hitJson = JSONUtil.parse(content, Agentic.IndexHit.class);
        var nodeIds = hitJson.nodeIds;

        if (nodeIds == null || nodeIds.isEmpty()) {
            return "No relevant content found.";
        }

        var resultBuilder = new StringBuilder();
        resultBuilder.append("Retrieved Content:\n");

        var nodeIdSet = new java.util.HashSet<>(nodeIds);
        var matchedNodes = new java.util.ArrayList<ObjectNode>();
        
        var structureNode = indexJson.path("structure");
        if (!structureNode.isMissingNode() && !structureNode.isNull()) {
            for (int i = 0; i < structureNode.size(); i++) {
                collectMatchingNodes(JSONUtil.convert(structureNode.get(i), ObjectNode.class), nodeIdSet, matchedNodes);
            }
        }
        
        for (var node : matchedNodes) {
            resultBuilder.append(node.toString()).append("\n\n");
        }

        return resultBuilder.toString();
    }

    private void collectMatchingNodes(ObjectNode node, java.util.Set<String> nodeIdSet, List<ObjectNode> result) {
        var idNode = node.path("nodeId");
        if (!idNode.isMissingNode() && !idNode.isNull() && nodeIdSet.contains(idNode.asText())) {
            result.add(node);
        }

        var nodesArray = node.path("nodes");
        if (nodesArray.isMissingNode() || nodesArray.isNull()) {
            return;
        }

        for (int i = 0; i < nodesArray.size(); i++) {
            collectMatchingNodes(JSONUtil.convert(nodesArray.get(i), ObjectNode.class), nodeIdSet, result);
        }
    }
}