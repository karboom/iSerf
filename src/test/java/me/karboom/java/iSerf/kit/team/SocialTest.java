package me.karboom.java.iSerf.kit.team;

import lombok.SneakyThrows;
import me.karboom.java.iSerf.team.Event;
import me.karboom.java.iSerf.team.Task;
import me.karboom.java.iSerf.team.Team;
import me.karboom.java.iSerf.util.DataUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class SocialTest {

    private static final Path WORK_BASE = Path.of("/tmp/iSerf/team/social");

    private Team team;

    @BeforeEach
    @SneakyThrows
    void setUp() {
        team = new Social().text(WORK_BASE);
    }

    // region 团队构建

    @Nested
    class TeamConstruction {

        @Test
        void testLeaderExists() {
            assertNotNull(team.leader);
            assertEquals("leader", team.leader.metadata.getId());
        }

        @Test
        void testMembersCount() {
            assertEquals(5, team.member.size());
        }

        @Test
        void testMemberIds() {
            var ids = team.member.stream().map(m -> m.metadata.getId()).toList();
            assertTrue(ids.containsAll(List.of("topic", "tree", "editor", "validator", "channel")));
        }

        @Test
        void testTeamReferenceSet() {
            assertEquals(team, team.leader.team);
            for (var member : team.member) {
                assertEquals(team, member.team);
            }
        }

        @Test
        void testWorkDirSet() {
            var agentIds = List.of("leader", "topic", "tree", "editor", "validator", "channel");
            var allAgents = new java.util.ArrayList<me.karboom.java.iSerf.agent.Agent>();
            allAgents.add(team.leader);
            allAgents.addAll(team.member);

            for (var agent : allAgents) {
                var expectedDir = WORK_BASE.resolve(agent.metadata.getId());
                assertEquals(expectedDir, agent.workDir,
                        "WorkDir for %s should be %s".formatted(agent.metadata.getId(), expectedDir));
            }
        }
    }

    // endregion

    // region 任务规划

    @Nested
    class TaskPlanning {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @SneakyThrows
        void testLeaderSplitsTaskOnSend() {
            team.send("写一篇200字关于Java入门简介的短文");

            while (team.tasks == null || team.tasks.isEmpty()) {
                Thread.sleep(500);
            }

            assertFalse(team.tasks.isEmpty(), "Leader should split tasks");
            for (var task : team.tasks) {
                assertNotNull(task.getId());
                assertNotNull(task.getDesc());
                assertNotNull(task.getAgentId());
                assertNotNull(task.getStatus());
            }
        }
    }

    // endregion

    // region 任务状态流转

    @Nested
    class TaskStateFlow {

        @Test
        @Timeout(10)
        @SneakyThrows
        void testNoUpstreamTaskGoesDoing() {
            var task = Task.builder()
                    .id(DataUtil.getFlakeId())
                    .desc("test task")
                    .status(Task.STATUS.WAITING)
                    .agentId("non_existent")
                    .upstreamIds(List.of())
                    .build();
            team.tasks = List.of(task);

            team.trigger(Event.builder().type(Event.TYPE.TASK_CHANGE).desc("test").build());

            Thread.sleep(500);
            assertEquals(Task.STATUS.DOING, task.getStatus());
        }

        @Test
        @Timeout(10)
        @SneakyThrows
        void testWaitingTaskStaysWhenUpstreamNotDone() {
            var upstreamTask = Task.builder()
                    .id(DataUtil.getFlakeId())
                    .desc("upstream task")
                    .status(Task.STATUS.WAITING)
                    .agentId("non_existent")
                    .upstreamIds(List.of())
                    .build();
            var downstreamTask = Task.builder()
                    .id(DataUtil.getFlakeId())
                    .desc("dependent task")
                    .status(Task.STATUS.WAITING)
                    .agentId("non_existent")
                    .upstreamIds(List.of(upstreamTask.getId()))
                    .build();
            team.tasks = List.of(upstreamTask, downstreamTask);

            team.trigger(Event.builder().type(Event.TYPE.TASK_CHANGE).desc("test").build());

            Thread.sleep(500);
            assertEquals(Task.STATUS.DOING, upstreamTask.getStatus());
            assertEquals(Task.STATUS.WAITING, downstreamTask.getStatus(), "Should stay WAITING when upstream not DONE");
        }

        @Test
        @Timeout(10)
        @SneakyThrows
        void testAllDoneTriggersComplete() {
            var task = Task.builder()
                    .id(DataUtil.getFlakeId())
                    .desc("done task")
                    .status(Task.STATUS.DONE)
                    .agentId("non_existent")
                    .upstreamIds(List.of())
                    .build();
            team.tasks = List.of(task);

            var completed = new boolean[]{false};
            var disposable = team.eventBroadcast.subscribe(event -> {
                if (Event.TYPE.COMPLETE.equals(event.getType())) {
                    completed[0] = true;
                }
            });

            team.trigger(Event.builder().type(Event.TYPE.TASK_CHANGE).desc("test").build());

            Thread.sleep(500);
            assertTrue(completed[0], "COMPLETE event should fire when all tasks DONE");
            disposable.dispose();
        }

        @Test
        @Timeout(10)
        @SneakyThrows
        void testUpstreamDoneUnblocksDownstream() {
            var upstreamTask = Task.builder()
                    .id(DataUtil.getFlakeId())
                    .desc("upstream task")
                    .status(Task.STATUS.DONE)
                    .agentId("non_existent")
                    .upstreamIds(List.of())
                    .build();
            var downstreamTask = Task.builder()
                    .id(DataUtil.getFlakeId())
                    .desc("dependent task")
                    .status(Task.STATUS.WAITING)
                    .agentId("non_existent")
                    .upstreamIds(List.of(upstreamTask.getId()))
                    .build();
            team.tasks = List.of(upstreamTask, downstreamTask);

            team.trigger(Event.builder().type(Event.TYPE.TASK_CHANGE).desc("test").build());

            Thread.sleep(500);
            assertEquals(Task.STATUS.DOING, downstreamTask.getStatus(), "Should become DOING when all upstream DONE");
        }
    }

    // endregion

    // region 评论反馈

    @Nested
    class CommentFlow {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @SneakyThrows
        void testCommentDispatchedToAgent() {
            var task = Task.builder()
                    .id(DataUtil.getFlakeId())
                    .desc("草稿内容需要修改")
                    .status(Task.STATUS.DOING)
                    .agentId("editor")
                    .result("原始草稿")
                    .upstreamIds(List.of())
                    .build();
            team.tasks = List.of(task);

            team.trigger(Event.builder()
                    .type(Event.TYPE.COMMENT)
                    .taskId(task.getId())
                    .desc("请优化开头部分，增加吸引力")
                    .build());

            Thread.sleep(5000);

            assertNotNull(task.getResult(), "Task result should be updated after comment");

            // 验证 editor 工作目录中产生了文件
            var editorDir = WORK_BASE.resolve("editor");
            if (Files.exists(editorDir)) {
                try (var files = Files.list(editorDir)) {
                    var fileList = files.toList();
                    assertFalse(fileList.isEmpty(), "Editor should produce output files after comment");
                }
            }
        }
    }

    // endregion

    // region 端到端

    @Nested
    class EndToEnd {

        @Test
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        @SneakyThrows
        void testFullWorkflow() {
            team.send("写一篇200字关于Java入门简介的短文");

            var maxWait = 100_000L;
            var interval = 1000L;
            var waited = 0L;
            while (waited < maxWait) {
                Thread.sleep(interval);
                waited += interval;
                if (team.tasks != null && !team.tasks.isEmpty()
                        && team.tasks.stream().allMatch(t -> Task.STATUS.DONE.equals(t.getStatus()))) {
                    break;
                }
            }

            assertNotNull(team.tasks, "Tasks should be created");
            assertFalse(team.tasks.isEmpty(), "Tasks should not be empty");
            for (var task : team.tasks) {
                assertEquals(Task.STATUS.DONE, task.getStatus(),
                        "Task %s should be DONE".formatted(task.getId()));
                assertNotNull(task.getResult(), "Task %s should have result".formatted(task.getId()));
            }

            // 验证工作目录存在
            var agentIds = List.of("topic", "tree", "editor", "validator", "channel");
            for (var id : agentIds) {
                var dir = WORK_BASE.resolve(id);
                assertTrue(Files.exists(dir),
                        "WorkDir should exist for %s: %s".formatted(id, dir));
            }

            // 验证 editor 产出文件
            var editorDir = WORK_BASE.resolve("editor");
            try (var files = Files.list(editorDir)) {
                var editorFiles = files.toList();
                assertFalse(editorFiles.isEmpty(), "Editor should produce output files");
            }

            // 验证 channel 产出最终文件
            var channelDir = WORK_BASE.resolve("channel");
            try (var files = Files.list(channelDir)) {
                var channelFiles = files.toList();
                assertFalse(channelFiles.isEmpty(), "Channel should produce final output files");
            }
        }
    }

    // endregion
}
