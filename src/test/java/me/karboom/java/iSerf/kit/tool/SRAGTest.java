package me.karboom.java.iSlogger.kit.tool;

import me.karboom.java.iSlogger.agent.Agent;
import me.karboom.java.iSlogger.llm.text.OpenAITest;
import me.karboom.java.iSlogger.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class SRAGTest {

    private OpenAITest llmTest = new OpenAITest();

    @BeforeEach
    public void setUp() {

    }

    @Test
    public void testMixedSearch() throws InterruptedException {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            // 创建 MixedSearch 工具
            var srag = new SRAG();
            var mixedSearchTool = srag.mixedSearch();

            var tools = new ArrayList<Tool>();
            tools.add(mixedSearchTool);

            var system = """
                    我有一个数据表名称为info，保存了视频每个画面的相关字段，{
                      "frame_id": "字符串，表示当前帧的唯一标识符，例如 'frame_001234'",
                      "timestamp": "字符串，表示该帧在视频中的时间戳，格式如 '00:02:15'",
                      "scene_type": "字符串，描述整体场景类型，例如 '城市街道'、'室内办公室'、'公园'、'高速公路' 等",
                      "objects": [
                        {
                          "category": "字符串，物体类别名称，例如 '人'、'汽车'、'自行车'、'交通灯'",
                          "attributes": {
                            "color": "字符串，主要颜色，例如 '红色'、'蓝色'",
                            "state": "字符串，物体状态，例如 '静止'、'移动中'、'损坏'、'打开'",
                            "...": "其他可选属性，如 '品牌'、'型号'、'朝向' 等"
                          },
                          "bbox": [x1, y1, x2, y2]  // 浮点数列表，表示物体边界框坐标（归一化或像素值）
                        }
                      ],
                      "relations": [
                        {
                          "subject": "字符串，主语对象（如 '人'）",
                          "predicate": "字符串，关系谓词（如 '骑着'、'站在旁边'、'看向'）",
                          "object": "字符串，宾语对象（如 '自行车'、'建筑物'）"
                        }
                      ],
                      "caption": "字符串，对该帧内容的自然语言描述，例如 '一名穿红衣服的人正在公园里骑自行车'",
                      "event_type": "字符串，高层事件类型，用于分类语义，例如 '休闲活动'、'交通事故'、'商业行为'、'紧急事件'",
                      "salience_score": 0.0  // 浮点数（0.0~1.0），表示该帧的显著性或“有趣程度”，可用于开放性查询排序
                    }
                    """;

            // 使用Tablesaw创建表格，第一列是输入，第二列是输出
            var inputs = List.of(
                    "这个视频展示了哪些气候变化的影响？",
                    "这个视频如何演示烹饪食谱？",
                    "解释视频中展示的科学概念",
                    "教育视频中讨论的主要要点是什么？",
                    "向我展示视频中的产品演示",
                    "纪录片视频涵盖了哪些历史事件？",
                    "描述视频中展示的舞蹈动作",
                    "培训视频中演示了哪些安全程序？",
                    "视频如何说明化学反应？",
                    "视频中是否存在红色的汽车？",
                    "视频里的人穿的是什么颜色的衣服？",
                    "视频中的物体有多大？",
                    "视频场景是在室内还是室外？",
                    "视频中的人物有几个？",
                    "视频里的动物是什么品种？",
                    "视频中显示的时间是几点？",
                    "视频背景音乐的风格是什么？",
                    "视频中出现的品牌标志是什么？",
                    "视频中的文字内容有哪些？"
            );

            var inputColumn = StringColumn.create("input", inputs);
            var outputColumn = StringColumn.create("output", new String[inputs.size()]);
            var table = Table.create("mixed_search_test", inputColumn, outputColumn);

            // 创建完成计数器
            var completedCount = new AtomicInteger(0);

            // 第一个循环：创建agent并发送查询
            inputs.forEach(input -> {
                var i = inputs.indexOf(input);
                var llm = llmTest.getLlm();
                var agent = new Agent("srag-agent-" + i, "", llm, tools) {};

                // 使用agent.subscribe订阅响应内容
                agent.subscribe(
                        item -> {
                            if (item.getIsSegment().equals(0)) {
                                outputColumn.set(i, item.getText()); // 更新对应索引的输出
                                System.out.println("Index " + i + " received: " + item);

                                // 增加完成计数
                                completedCount.incrementAndGet();
                            }
                        }
                );

                // 发送查询
                agent.send(system + input);
            });

            // 第二个循环：等待所有输出都填满（基于订阅信号量计数）
            while (completedCount.get() < inputs.size()) {
                Thread.sleep(100); // 短暂休眠避免过度占用CPU
            }

            // 输出表格供检查
            System.out.println(table.print());
        });

    }

    @Test
    public void testBuildIndex() throws InterruptedException {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var srag = new SRAG();
            var buildIndexTool = srag.buildIndex();

            var tools = new ArrayList<Tool>();
            tools.add(buildIndexTool);

            var system = """
                    """;

            var llm = llmTest.getLlm("qwen3-vl-plus");
            var agent = new Agent("build-index-agent", "", llm, tools) {};

            var result = new StringBuilder();
            var finished = new AtomicBoolean(false);

            agent.subscribe(
                    item -> {
                        if (item.getIsSegment().equals(0)) {
                            result.append(item.getText());
                            System.out.println("索引构建结果: " + item);
//                            finished.set(true);
                        }
                    }
            );

            agent.send(system + "我想索引这个视频文件 https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/c000002xh60.hnFM10002.mp4 ， 需要重点关注物品的尺寸、颜色");

            while (!finished.get()) {
                Thread.sleep(100);
            }

            assertNotNull(result.toString(), "索引构建结果不应为空");
            assertTrue(result.toString().length() > 0, "索引构建结果应有内容");
        });
    }
}
