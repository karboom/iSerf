package me.karboom.java.iSerf.rag.scene;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.memory.Item;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 基于 pageindex 的文档索引
 * Todo 建立全局 ID，跨文档搜索，和递归搜索
 */
@Slf4j
public class Document {
    public String dir;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexHit {
        public String thinking;
        public List<String> nodeIds;
    }

    /**
     * 调用 pageindex 命令行（scripts/PageIndex/run_pageindex.py），生成文档索引文件，保存到 dir
     * @param filePath 输入的 pdf 文件
     * @return 索引文件保存位置
     */
    @lombok.SneakyThrows
    public String buildIndex(Path filePath) {
        log.debug("buildIndex filePath: {}", filePath);

        var scriptPath = Paths.get("scripts/PageIndex/run_pageindex.py");
        var pdfName = filePath.getFileName().toString();
        var baseName = pdfName.substring(0, pdfName.lastIndexOf('.'));

        var indexDir = Paths.get(dir != null ? dir : "results");
        Files.createDirectories(indexDir);

        var processBuilder = new ProcessBuilder(
                "/home/karboom/anaconda3/bin/python3",
                scriptPath.toString(),
                "--pdf_path", filePath.toString(),
                "--if-add-node-id", "yes",
                "--model", "qwen3.5-plus",
                "--if-add-node-summary", "yes"
        );
        processBuilder.redirectErrorStream(true);
        
        var env = processBuilder.environment();
        env.put("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        env.put("OPENAI_BASE_URL", System.getenv("OPENAI_BASE_URL"));

        var process = processBuilder.start();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("pageindex output: {}", line);
            }
        }

        var exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("pageindex process failed with exit code: %d".formatted(exitCode));
        }

        var indexFile = indexDir.resolve(baseName + "_structure.json");
        return indexFile.toString();
    }

    /**
     * 根据用户输入，检索文档片段
     * 1. 调用 llm，输入 query + 索引文件，输 IndexHit 出
     * 2. 通过 IndexHit 从 索引文件拉取内容
     * @param query 用户提问
     * @return 匹配到的文档片段
     */
    @SneakyThrows
    public String search(String query, String path) {
        log.debug("search query: {}, path: {}", query, path);

        var indexContent = Files.readString(Paths.get(path));
        var indexJson = JSONUtil.parse(indexContent);

        var llm = new OpenAI(
                "qwen-plus",
                Map.of("temperature", 0.0),
                System.getenv("OPENAI_API_KEY"),
                System.getenv("OPENAI_BASE_URL"),
                3
        );

        var prompt = """
                You are a document retrieval assistant. Given a query and a document index structure, 
                identify which nodes are most relevant to the query.
                
                Query: %s
                
                Document Index:
                %s
                
                """.formatted(query, indexContent);

        var messages = List.of(Item.builder().text(prompt).role(Item.ROLE.USER).type(Item.TYPE.TEXT).build());
        var output = llm.query(messages, Document.IndexHit.class);

        var content = output.getChoices().get(0).getText();
        var hitJson = JSONUtil.parse(content, Document.IndexHit.class);
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
        var idNode = node.path("node_id");
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