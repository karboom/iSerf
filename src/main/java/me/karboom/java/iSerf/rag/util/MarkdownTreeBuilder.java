package me.karboom.java.iSerf.rag.util;

import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.Encodings;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.rag.bo.markdown.MarkdownBuildOptions;
import me.karboom.java.iSerf.rag.bo.markdown.MarkdownBuildResult;
import me.karboom.java.iSerf.rag.bo.markdown.MarkdownTreeNode;
import me.karboom.java.iSerf.rag.bo.markdown.MdToTreeTask;
import me.karboom.java.iSerf.rag.store.IStore;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文档转树形结构构建器
 */
@Slf4j
public class MarkdownTreeBuilder {

    // ==================== 正则表达式 ====================

    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("^```");

    // ==================== jtokkit ====================

    private static final Encoding ENCODING;
    
    static {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        ENCODING = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    // ==================== 任务存储器 ====================

    private final IStore<MdToTreeTask> structStore;
    private final OpenAI openAI;

    /**
     * 构造函数，传入结构化存储器实现
     */
    public MarkdownTreeBuilder(IStore<MdToTreeTask> structStore, OpenAI openAI) {
        this.structStore = structStore;
        this.openAI = openAI;
    }

    // ==================== 核心方法 ====================

    /**
     * 主入口：Markdown 转树形结构（带进度追踪）
     */
    public String mdToTree(String mdPath, MarkdownBuildOptions options) {

        var taskId = DataUtil.getFlakeId();
        // 如果没有任务 ID，创建临时任务
            MdToTreeTask task = MdToTreeTask.builder()
                    .id(taskId)
                    .mdPath(mdPath)
                    .status(MdToTreeTask.Status.RUNNING)
                    .progress(0.0)
                    .options(options)
                    .build();
            structStore.create(task);

        try {
            log.debug("mdToTree 开始处理文件：{}", mdPath);

            // 更新进度：开始
            updateTaskProgress(taskId, MdToTreeTask.Status.RUNNING, 
                    "INIT", 0.0, "开始处理");

            // 读取文件
            String markdownContent;
            try {
                markdownContent = Files.readString(Paths.get(mdPath));
                updateTaskProgress(taskId, MdToTreeTask.Status.RUNNING,
                        "READING_FILE", 0.1, "读取文件完成");
            } catch (IOException e) {
                throw new RuntimeException("读取 Markdown 文件失败：" + mdPath, e);
            }

            // 提取节点（包含文本内容）
            log.debug("extractNodesFromMarkdown 提取节点...");
            List<MarkdownTreeNode> nodes = extractNodesFromMarkdown(markdownContent);
            updateTaskProgress(taskId, MdToTreeTask.Status.RUNNING,
                    "EXTRACTING_NODES", 0.3, "提取节点完成，共 " + nodes.size() + " 个节点");

            // 可选：树剪枝
            if (Boolean.TRUE.equals(options.getIfThinning())) {
                log.debug("treeThinning 执行树剪枝...");
                updateTaskProgress(taskId, MdToTreeTask.Status.RUNNING,
                        "CALCULATING_TOKENS", 0.4, "计算 Token 计数...");
                updateNodeListWithTextTokenCount(nodes);
                
                updateTaskProgress(taskId, MdToTreeTask.Status.RUNNING,
                        "THINNING_TREE", 0.5, "执行树剪枝...");
                nodes = treeThinningForIndex(nodes, options.getMinTokenThreshold());
                updateTaskProgress(taskId, MdToTreeTask.Status.RUNNING,
                        "THINNING_TREE", 0.6, "树剪枝完成，剩余 " + nodes.size() + " 个节点");
            }


            // 可选：添加节点摘要
            if (Boolean.TRUE.equals(options.getIfAddNodeSummary())) {
                log.debug("generateSummaries 生成节点摘要...");
                updateTaskProgress(taskId, MdToTreeTask.Status.RUNNING,
                        "GENERATING_SUMMARIES", 0.85, "生成节点摘要...");
                generateSummaries(nodes, options.getSummaryTokenThreshold(), options.getModelName());
                updateTaskProgress(taskId, MdToTreeTask.Status.RUNNING,
                        "GENERATING_SUMMARIES", 0.9, "生成节点摘要完成");
            }

            if (!Boolean.TRUE.equals(options.getIfAddNodeText())) {
                removeFieldsFromNodes(nodes, "text");
            }


            // 构建树结构
            log.debug("buildTreeFromNodes 构建树结构...");
            List<MarkdownTreeNode> treeStructure = buildTreeFromNodes(nodes);
            updateTaskProgress(taskId, MdToTreeTask.Status.RUNNING,
                    "BUILDING_TREE", 0.7, "构建树结构完成");


            // 7. 构建结果
            String docName = Paths.get(mdPath).getFileName().toString().replaceFirst("\\.[^.]+$", "");

            MarkdownBuildResult result = MarkdownBuildResult.builder()
                    .docName(docName)
                    .structure(treeStructure)
                    .build();

            if (Boolean.TRUE.equals(options.getIfAddDocDescription())) {
                log.debug("generateDocDescription 生成文档描述...");
                updateTaskProgress(taskId, MdToTreeTask.Status.RUNNING,
                        "GENERATING_DESCRIPTION", 0.95, "生成文档描述...");
                String docDescription = generateDocDescription(treeStructure, options.getModelName());
                result.setDocDescription(docDescription);
            }

            // 完成任务
            updateTaskProgress(taskId, MdToTreeTask.Status.COMPLETED,
                    "COMPLETED", 1.0, "处理完成");
            
            // 更新任务结果
            MdToTreeTask completedTask = structStore.getById(taskId);
            if (completedTask != null) {
                completedTask.setResult(result);
                structStore.updateById(taskId, completedTask);
            }

            return taskId;

        } catch (Exception e) {
            log.error("mdToTree 处理失败：{}", taskId, e);
            updateTaskProgress(taskId, MdToTreeTask.Status.FAILED,
                    "ERROR", 0.0, "处理失败：" + e.getMessage());
            MdToTreeTask failedTask = structStore.getById(taskId);
            if (failedTask != null) {
                failedTask.setErrorMessage(e.getMessage());
                structStore.updateById(taskId, failedTask);
            }
            throw e;
        }
    }

    /**
     * 更新任务进度
     */
    private void updateTaskProgress(String taskId, String status, 
            String stage, double progress, String message) {
        MdToTreeTask task = structStore.getById(taskId);
        if (task != null) {
            task.setStatus(status);
            task.setStage(stage);
            task.setProgress(progress);
            task.setMessage(message);
            structStore.updateById(taskId, task);
        }
        log.debug("updateTaskProgress 任务进度更新：{} - {} - {} - {:.1f}%", 
                taskId, stage, message, progress * 100);
    }

    /**
     * 从 Markdown 提取节点列表（包含文本内容和节点 ID）
     */
    private List<MarkdownTreeNode> extractNodesFromMarkdown(String content) {
        List<MarkdownTreeNode> nodeList = new ArrayList<>();
        List<String> lines = List.of(content.split("\n", -1));
        boolean inCodeBlock = false;
        int nodeCounter = 1;

        // 第一遍：提取所有标题节点
        for (int lineNum = 0; lineNum < lines.size(); lineNum++) {
            String line = lines.get(lineNum);
            String strippedLine = line.trim();

            // 检查代码块分隔符
            if (CODE_BLOCK_PATTERN.matcher(strippedLine).matches()) {
                inCodeBlock = !inCodeBlock;
                continue;
            }

            // 跳过空行
            if (strippedLine.isEmpty()) {
                continue;
            }

            // 只在非代码块中查找标题
            if (!inCodeBlock) {
                Matcher match = HEADER_PATTERN.matcher(strippedLine);
                if (match.find()) {
                    String title = match.group(2).trim();
                    int level = match.group(1).length();

                    MarkdownTreeNode node = MarkdownTreeNode.builder()
                            .title(title)
                            .lineNum(lineNum + 1)
                            .level(level)
                            .nodeId(String.format("%04d", nodeCounter++))
                            .build();
                    nodeList.add(node);
                }
            }
        }

        // 第二遍：提取节点文本内容
        for (int i = 0; i < nodeList.size(); i++) {
            MarkdownTreeNode node = nodeList.get(i);
            int startLine = node.getLineNum() - 1;
            int endLine = (i + 1 < nodeList.size()) ? nodeList.get(i + 1).getLineNum() - 1 : lines.size();

            String text = String.join("\n", lines.subList(startLine, endLine)).trim();
            node.setText(text);
        }

        return nodeList;
    }

    /**
     * 查找所有子节点索引
     */
    private List<Integer> findAllChildren(int parentIndex, int parentLevel, List<MarkdownTreeNode> nodes) {
        List<Integer> childrenIndices = new ArrayList<>();

        for (int i = parentIndex + 1; i < nodes.size(); i++) {
            int currentLevel = nodes.get(i).getLevel();

            if (currentLevel <= parentLevel) {
                break;
            }

            childrenIndices.add(i);
        }

        return childrenIndices;
    }

    /**
     * 更新节点 token 计数（包含子孙节点）
     */
    private void updateNodeListWithTextTokenCount(List<MarkdownTreeNode> nodes) {
        // 从后向前遍历，确保子节点先被处理
        for (int i = nodes.size() - 1; i >= 0; i--) {
            MarkdownTreeNode currentNode = nodes.get(i);
            int currentLevel = currentNode.getLevel();

            String nodeText = currentNode.getText() != null ? currentNode.getText() : "";
            String totalText = nodeText;

            List<Integer> childrenIndices = findAllChildren(i, currentLevel, nodes);
            for (int childIndex : childrenIndices) {
                String childText = nodes.get(childIndex).getText();
                if (childText != null && !childText.isEmpty()) {
                    totalText += "\n" + childText;
                }
            }

            currentNode.setTextTokenCount(countTokens(totalText));
        }
    }

    /**
     * 树剪枝：合并小节点
     */
    private List<MarkdownTreeNode> treeThinningForIndex(List<MarkdownTreeNode> nodes, int minToken) {
        List<MarkdownTreeNode> result = new ArrayList<>(nodes);
        List<Integer> nodesToRemove = new ArrayList<>();

        for (int i = result.size() - 1; i >= 0; i--) {
            if (nodesToRemove.contains(i)) {
                continue;
            }

            MarkdownTreeNode currentNode = result.get(i);
            int currentLevel = currentNode.getLevel();
            int totalTokens = currentNode.getTextTokenCount() != null ? currentNode.getTextTokenCount() : 0;

            if (totalTokens < minToken) {
                List<Integer> childrenIndices = findAllChildren(i, currentLevel, result);

                List<String> childrenTexts = new ArrayList<>();
                for (int childIndex : childrenIndices) {
                    if (!nodesToRemove.contains(childIndex)) {
                        String childText = result.get(childIndex).getText();
                        if (childText != null && !childText.trim().isEmpty()) {
                            childrenTexts.add(childText);
                        }
                        nodesToRemove.add(childIndex);
                    }
                }

                if (!childrenTexts.isEmpty()) {
                    String parentText = currentNode.getText() != null ? currentNode.getText() : "";
                    StringBuilder mergedText = new StringBuilder(parentText);

                    for (String childText : childrenTexts) {
                        if (!mergedText.isEmpty() && !mergedText.toString().endsWith("\n")) {
                            mergedText.append("\n\n");
                        }
                        mergedText.append(childText);
                    }

                    currentNode.setText(mergedText.toString());
                    currentNode.setTextTokenCount(countTokens(mergedText.toString()));
                }
            }
        }

        // 删除被标记的节点
        for (int i = result.size() - 1; i >= 0; i--) {
            if (nodesToRemove.contains(i)) {
                result.remove(i);
            }
        }

        return result;
    }

    /**
     * 构建树结构
     */
    private List<MarkdownTreeNode> buildTreeFromNodes(List<MarkdownTreeNode> nodeList) {
        if (nodeList.isEmpty()) {
            return new ArrayList<>();
        }

        List<MarkdownTreeNode> rootNodes = new ArrayList<>();
        List<Object[]> stack = new ArrayList<>();

        for (MarkdownTreeNode node : nodeList) {
            int currentLevel = node.getLevel();

            node.setNodes(new ArrayList<>());

            // 弹出栈中层级大于等于当前层级的节点
            while (!stack.isEmpty() && (int) stack.get(stack.size() - 1)[1] >= currentLevel) {
                stack.remove(stack.size() - 1);
            }

            if (stack.isEmpty()) {
                rootNodes.add(node);
            } else {
                MarkdownTreeNode parentNode = (MarkdownTreeNode) stack.get(stack.size() - 1)[0];
                parentNode.getNodes().add(node);
            }

            stack.add(new Object[]{node, currentLevel});
        }

        return rootNodes;
    }

    /**
     * 生成节点摘要（使用 LLM，虚拟线程并发）
     */
    private void generateSummaries(List<MarkdownTreeNode> nodeList, Integer summaryTokenThreshold, String modelName) {
        if (summaryTokenThreshold == null) {
            summaryTokenThreshold = 200;
        }
        int threshold = summaryTokenThreshold;
        
        // 收集需要生成摘要的节点
        List<MarkdownTreeNode> nodesToSummarize = new ArrayList<>();
        for (MarkdownTreeNode node : nodeList) {
            String text = node.getText() != null ? node.getText() : "";
            int tokenCount = countTokens(text);
            
            if (tokenCount < threshold) {
                node.setSummary(text);
            } else {
                nodesToSummarize.add(node);
            }
        }
        
        if (nodesToSummarize.isEmpty()) {
            return;
        }
        
        log.debug("generateSummaries 需要生成摘要的节点数：{}", nodesToSummarize.size());
        
        // 使用虚拟线程并发处理
        CountDownLatch latch = new CountDownLatch(nodesToSummarize.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (MarkdownTreeNode node : nodesToSummarize) {
                executor.execute(() -> {
                    try {
                        String summary = generateNodeSummary(node, modelName);
                        node.setSummary(summary);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("generateSummaries 被中断", e);
        }
        
        log.debug("generateSummaries 所有摘要生成完成");
    }

    /**
     * 使用 LLM 生成单个节点摘要
     */
    private String generateNodeSummary(MarkdownTreeNode node, String modelName) {
        var prompt = """
                You are given a part of a document, your task is to generate a description of the partial document about what are main points covered in the partial document.

                Partial Document Text: %s
                
                Directly return the description, do not include any other text.
                """.formatted(node.getText());

        var messages = List.of(AgentMessage.builder()
                .role(AgentMessage.ROLE.USER)
                .type(AgentMessage.TYPE.TEXT)
                .text(prompt)
                .build());

        var output = openAI.query(messages, null);
        var summary = output.getChoices().get(0).text;

        log.debug("generateNodeSummary 生成节点摘要：{} -> {}", node.getTitle(), summary);

        return summary;
    }

    /**
     * 从节点列表中删除指定字段
     */
    private void removeFieldsFromNodes(List<MarkdownTreeNode> nodes, String... fieldNames) {
        for (MarkdownTreeNode node : nodes) {
            for (String fieldName : fieldNames) {
                switch (fieldName) {
                    case "text" -> node.setText(null);
                    case "textTokenCount" -> node.setTextTokenCount(null);
                    case "summary" -> node.setSummary(null);
                }
            }
        }
    }

    /**
     * 生成文档描述（使用 LLM）
     */
    private String generateDocDescription(List<MarkdownTreeNode> treeStructure, String modelName) {
        var cleanStructure = createCleanStructureForDescription(treeStructure);
        
        var prompt = """
                Your are an expert in generating descriptions for a document.
                You are given a structure of a document. Your task is to generate a one-sentence description for the document, which makes it easy to distinguish the document from other documents.
                    
                Document Structure: %s
                
                Directly return the description, do not include any other text.
                """.formatted(JSONUtil.stringify(cleanStructure));

        var messages = List.of(AgentMessage.builder()
                .role(AgentMessage.ROLE.USER)
                .type(AgentMessage.TYPE.TEXT)
                .text(prompt)
                .build());

        var output = openAI.query(messages, null);
        var description = output.getChoices().get(0).text;

        log.debug("generateDocDescription 生成文档描述：{}", description);

        return description;
    }

    /**
     * 创建用于文档描述生成的简洁结构
     */
    private List<Object> createCleanStructureForDescription(List<MarkdownTreeNode> treeStructure) {
        var result = new ArrayList<Object>();
        for (var node : treeStructure) {
            result.add(createCleanNode(node));
        }
        return result;
    }

    private Object createCleanNode(MarkdownTreeNode node) {
        var cleanNode = new java.util.LinkedHashMap<String, Object>();
        cleanNode.put("title", node.getTitle());
        cleanNode.put("nodeId", node.getNodeId());
        if (node.getSummary() != null) {
            cleanNode.put("summary", node.getSummary());
        }
        if (node.getNodes() != null && !node.getNodes().isEmpty()) {
            var childNodes = new ArrayList<Object>();
            for (var child : node.getNodes()) {
                childNodes.add(createCleanNode(child));
            }
            cleanNode.put("nodes", childNodes);
        }
        return cleanNode;
    }

    /**
     * 使用 jtokkit 计算 token 数量
     */
    private int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return ENCODING.encode(text).size();
    }

    // ==================== 工具方法 ====================

    /**
     * 清理树结构用于输出
     */
    public List<MarkdownTreeNode> cleanTreeForOutput(List<MarkdownTreeNode> treeNodes) {
        List<MarkdownTreeNode> cleanedNodes = new ArrayList<>();

        for (MarkdownTreeNode node : treeNodes) {
            MarkdownTreeNode cleanedNode = MarkdownTreeNode.builder()
                    .title(node.getTitle())
                    .nodeId(node.getNodeId())
                    .text(node.getText())
                    .lineNum(node.getLineNum())
                    .level(node.getLevel())
                    .build();

            if (node.getNodes() != null && !node.getNodes().isEmpty()) {
                cleanedNode.setNodes(cleanTreeForOutput(node.getNodes()));
            }

            cleanedNodes.add(cleanedNode);
        }

        return cleanedNodes;
    }

    /**
     * 将树结构转换为 JSON
     */
    public ObjectNode treeToJson(List<MarkdownTreeNode> treeStructure) {
        return JSONUtil.convert(treeStructure);
    }

    /**
     * 打印目录结构
     */
    public void printToc(List<MarkdownTreeNode> treeStructure) {
        printTocRecursive(treeStructure, 0);
    }

    private void printTocRecursive(List<MarkdownTreeNode> nodes, int depth) {
        for (MarkdownTreeNode node : nodes) {
            String indent = "  ".repeat(depth);
            System.out.println(indent + "├── " + node.getTitle() + " (L" + node.getLevel() + ")");
            if (node.getNodes() != null && !node.getNodes().isEmpty()) {
                printTocRecursive(node.getNodes(), depth + 1);
            }
        }
    }
}