package me.karboom.java.iSerf.rag.util;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * 图片树形结构构建器
 * 将 PDF 转图片后，通过视觉 LLM 识别图片内容构建树形结构
 */
@Slf4j
public class PicTreeBuilder {

    private final OpenAI openAI;

    public PicTreeBuilder(OpenAI openAI) {
        this.openAI = openAI;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TreeNode {
        public String title;
        public Integer level;
        public String text;
        public String nodeId;
        public String summary;
        public List<TreeNode> nodes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BuildResult {
        public String docName;
        public String docDescription;
        public List<TreeNode> structure;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PicInfo {
        public Boolean isToc;

        public List<PicInfoTitle> titles;

        public List<PicInfoContent> contents;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PicInfoTitle {
        public String title;
        public String fatherTitle;
//        public String grandFatherTitle;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PicInfoContent {
        public String title;
        public String text;
        public String summary;
    }

    // ==================== 正则表达式 ====================

    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile(".*_page_(\\d+)\\..*");

    /**
     * PDF 转为图片
     * 按照页码输出文件名
     * 使用 PDFBox 进行转换
     */
    public List<Path> convertPdf(Path pdf, Path outDir) {
        log.debug("convertPdf 开始转换 PDF: {}", pdf);

        try {
            // 创建输出目录
            Files.createDirectories(outDir);

            // 获取 PDF 文件名（不含扩展名）
            var pdfName = pdf.getFileName().toString().replaceFirst("\\.[^.]+$", "");

            // 使用 PDFBox 转换
            try (var document = Loader.loadPDF(pdf.toFile())) {
                var renderer = new PDFRenderer(document);
                var pageCount = document.getPages().getCount();

                log.debug("convertPdf PDF 页数：{}", pageCount);

                var images = new ArrayList<Path>();

                for (var page = 0; page < pageCount; page++) {
                    // 渲染为 BufferedImage，300 DPI
                    var image = renderer.renderImageWithDPI(page, 300, ImageType.RGB);

                    // 生成输出文件名
                    var outputPath = outDir.resolve("%s_page_%s.png".formatted(pdfName, page + 1));

                    // 写入文件
                    ImageIO.write(image, "PNG", outputPath.toFile());
                    images.add(outputPath);

                    log.debug("convertPdf 转换第 {} 页：{}", page + 1, outputPath);
                }

                log.debug("convertPdf 生成 {} 张图片", images.size());

                return images;
            }

        } catch (Exception e) {
            throw new RuntimeException("convertPdf PDF 转图片失败", e);
        }
    }

    /**
     * 解析图片为树形结构
     * 1. 每一页图片通过 llm.query 转为 PicInfo （提示词要求：1. 角标文本用xxx[xxx]形式 2.内嵌图片不开标题 3.无标题就认为标题是 无 4.左右分栏的情况下注意解析顺序）
     * 2. 通过 PicInfo 构建 TreeNode
     */
    public BuildResult parsePics(Path dir) {
        log.debug("parsePics 开始解析目录：{}", dir);

        try {
            // 获取所有图片文件
            var images = new ArrayList<Path>();
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".png") ||
                                p.toString().toLowerCase().endsWith(".jpg") ||
                                p.toString().toLowerCase().endsWith(".jpeg"))
                        .sorted(Comparator.comparingInt(path -> {
                            var matcher = PAGE_NUMBER_PATTERN.matcher(path.getFileName().toString());
                            if (matcher.matches()) {
                                return Integer.parseInt(matcher.group(1));
                            }
                            return 0;
                        }))
                        .forEach(images::add);
            }

            if (images.isEmpty()) {
                throw new RuntimeException("parsePics 目录中没有图片文件：" + dir);
            }

            log.debug("parsePics 找到 {} 张图片", images.size());

            // 并发处理所有图片
            var picInfos = new PicInfo[images.size()];
            var latch = new CountDownLatch(images.size());

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var i = 0; i < images.size(); i++) {
                    var index = i;
                    var image = images.get(index);
                    executor.execute(() -> {
                        try {
                            picInfos[index] = parseImage(image);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await();
            }

            log.debug("parsePics 所有图片解析完成");

            // 构建树形结构
            var treeNodes = buildTree(picInfos);

            // 构建结果
            var docName = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
            var result = BuildResult.builder()
                    .docName(docName)
                    .structure(treeNodes)
                    .build();

            log.debug("parsePics 构建树形结构完成，根节点数：{}", treeNodes.size());

            return result;

        } catch (Exception e) {
            throw new RuntimeException("parsePics 解析图片失败", e);
        }
    }

    /**
     * 解析单张图片为 PicInfo
     */
    public PicInfo parseImage(Path image) {
        log.debug("parseImage 解析图片：{}", image);

        try {
            // 读取图片为 base64
            var imageBytes = Files.readAllBytes(image);
            var base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);
            var dataUrl = "data:image/png;base64," + base64Image;

            // 构建 prompt
            var prompt = """
                    分析这张图片，提取以下信息并以严格的 JSON 格式返回：
                    {
                        "isToc": false,
                        "titles": [
                            {
                                "title": "内容标题",
                                "fatherTitle": "父级标题",
                            }
                        ],
                        "contents": [
                            {
                                "title": "内容标题",
                                "text": "完整的文本内容，保留角标标注如 [1]、[2] 等",
                                "summary": "内容摘要"
                            }
                        ]
                    }

                    注意事项：
                    1. 角标标注如 [1]、[2] 必须保留在文本中
                    2. 图片中的图表、插图等视作内容的一部分
                    3. 如果没有明确标题，title 设为"无"
                    4. 如果是目录页，isToc 设为 true
                    6. 注意识别左右分栏的布局，按正确阅读顺序提取
                    7. 直接返回 JSON，不要包含任何解释文字
                    8. 目录页面不输出 contents，非目录页面不输出titles
                    9. 根据相邻标题的缩进关系确认父标题
                    """;

            var messages = List.of(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.IMAGE)

                    .text(prompt)
                    .files(List.of(dataUrl))
                    .build());

            var output = openAI.query(messages, PicInfo.class);
            var responseText = output.getChoices().get(0).getText();
            
            // 解析 JSON 响应
            var picInfo = JSONUtil.parse(responseText, PicInfo.class);
            

            log.debug("parseImage 解析完成：isToc={}, titles={}, contents={}",
                    picInfo.getIsToc(), picInfo.getTitles().size(), picInfo.getContents().size());

            return picInfo;

        } catch (Exception e) {
            log.error("parseImage 解析图片失败：{}", image, e);
            // 返回空的 PicInfo 继续处理
            return PicInfo.builder()
                    .isToc(false)
                    .titles(new ArrayList<>())
                    .contents(new ArrayList<>())
                    .build();
        }
    }

    /**
     * 从 PicInfo 数组构建树形结构
     * 1. 按照顺序遍历所有info的content，假如title为“无”，那么把内容追加到上一个content。假如它就是第一个，那么内容合并到上一个info的最后一个content
     * 2. 首先遍历所有目录页，利用 fatherTitle 从底向上构建树，当 fatherTitle 指向不存在的节点时，创建虚拟根节点
     * 3. 如果没有目录页，那么遍历所有info的content生成一个树
     * 3. 遍历所有info的content，根据title搜索树节点，如果匹配到复制内容到树节点。如果未匹配，追随它的上一个content或者上一个info的最后一个content对应的树节点，添加为兄弟树节点
     * 4. level字段最后根据树形结构生成
     */
    @SneakyThrows
    private List<TreeNode> buildTree(PicInfo[] picInfos) {
        log.debug("buildTree 开始构建树形结构，picInfos 数量：{}", picInfos.length);

        if (picInfos == null || picInfos.length == 0) {
            return new ArrayList<>();
        }

        // 步骤 1: 处理"无"标题的内容
        // 按顺序遍历所有 info 的所有 content，当遇到 title 为"无"的 content 时：
        // 1. 优先合并到当前 info 的上一个 content（如果存在）
        // 2. 如果当前 info 没有上一个 content（即第一个），则查找上一个 info 的最后一个 content 并合并
        for (var i = 0; i < picInfos.length; i++) {
            var currentInfo = picInfos[i];
            if (currentInfo.getContents() == null) {
                continue;
            }
            
            // 使用索引遍历，避免 ConcurrentModificationException
            for (var j = 0; j < currentInfo.getContents().size(); j++) {
                var content = currentInfo.getContents().get(j);
                if ("无".equals(content.getTitle())) {
                    PicInfoContent targetContent = null;
                    
                    // 优先合并到当前 info 的上一个 content
                    if (j > 0) {
                        targetContent = currentInfo.getContents().get(j - 1);
                    } else {
                        // 如果是第一个 content，查找上一个 info 的最后一个 content
                        for (var k = i - 1; k >= 0; k--) {
                            var prevInfo = picInfos[k];
                            if (prevInfo.getContents() != null && !prevInfo.getContents().isEmpty()) {
                                targetContent = prevInfo.getContents().get(prevInfo.getContents().size() - 1);
                                break;
                            }
                        }
                    }
                    
                    // 如果找到目标 content，合并并删除当前"无"标题 content
                    if (targetContent != null) {
                        targetContent.setText(targetContent.getText() + content.getText());
                        if (content.getSummary() != null) {
                            targetContent.setSummary(targetContent.getSummary() == null ? 
                                    content.getSummary() : 
                                    targetContent.getSummary() + content.getSummary());
                        }
                        currentInfo.getContents().remove(j);
                        j--; // 删除后调整索引
                    }
                }
            }
        }

        // 步骤 2: 构建标题树
        // 使用 Map 来存储 title -> TreeNode 的映射
        var titleToNodeMap = new java.util.HashMap<String, TreeNode>();
        // 存储根节点列表
        var rootNodes = new ArrayList<TreeNode>();

        // 检测是否存在目录页
        var hasToc = false;
        for (var picInfo : picInfos) {
            if (Boolean.TRUE.equals(picInfo.getIsToc())) {
                hasToc = true;
                break;
            }
        }

        if (hasToc) {
            // 有目录页：从目录页构建标题树
            // 收集所有目录页的 titles
            for (var picInfo : picInfos) {
                if (Boolean.TRUE.equals(picInfo.getIsToc()) && picInfo.getTitles() != null) {
                    for (var picInfoTitle : picInfo.getTitles()) {
                        var title = picInfoTitle.getTitle();
                        var fatherTitle = picInfoTitle.getFatherTitle();

                        if (title == null || "无".equals(title)) {
                            continue;
                        }

                        // 如果节点已存在，跳过
                        if (titleToNodeMap.containsKey(title)) {
                            continue;
                        }

                        // 创建新节点
                        var newNode = TreeNode.builder()
                                .title(title)
                                .nodes(new ArrayList<>())
                                .nodeId(DataUtil.getFlakeId())
                                .build();

                        titleToNodeMap.put(title, newNode);

                        // 处理父子关系
                        if (fatherTitle == null || "无".equals(fatherTitle)) {
                            // 没有父标题，作为根节点
                            rootNodes.add(newNode);
                        } else {
                            var fatherNode = titleToNodeMap.get(fatherTitle);
                            if (fatherNode == null) {
                                // 父节点不存在，创建虚拟根节点
                                fatherNode = TreeNode.builder()
                                        .title(fatherTitle)
                                        .nodes(new ArrayList<>())
                                        .nodeId(DataUtil.getFlakeId())
                                        .build();
                                titleToNodeMap.put(fatherTitle, fatherNode);
                                rootNodes.add(fatherNode);
                            }
                            fatherNode.getNodes().add(newNode);
                        }
                    }
                }
            }
        } else {
            // 没有目录页：遍历所有 info 的 content 生成树节点（每个 title 作为一个节点）
            TreeNode lastNode = null;
            for (var picInfo : picInfos) {
                if (picInfo.getContents() != null) {
                    for (var content : picInfo.getContents()) {
                        var title = content.getTitle();
                        if (title == null || "无".equals(title)) {
                            continue;
                        }

                        // 如果节点已存在，跳过
                        if (titleToNodeMap.containsKey(title)) {
                            continue;
                        }

                        // 创建新节点
                        var newNode = TreeNode.builder()
                                .title(title)
                                .nodes(new ArrayList<>())
                                .nodeId(DataUtil.getFlakeId())
                                .build();

                        titleToNodeMap.put(title, newNode);

                        // 如果没有上一个节点，作为根节点
                        if (lastNode == null) {
                            rootNodes.add(newNode);
                        } else {
                            // 将新节点添加为上一个节点的兄弟节点
                            rootNodes.add(newNode);
                        }
                        lastNode = newNode;
                    }
                }
            }
        }

        // 步骤 3: 关联内容到树节点
        // 遍历所有 info 的 content，根据 title 搜索树节点，如果匹配到复制内容到树节点
        // 如果未匹配，追随它的上一个 content 或者上一个 info 的最后一个 content 对应的树节点，添加为兄弟树节点
        TreeNode lastMatchedNode = null;
        for (var picInfo : picInfos) {
            if (!Boolean.TRUE.equals(picInfo.getIsToc()) && picInfo.getContents() != null) {
                for (var content : picInfo.getContents()) {
                    var title = content.getTitle();
                    TreeNode targetNode = null;
                    
                    // 首先尝试根据 title 查找树节点
                    if (title != null && !"无".equals(title)) {
                        targetNode = titleToNodeMap.get(title);
                    }
                    
                    // 如果未匹配到，追随上一个匹配的树节点，添加为兄弟节点
                    if (targetNode == null && lastMatchedNode != null) {
                        // 创建新节点作为 lastMatchedNode 的兄弟节点
                        targetNode = TreeNode.builder()
                                .title(title)
                                .text(content.getText())
                                .summary(content.getSummary())
                                .nodes(new ArrayList<>())
                                .nodeId(DataUtil.getFlakeId())
                                .build();
                        
                        // 找到 lastMatchedNode 的父节点，将新节点添加为兄弟节点
                        var parentNode = findParentNode(rootNodes, lastMatchedNode);
                        if (parentNode != null) {
                            parentNode.getNodes().add(targetNode);
                        } else {
                            // lastMatchedNode 是根节点，将新节点也添加为根节点
                            rootNodes.add(targetNode);
                        }
                        lastMatchedNode = targetNode;
                        continue;
                    }
                    
                    // 复制内容到目标树节点
                    if (targetNode != null) {
                        targetNode.setText(content.getText());
                        targetNode.setSummary(content.getSummary());
                        lastMatchedNode = targetNode;
                    }
                }
            }
        }

        // 步骤 4: 生成 level 字段
        setLevelRecursive(rootNodes, 1);

        log.debug("buildTree 树形结构构建完成，根节点数：{}", rootNodes.size());

        return rootNodes;
    }

    /**
     * 递归设置 level 字段
     */
    private void setLevelRecursive(List<TreeNode> nodes, int level) {
        if (nodes == null) {
            return;
        }
        for (var node : nodes) {
            node.setLevel(level);
            if (node.getNodes() != null) {
                setLevelRecursive(node.getNodes(), level + 1);
            }
        }
    }

    /**
     * 查找节点在树中的父节点
     */
    private TreeNode findParentNode(List<TreeNode> rootNodes, TreeNode targetNode) {
        for (var node : rootNodes) {
            if (node == targetNode) {
                return null; // 目标是根节点
            }
            if (node.getNodes() != null) {
                if (node.getNodes().contains(targetNode)) {
                    return node;
                }
                var result = findParentNode(node.getNodes(), targetNode);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }



    // ==================== 工具方法 ====================

    /**
     * 打印目录结构
     */
    public void printToc(List<TreeNode> treeStructure) {
        printTocRecursive(treeStructure, 0);
    }

    private void printTocRecursive(List<TreeNode> nodes, int depth) {
        for (var node : nodes) {
            var indent = "  ".repeat(depth);
            System.out.println(indent + "├── " + node.getTitle() + " (L" + node.getLevel() + ")");
            if (node.getNodes() != null && !node.getNodes().isEmpty()) {
                printTocRecursive(node.getNodes(), depth + 1);
            }
        }
    }

}