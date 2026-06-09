package me.karboom.java.iSerf.team;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class Task {
    static public  class STATUS {
        final static public String WAITING = "WAITING";
        final static public String DOING = "DOING";
        final static public String DONE = "DONE";
    }

    @Data
    @AllArgsConstructor
    @Builder
    @NoArgsConstructor
    static public class Comment {
        public String agentId;
        public String userId;
        public String content;
        public OffsetDateTime createTime;
    }

    public String id;
    public String desc;

    public String status;
    public String agentId;

    /**
     * 任务执行结果
     */
    public String result;

    /**
     * 输出目录
     */
    public Path directory;

    // 上游任务 ID
    public List<String> upstreamIds;

    // 评论
    public List<Comment> comments;
}
