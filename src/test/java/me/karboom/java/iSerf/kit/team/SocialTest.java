package me.karboom.java.iSerf.kit.team;

import me.karboom.java.iSerf.team.Task;
import me.karboom.java.iSerf.util.DataUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

public class SocialTest {
    @Test
    public void testText() throws IOException {
        var team = new Social().text();

        // 阶段1: 选题规划
        var id1 = DataUtil.getFlakeId();
        var task1 = Task.builder()
                .id(id1)
                .desc("确定「如何成为高级后端开发」文章的主题方向和受众定位")
                .status(Task.STATUS.WAITING)
                .agentId("topic")
                .upstreamIds(List.of())
                .comments(List.of())
                .build();

        // 阶段2: 大纲架构
        var id2 = DataUtil.getFlakeId();
        var task2 = Task.builder()
                .id(id2)
                .desc("根据选题生成文章大纲结构，包含技术深度、系统设计、性能优化、团队协作、技术视野五个章节")
                .status(Task.STATUS.WAITING)
                .agentId("tree")
                .upstreamIds(List.of(id1))
                .comments(List.of())
                .build();

        // 阶段3: 分段编辑
        var id3 = DataUtil.getFlakeId();
        var task3 = Task.builder()
                .id(id3)
                .desc("撰写引言部分 - 什么是高级后端开发，突出高级与中级的区别")
                .status(Task.STATUS.WAITING)
                .agentId("editor")
                .upstreamIds(List.of(id2))
                .comments(List.of())
                .build();

        var id4 = DataUtil.getFlakeId();
        var task4 = Task.builder()
                .id(id4)
                .desc("撰写技术深度章节 - 源码级理解与JVM调优、数据库优化实战案例")
                .status(Task.STATUS.WAITING)
                .agentId("editor")
                .upstreamIds(List.of(id2))
                .comments(List.of())
                .build();

        var id5 = DataUtil.getFlakeId();
        var task5 = Task.builder()
                .id(id5)
                .desc("撰写系统设计章节 - 高并发架构设计与微服务实践")
                .status(Task.STATUS.WAITING)
                .agentId("editor")
                .upstreamIds(List.of(id2))
                .comments(List.of())
                .build();

        var id6 = DataUtil.getFlakeId();
        var task6 = Task.builder()
                .id(id6)
                .desc("撰写团队协作章节 - 技术管理与沟通技巧")
                .status(Task.STATUS.WAITING)
                .agentId("editor")
                .upstreamIds(List.of(id2))
                .comments(List.of())
                .build();

        var id7 = DataUtil.getFlakeId();
        var task7 = Task.builder()
                .id(id7)
                .desc("撰写技术视野章节 - 技术选型与趋势判断")
                .status(Task.STATUS.WAITING)
                .agentId("editor")
                .upstreamIds(List.of(id2))
                .comments(List.of())
                .build();

        // 阶段4: 分段审稿
        var id8 = DataUtil.getFlakeId();
        var task8 = Task.builder()
                .id(id8)
                .desc("审稿引言部分 - 检查逻辑和吸引力")
                .status(Task.STATUS.WAITING)
                .agentId("validator")
                .upstreamIds(List.of(id3))
                .comments(List.of())
                .build();

        var id9 = DataUtil.getFlakeId();
        var task9 = Task.builder()
                .id(id9)
                .desc("审稿技术深度章节 - 验证技术准确性")
                .status(Task.STATUS.WAITING)
                .agentId("validator")
                .upstreamIds(List.of(id4))
                .comments(List.of())
                .build();

        var id10 = DataUtil.getFlakeId();
        var task10 = Task.builder()
                .id(id10)
                .desc("审稿系统设计章节 - 验证架构设计合理性")
                .status(Task.STATUS.WAITING)
                .agentId("validator")
                .upstreamIds(List.of(id5))
                .comments(List.of())
                .build();

        var id11 = DataUtil.getFlakeId();
        var task11 = Task.builder()
                .id(id11)
                .desc("审稿团队协作章节 - 检查内容实用性")
                .status(Task.STATUS.WAITING)
                .agentId("validator")
                .upstreamIds(List.of(id6))
                .comments(List.of())
                .build();

        var id12 = DataUtil.getFlakeId();
        var task12 = Task.builder()
                .id(id12)
                .desc("审稿技术视野章节 - 验证前瞻性判断")
                .status(Task.STATUS.WAITING)
                .agentId("validator")
                .upstreamIds(List.of(id7))
                .comments(List.of())
                .build();

        // 阶段5: 渠道美化
        var id13 = DataUtil.getFlakeId();
        var task13 = Task.builder()
                .id(id13)
                .desc("排版美化适配公众号格式，增加目录导航和章节分隔线")
                .status(Task.STATUS.WAITING)
                .agentId("channel")
                .upstreamIds(List.of(id8, id9, id10, id11, id12))
                .comments(List.of())
                .build();

        var id14 = DataUtil.getFlakeId();
        var task14 = Task.builder()
                .id(id14)
                .desc("添加封面图和文章摘要")
                .status(Task.STATUS.WAITING)
                .agentId("channel")
                .upstreamIds(List.of(id13))
                .comments(List.of())
                .build();

        team.tasks = List.of(task1, task2, task3, task4, task5, task6, task7, task8, task9, task10, task11, task12, task13, task14);
    }
}
