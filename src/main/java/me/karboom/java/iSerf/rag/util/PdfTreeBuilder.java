package me.karboom.java.iSerf.rag.util;

import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.Encodings;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.rag.bo.pdf.PdfBuildOptions;
import me.karboom.java.iSerf.rag.bo.pdf.PdfBuildResult;
import me.karboom.java.iSerf.rag.bo.pdf.PdfPage;
import me.karboom.java.iSerf.rag.bo.pdf.PdfPageAnalysis;
import me.karboom.java.iSerf.rag.bo.pdf.PdfSectionStart;
import me.karboom.java.iSerf.rag.bo.pdf.PdfToTreeTask;
import me.karboom.java.iSerf.rag.bo.pdf.PdfTocItem;
import me.karboom.java.iSerf.rag.bo.pdf.PdfTreeNode;
import me.karboom.java.iSerf.rag.store.IStore;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.Loader;

/**
 * PDF 文档转树形结构构建器 - 每页只经过 LLM 一次
 */
@Slf4j
public class PdfTreeBuilder {

    // ==================== 正则表达式 ====================

    private static final Pattern PHYSICAL_INDEX_PATTERN = Pattern.compile("<physical_index_(\\d+)>");

    // ==================== jtokkit ====================

    private static final Encoding ENCODING;
    
    static {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        ENCODING = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    // ==================== 成员变量 ====================

    private final IStore<PdfToTreeTask> structStore;
    private final OpenAI openAI;

    /**
     * 构造函数，传入结构化存储器实现
     */
    public PdfTreeBuilder(IStore<PdfToTreeTask> structStore, OpenAI openAI) {
        this.structStore = structStore;
        this.openAI = openAI;
    }

    // ==================== 核心方法 ====================

    /**
     * 主入口：PDF 转树形结构（每页只经过 LLM 一次）
     * @param pdfPath PDF 文件路径
     * @param options 构建选项
     * @return 任务 ID
     */
    public String pdfToTree(String pdfPath, PdfBuildOptions options) {
        String taskId = DataUtil.getFlakeId();
        
        PdfToTreeTask task = PdfToTreeTask.builder()
                .id(taskId)
                .status(PdfToTreeTask.Status.RUNNING)
                .progress(0.0)
                .options(options)
                .build();
        structStore.create(task);

        try {
            log.debug("pdfToTree 开始处理 PDF: {}", pdfPath);

            // 1. 准备页面数据
            updateTaskProgress(taskId, PdfToTreeTask.Status.RUNNING,
                    "PREPARING_PAGES", 0.05, "准备页面数据...");
            List<PdfPage> pages = preparePagesFromPdf(pdfPath);

            // 2. 并行调用 LLM 分析所有页面（每页只经过一次 LLM）
            updateTaskProgress(taskId, PdfToTreeTask.Status.RUNNING,
                    "ANALYZING_PAGES", 0.1, "LLM 分析页面...");
            analyzePagesParallel(pages, options.getModelName());

            // 3. 合并目录项
            updateTaskProgress(taskId, PdfToTreeTask.Status.RUNNING,
                    "MERGING_TOC_ITEMS", 0.5, "合并目录项...");
            List<PdfTocItem> allTocItems = mergeTocItems(pages);

            // 4. 收集章节起始信息
            updateTaskProgress(taskId, PdfToTreeTask.Status.RUNNING,
                    "COLLECTING_SECTION_STARTS", 0.6, "收集章节起始信息...");
            List<PdfSectionStart> allSectionStarts = collectSectionStarts(pages);

            // 5. 匹配物理页码到目录项
            updateTaskProgress(taskId, PdfToTreeTask.Status.RUNNING,
                    "MATCHING_PHYSICAL_INDICES", 0.7, "匹配物理页码...");
            matchPhysicalIndicesToToc(allTocItems, allSectionStarts);

            // 6. 构建树结构
            updateTaskProgress(taskId, PdfToTreeTask.Status.RUNNING,
                    "BUILDING_TREE", 0.8, "构建树结构...");
            List<PdfTreeNode> treeStructure = buildTreeFromTocItems(allTocItems);

            // 7. 可选：添加节点摘要
            if (Boolean.TRUE.equals(options.getIfAddNodeSummary())) {
                log.debug("generateSummaries 生成节点摘要...");
                updateTaskProgress(taskId, PdfToTreeTask.Status.RUNNING,
                        "GENERATING_SUMMARIES", 0.9, "生成节点摘要...");
                generateSummaries(treeStructure, pages, options.getSummaryTokenThreshold(), options.getModelName());
            }

            // 8. 可选：添加节点文本
            if (!Boolean.TRUE.equals(options.getIfAddNodeText())) {
                removeFieldsFromNodes(treeStructure, "text");
            }

            // 9. 可选：生成文档描述
            PdfBuildResult result = PdfBuildResult.builder()
                    .docName("pdf_document")
                    .structure(treeStructure)
                    .build();

            if (Boolean.TRUE.equals(options.getIfAddDocDescription())) {
                log.debug("generateDocDescription 生成文档描述...");
                updateTaskProgress(taskId, PdfToTreeTask.Status.RUNNING,
                        "GENERATING_DESCRIPTION", 0.95, "生成文档描述...");
                String docDescription = generateDocDescription(treeStructure, options.getModelName());
                result.setDocDescription(docDescription);
            }

            // 完成任务
            updateTaskProgress(taskId, PdfToTreeTask.Status.COMPLETED,
                    "COMPLETED", 1.0, "处理完成");
            
            task.setResult(result);
            structStore.updateById(taskId, task);

            return taskId;

        } catch (Exception e) {
            log.error("pdfToTree 处理失败：{}", taskId, e);
            updateTaskProgress(taskId, PdfToTreeTask.Status.FAILED,
                    "ERROR", 0.0, "处理失败：" + e.getMessage());
            throw e;
        }
    }

    /**
     * 更新任务进度
     */
    private void updateTaskProgress(String taskId, String status, 
            String stage, double progress, String message) {
        PdfToTreeTask task = structStore.getById(taskId);
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
     * 从 PDF 文件准备页面数据
     */
    private List<PdfPage> preparePagesFromPdf(String pdfPath) {
        List<PdfPage> pages = new ArrayList<>();
        
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();
            
            for (int i = 1; i <= totalPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String content = stripper.getText(document);
                
                int physicalIndex = i;
                String wrappedContent = "<physical_index_%d>\n%s\n<physical_index_%d>".formatted(
                        physicalIndex, content, physicalIndex);
                
                PdfPage page = PdfPage.builder()
                        .physicalIndex(physicalIndex)
                        .content(wrappedContent)
                        .tokenCount(countTokens(wrappedContent))
                        .build();
                pages.add(page);
            }
        } catch (IOException e) {
            throw new RuntimeException("preparePagesFromPdf 读取 PDF 失败：" + pdfPath, e);
        }
        
        log.debug("preparePagesFromPdf 从 {} 提取了 {} 页", pdfPath, pages.size());
        return pages;
    }

    /**
     * 并行调用 LLM 分析所有页面（每页只经过一次 LLM）
     */
    private void analyzePagesParallel(List<PdfPage> pages, String modelName) {
        int batchSize = 10; // 每批处理的页面数
        int totalBatches = (pages.size() + batchSize - 1) / batchSize;
        
        for (int batchStart = 0; batchStart < pages.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, pages.size());
            List<PdfPage> batch = pages.subList(batchStart, batchEnd);
            
            log.debug("analyzePagesParallel 处理批次 {}/{}", (batchStart / batchSize) + 1, totalBatches);
            
            CountDownLatch latch = new CountDownLatch(batch.size());
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (PdfPage page : batch) {
                    executor.execute(() -> {
                        try {
                            PdfPageAnalysis analysis = analyzeSinglePage(page, modelName);
                            page.setAnalysis(analysis);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("analyzePagesParallel 被中断", e);
            }
        }
        
        log.debug("analyzePagesParallel 所有页面分析完成");
    }

    /**
     * 使用 LLM 分析单个页面
     */
    private PdfPageAnalysis analyzeSinglePage(PdfPage page, String modelName) {
        String prompt = """
                You are an expert in extracting hierarchical tree structure from documents.
                You are given a page from a PDF document. Your task is to:
                
                1. Detect if this page is a table of contents (TOC) page.
                2. If it is a TOC page, extract all TOC items with their structure, title, and page number.
                3. If it is a content page, detect which sections start on this page.
                
                The page content contains tags like <physical_index_X> to indicate the physical page number X.
                
                For structure: use a numeric system like "1", "1.1", "1.2", "2", "2.1", etc.
                If the structure is not clear, use null for the structure field.
                
                Return the result in the following JSON format:
                {
                    "isTocPage": "yes" or "no",
                    "tocItems": [
                        {"structure": "1.1", "title": "Section Title", "page": 5}
                    ],
                    "sectionStarts": [
                        {"structure": "2", "title": "New Section"}
                    ]
                }
                
                Page content:
                %s
                
                Directly return the JSON structure, do not output anything else.
                """.formatted(page.getContent());

        var messages = List.of(AgentMessage.builder()
                .role(AgentMessage.ROLE.USER)
                .type(AgentMessage.TYPE.TEXT)
                .text(prompt)
                .build());

        var output = openAI.query(messages, null);
        var responseText = output.getChoices().get(0).text;
        
        log.debug("analyzeSinglePage LLM 响应：{}", responseText);
        
        try {
            ObjectNode jsonNode = JSONUtil.parse(responseText);
            
            String isTocPage = jsonNode.path("isTocPage").asText("no");
            List<PdfTocItem> tocItems = new ArrayList<>();
            List<PdfSectionStart> sectionStarts = new ArrayList<>();
            
            if (jsonNode.has("tocItems") && !jsonNode.path("tocItems").isMissingNode()) {
                var tocItemsNode = jsonNode.path("tocItems");
                if (tocItemsNode.isArray()) {
                    for (var itemNode : tocItemsNode) {
                        PdfTocItem item = PdfTocItem.builder()
                                .structure(itemNode.path("structure").isTextual() ? itemNode.path("structure").asText() : null)
                                .title(itemNode.path("title").asText())
                                .page(itemNode.path("page").isInt() ? itemNode.path("page").asInt() : null)
                                .build();
                        tocItems.add(item);
                    }
                }
            }
            
            if (jsonNode.has("sectionStarts") && !jsonNode.path("sectionStarts").isMissingNode()) {
                var sectionStartsNode = jsonNode.path("sectionStarts");
                if (sectionStartsNode.isArray()) {
                    for (var sectionNode : sectionStartsNode) {
                        PdfSectionStart section = PdfSectionStart.builder()
                                .structure(sectionNode.path("structure").isTextual() ? sectionNode.path("structure").asText() : null)
                                .title(sectionNode.path("title").asText())
                                .physicalIndex(page.getPhysicalIndex())
                                .build();
                        sectionStarts.add(section);
                    }
                }
            }
            
            return PdfPageAnalysis.builder()
                    .isTocPage(isTocPage)
                    .tocItems(tocItems)
                    .sectionStarts(sectionStarts)
                    .build();
                    
        } catch (Exception e) {
            log.error("analyzeSinglePage 解析 LLM 响应失败", e);
            return PdfPageAnalysis.builder()
                    .isTocPage("no")
                    .tocItems(new ArrayList<>())
                    .sectionStarts(new ArrayList<>())
                    .build();
        }
    }

    /**
     * 合并所有目录项
     */
    private List<PdfTocItem> mergeTocItems(List<PdfPage> pages) {
        List<PdfTocItem> allTocItems = new ArrayList<>();
        
        for (PdfPage page : pages) {
            if (page.getAnalysis() != null && "yes".equals(page.getAnalysis().getIsTocPage())) {
                if (page.getAnalysis().getTocItems() != null) {
                    allTocItems.addAll(page.getAnalysis().getTocItems());
                }
            }
        }
        
        log.debug("mergeTocItems 共提取 {} 个目录项", allTocItems.size());
        return allTocItems;
    }

    /**
     * 收集所有章节起始信息
     */
    private List<PdfSectionStart> collectSectionStarts(List<PdfPage> pages) {
        List<PdfSectionStart> allSectionStarts = new ArrayList<>();
        
        for (PdfPage page : pages) {
            if (page.getAnalysis() != null && page.getAnalysis().getSectionStarts() != null) {
                allSectionStarts.addAll(page.getAnalysis().getSectionStarts());
            }
        }
        
        log.debug("collectSectionStarts 共收集 {} 个章节起始信息", allSectionStarts.size());
        return allSectionStarts;
    }

    /**
     * 匹配物理页码到目录项
     */
    private void matchPhysicalIndicesToToc(List<PdfTocItem> tocItems, List<PdfSectionStart> sectionStarts) {
        // 创建 title 到 physicalIndex 的映射
        Map<String, Integer> titleToPhysicalIndex = new HashMap<>();
        for (PdfSectionStart section : sectionStarts) {
            if (section.getTitle() != null && section.getPhysicalIndex() != null) {
                titleToPhysicalIndex.put(section.getTitle(), section.getPhysicalIndex());
            }
        }
        
        // 为目录项填充 physicalIndex
        for (PdfTocItem item : tocItems) {
            if (item.getTitle() != null && titleToPhysicalIndex.containsKey(item.getTitle())) {
                item.setPhysicalIndex(titleToPhysicalIndex.get(item.getTitle()));
            } else if (item.getPage() != null) {
                // 如果没有直接匹配，尝试用 page 来估算
                // 这里可以根据实际情况调整偏移量计算逻辑
                item.setPhysicalIndex(item.getPage());
            }
        }
        
        // 过滤掉没有 physicalIndex 的项
        tocItems.removeIf(item -> item.getPhysicalIndex() == null);
        
        log.debug("matchPhysicalIndicesToToc 成功匹配 {} 个目录项", tocItems.size());
    }

    /**
     * 从目录项构建树结构
     */
    private List<PdfTreeNode> buildTreeFromTocItems(List<PdfTocItem> tocItems) {
        if (tocItems.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<PdfTreeNode> rootNodes = new ArrayList<>();
        List<Object[]> stack = new ArrayList<>();
        
        for (PdfTocItem item : tocItems) {
            PdfTreeNode node = PdfTreeNode.builder()
                    .title(item.getTitle())
                    .structure(item.getStructure())
                    .physicalIndex(item.getPhysicalIndex())
                    .nodes(new ArrayList<>())
                    .build();
            
            int currentLevel = getLevelFromStructure(item.getStructure());
            
            // 弹出栈中层级大于等于当前层级的节点
            while (!stack.isEmpty() && (int) stack.get(stack.size() - 1)[1] >= currentLevel) {
                stack.remove(stack.size() - 1);
            }
            
            if (stack.isEmpty()) {
                rootNodes.add(node);
            } else {
                PdfTreeNode parentNode = (PdfTreeNode) stack.get(stack.size() - 1)[0];
                parentNode.getNodes().add(node);
            }
            
            stack.add(new Object[]{node, currentLevel});
        }
        
        return rootNodes;
    }

    /**
     * 从结构字符串获取层级深度
     */
    private int getLevelFromStructure(String structure) {
        if (structure == null || structure.isEmpty()) {
            return 1;
        }
        // "1" -> 1, "1.1" -> 2, "1.1.1" -> 3
        return structure.split("\\.").length;
    }

    /**
     * 生成节点摘要（使用 LLM，虚拟线程并发）
     */
    private void generateSummaries(List<PdfTreeNode> nodeList, List<PdfPage> pages, 
            Integer summaryTokenThreshold, String modelName) {
        if (summaryTokenThreshold == null) {
            summaryTokenThreshold = 200;
        }
        int threshold = summaryTokenThreshold;
        
        // 收集需要生成摘要的节点
        List<PdfTreeNode> nodesToSummarize = new ArrayList<>();
        for (PdfTreeNode node : nodeList) {
            String text = getNodeText(node, pages);
            int tokenCount = countTokens(text);
            
            if (tokenCount < threshold) {
                node.setSummary(text);
            } else {
                node.setText(text);
                nodesToSummarize.add(node);
            }
        }
        
        if (nodesToSummarize.isEmpty()) {
            return;
        }
        
        log.debug("generateSummaries 需要生成摘要的节点数：{}", nodesToSummarize.size());
        
        CountDownLatch latch = new CountDownLatch(nodesToSummarize.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (PdfTreeNode node : nodesToSummarize) {
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
     * 获取节点文本内容
     */
    private String getNodeText(PdfTreeNode node, List<PdfPage> pages) {
        if (node.getPhysicalIndex() == null || pages.isEmpty()) {
            return node.getTitle();
        }
        
        // 查找下一个节点的起始页，确定当前节点的内容范围
        int startPage = node.getPhysicalIndex();
        int endPage = findNextPhysicalIndex(node, pages);
        
        StringBuilder text = new StringBuilder();
        for (PdfPage page : pages) {
            if (page.getPhysicalIndex() >= startPage && page.getPhysicalIndex() <= endPage) {
                text.append(page.getContent());
            }
        }
        
        return text.toString();
    }

    /**
     * 查找下一个有 physicalIndex 的节点
     */
    private int findNextPhysicalIndex(PdfTreeNode currentNode, List<PdfPage> pages) {
        // 简单实现：返回最后一页
        return pages.stream().mapToInt(PdfPage::getPhysicalIndex).max().orElse(currentNode.getPhysicalIndex());
    }

    /**
     * 使用 LLM 生成单个节点摘要
     */
    private String generateNodeSummary(PdfTreeNode node, String modelName) {
        String text = node.getText() != null ? node.getText() : "";
        
        var prompt = """
                You are given a part of a document, your task is to generate a description of the partial document about what are main points covered in the partial document.

                Partial Document Text: %s
                
                Directly return the description, do not include any other text.
                """.formatted(text);

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
    private void removeFieldsFromNodes(List<PdfTreeNode> nodes, String... fieldNames) {
        for (PdfTreeNode node : nodes) {
            for (String fieldName : fieldNames) {
                switch (fieldName) {
                    case "text" -> node.setText(null);
                    case "textTokenCount" -> node.setTextTokenCount(null);
                    case "summary" -> node.setSummary(null);
                }
            }
            if (node.getNodes() != null && !node.getNodes().isEmpty()) {
                removeFieldsFromNodes(node.getNodes(), fieldNames);
            }
        }
    }

    /**
     * 生成文档描述（使用 LLM）
     */
    private String generateDocDescription(List<PdfTreeNode> treeStructure, String modelName) {
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
    private List<Object> createCleanStructureForDescription(List<PdfTreeNode> treeStructure) {
        var result = new ArrayList<Object>();
        for (var node : treeStructure) {
            result.add(createCleanNode(node));
        }
        return result;
    }

    private Object createCleanNode(PdfTreeNode node) {
        var cleanNode = new java.util.LinkedHashMap<String, Object>();
        cleanNode.put("title", node.getTitle());
        cleanNode.put("structure", node.getStructure());
        if (node.getPhysicalIndex() != null) {
            cleanNode.put("physicalIndex", node.getPhysicalIndex());
        }
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
     * 将树结构转换为 JSON
     */
    public ObjectNode treeToJson(List<PdfTreeNode> treeStructure) {
        return JSONUtil.convert(treeStructure);
    }

    /**
     * 打印目录结构
     */
    public void printToc(List<PdfTreeNode> treeStructure) {
        printTocRecursive(treeStructure, 0);
    }

    private void printTocRecursive(List<PdfTreeNode> nodes, int depth) {
        for (PdfTreeNode node : nodes) {
            String indent = "  ".repeat(depth);
            String info = node.getTitle();
            if (node.getPhysicalIndex() != null) {
                info += " (P" + node.getPhysicalIndex() + ")";
            }
            System.out.println(indent + "├── " + info);
            if (node.getNodes() != null && !node.getNodes().isEmpty()) {
                printTocRecursive(node.getNodes(), depth + 1);
            }
        }
    }
}