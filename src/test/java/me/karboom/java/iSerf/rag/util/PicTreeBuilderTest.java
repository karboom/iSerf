package me.karboom.java.iSerf.rag.util;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.llm.text.OpenAITest;
import me.karboom.java.iSerf.util.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PicTreeBuilder 测试类
 */
@Slf4j
class PicTreeBuilderTest {

    private PicTreeBuilder picTreeBuilder;
    private OpenAI openAI;

    @BeforeEach
    void setUp() {
        // 初始化 OpenAI，使用 qwen3.5-plus 模型
        var openAITest = new OpenAITest();
        openAI = openAITest.getLlm("qwen3.5-plus");

        // 创建 PicTreeBuilder
        picTreeBuilder = new PicTreeBuilder(openAI);
    }

    /**
     * 测试 parseImage 方法
     * 解析单张图片为 PicInfo
     */
    @Test
    void testParseImage() {
        assertTimeoutPreemptively(Duration.ofMinutes(10), () -> {
            // 准备测试图片文件
            var imagePath = Path.of("src/test/resources/rag/user-guide_page_2.png");

            // 调用 parseImage 方法（通过反射，因为它是 private 方法）
            var parseImageMethod = PicTreeBuilder.class.getDeclaredMethod("parseImage", Path.class);
            parseImageMethod.setAccessible(true);
            var picInfo = (PicTreeBuilder.PicInfo) parseImageMethod.invoke(picTreeBuilder, imagePath);

            // 验证结果
            assertNotNull(picInfo, "PicInfo 不应为空");
            assertNotNull(picInfo.getIsToc(), "isToc 不应为空");

            // 输出结果
            System.out.println("=== parseImage 测试结果 ===");
            System.out.println("图片路径：" + imagePath);
            System.out.println("是否目录页：" + picInfo.getIsToc());
            System.out.println("标题数量：" + (picInfo.getTitles() != null ? picInfo.getTitles().size() : 0));
            System.out.println("内容数量：" + (picInfo.getContents() != null ? picInfo.getContents().size() : 0));

            if (picInfo.getTitles() != null && !picInfo.getTitles().isEmpty()) {
                System.out.println("标题列表:");
                for (var title : picInfo.getTitles()) {
                    System.out.println("  - title: " + title.getTitle());
                    System.out.println("    fatherTitle: " + title.getFatherTitle());
                }
            }

            if (picInfo.getContents() != null && !picInfo.getContents().isEmpty()) {
                System.out.println("内容列表:");
                for (var content : picInfo.getContents()) {
                    System.out.println("  - title: " + content.getTitle());
                    var textPreview = content.getText() != null ? content.getText().substring(0, Math.min(100, content.getText().length())) : "null";
                    System.out.println("    text: " + textPreview + "...");
                    System.out.println("    summary: " + content.getSummary());
                }
            }

            // 验证 JSON 序列化
            var json = JSONUtil.convert(picInfo);
            assertNotNull(json, "JSON 不应为空");
            System.out.println("JSON 输出：" + JSONUtil.stringify(json));
        });
    }

    /**
     * 测试 parsePics 方法
     * 解析目录中的所有图片并构建树形结构
     */
    @Test
    void testParsePics() {
        assertTimeoutPreemptively(Duration.ofMinutes(30), () -> {
            // 准备测试目录：将 PDF 转换为图片后的目录
            // 使用已有的测试 PDF 文件
            var pdfPath = Path.of("src/test/resources/rag/user-guide.pdf");
            var tempDir = Files.createTempDirectory("pictree-test-");

            tempDir = Path.of("/tmp/picTreeBuilder-test-2178903051220570534/user-guide-images");

            try {
                log.debug("testParsePics 测试目录：{}", tempDir);

                // 调用 parsePics 方法
                var result = picTreeBuilder.parsePics(tempDir);

                // 验证结果
                assertNotNull(result, "BuildResult 不应为空");
                assertNotNull(result.getDocName(), "文档名称不应为空");
                assertNotNull(result.getStructure(), "树形结构不应为空");

                // 输出结果
                System.out.println("=== parsePics 测试结果 ===");
                System.out.println("文档名称：" + result.getDocName());
                System.out.println("根节点数量：" + result.getStructure().size());

                // 打印目录结构
                picTreeBuilder.printToc(result.getStructure());

                // 输出 JSON
                System.out.println("JSON 输出:");
                System.out.println(JSONUtil.stringify(JSONUtil.convert(result)));

            } finally {

            }
        });
    }

    /**
     * 测试 buildTree 方法
     * 利用 fatherTitle 从底向上构建树
     */
    @Test
    void testBuildTree() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            // 测试数据 JSON
            var jsonStr = """
                    {
                        "contents": [],
                        "isToc": true,
                        "titles": [
                            {
                                "fatherTitle": "介绍",
                                "title": "描述"
                            },
                            {
                                "fatherTitle": "介绍",
                                "title": "泵的应用"
                            },
                            {
                                "fatherTitle": "介绍",
                                "title": "运行限制"
                            },
                            {
                                "fatherTitle": "运行限制",
                                "title": "最大进压"
                            },
                            {
                                "fatherTitle": "运行限制",
                                "title": "最大工作压力"
                            },
                            {
                                "fatherTitle": "运行限制",
                                "title": "密封件运行限制"
                            },
                            {
                                "fatherTitle": "密封件运行限制",
                                "title": "机械密封件"
                            },
                            {
                                "fatherTitle": "密封件运行限制",
                                "title": "填料"
                            },
                            {
                                "fatherTitle": "介绍",
                                "title": "水泵的标识"
                            },
                            {
                                "fatherTitle": "介绍",
                                "title": "安全说明"
                            },
                            {
                                "fatherTitle": "介绍",
                                "title": "其它安全说明"
                            },
                            {
                                "fatherTitle": "其它安全说明",
                                "title": "电气安全"
                            },
                            {
                                "fatherTitle": "其它安全说明",
                                "title": "热安全"
                            },
                            {
                                "fatherTitle": "其它安全说明",
                                "title": "机械安全"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "本手册的目的"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "保修"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "接收水泵时的注意事项"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "暂时存放"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "升吊泵时"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "地点"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "重要事项"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "基座"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "底盘设置"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "可选的灌浆操作"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "旋转"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "连轴器校直"
                            },
                            {
                                "fatherTitle": "连轴器校直",
                                "title": "使用直尺和卡尺进行校直"
                            },
                            {
                                "fatherTitle": "连轴器校直",
                                "title": "使用千分表进行校直"
                            },
                            {
                                "fatherTitle": "连轴器校直",
                                "title": "最后校直"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "吸入和排出管道"
                            },
                            {
                                "fatherTitle": "吸入和排出管道",
                                "title": "吸入管道"
                            },
                            {
                                "fatherTitle": "吸入和排出管道",
                                "title": "吸入管道的阀门"
                            },
                            {
                                "fatherTitle": "吸入和排出管道",
                                "title": "出水管道"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "压力计"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "水泵的隔离"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "水泵的密封"
                            },
                            {
                                "fatherTitle": "水泵的密封",
                                "title": "机械密封件"
                            },
                            {
                                "fatherTitle": "水泵的密封",
                                "title": "填料"
                            },
                            {
                                "fatherTitle": "一般说明",
                                "title": "V 型切割泵轮边缘"
                            },
                            {
                                "fatherTitle": "运行",
                                "title": "冲洗"
                            },
                            {
                                "fatherTitle": "运行",
                                "title": "填充 V"
                            },
                            {
                                "fatherTitle": "运行",
                                "title": "启动准备"
                            },
                            {
                                "fatherTitle": "运行",
                                "title": "启动预检查"
                            },
                            {
                                "fatherTitle": "运行",
                                "title": "启动"
                            },
                            {
                                "fatherTitle": "运行",
                                "title": "可选检查内容"
                            },
                            {
                                "fatherTitle": "运行",
                                "title": "防冻"
                            },
                            {
                                "fatherTitle": "运行",
                                "title": "现场测试"
                            },
                            {
                                "fatherTitle": "维护",
                                "title": "一般维护和定期检查"
                            },
                            {
                                "fatherTitle": "维护",
                                "title": "润滑"
                            },
                            {
                                "fatherTitle": "维护",
                                "title": "泵轴承"
                            },
                            {
                                "fatherTitle": "维护",
                                "title": "连轴器"
                            },
                            {
                                "fatherTitle": "维护",
                                "title": "关于密封"
                            },
                            {
                                "fatherTitle": "关于密封",
                                "title": "机械密封件"
                            },
                            {
                                "fatherTitle": "关于密封",
                                "title": "填料（无石棉）"
                            },
                            {
                                "fatherTitle": "关于密封",
                                "title": "泵受水淹损坏后的维护"
                            },
                            {
                                "fatherTitle": "故障检修",
                                "title": "故障检修"
                            },
                            {
                                "fatherTitle": "保护装置",
                                "title": "ANSI/OSHA 连轴器保护装置的移除/安装"
                            },
                            {
                                "fatherTitle": "ANSI/OSHA 连轴器保护装置的移除/安装",
                                "title": "移除"
                            },
                            {
                                "fatherTitle": "ANSI/OSHA 连轴器保护装置的移除/安装",
                                "title": "安装"
                            },
                            {
                                "fatherTitle": "保护装置",
                                "title": "支架保护装置"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "一般拆卸程序"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "关闭"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "移除轴承框架时的拆卸程序－适用于所有泵"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "移除标准机械密封件的拆卸程序"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "移除填料函和填料的拆卸程序"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "移除集装式密封件的拆卸程序"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "移除盖板和轮轴组装体的拆卸程序－适用于所有水泵"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "安装盖板和轮轴组装体的组装程序－适用于所有水泵"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "安装标准机械密封件的组装程序"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "安装填料函和填料的组装程序"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "安装集装式密封件的组装程序"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "安装轴承框架的组装程序－适用于所有水泵"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "一般组装说明"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "改变旋转方向"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "更换标准机械密封件"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "更换集装式密封件"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "更换填料或轴套"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "A. 一般拆卸说明"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "B. 移除填料函和填料的拆卸程序"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "C. 安装填料函和填料的组装程序"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "D. 安装轴承框架的组装程序－适用于所有水泵"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "定购部件"
                            },
                            {
                                "fatherTitle": "维修",
                                "title": "代理服务"
                            }
                        ]
                    }
                    """;

            // 解析 JSON 为 PicInfo
            var picInfo = JSONUtil.parse(jsonStr, PicTreeBuilder.PicInfo.class);

            // 构造 PicInfo 数组
            var picInfos = new PicTreeBuilder.PicInfo[]{picInfo};

            // 调用 buildTree 方法（通过反射）
            var buildTreeMethod = PicTreeBuilder.class.getDeclaredMethod("buildTree", PicTreeBuilder.PicInfo[].class);
            buildTreeMethod.setAccessible(true);
            var treeNodes = (ArrayList<PicTreeBuilder.TreeNode>) buildTreeMethod.invoke(picTreeBuilder, (Object) picInfos);

            // 验证结果
            assertNotNull(treeNodes, "树形结构不应为空");
            assertTrue(treeNodes.size() > 0, "应有至少一个根节点");

            // 输出结果
            System.out.println("=== buildTree 测试结果 ===");
            System.out.println("根节点数量：" + treeNodes.size());

            // 打印目录结构
            picTreeBuilder.printToc(treeNodes);


        });
    }
}