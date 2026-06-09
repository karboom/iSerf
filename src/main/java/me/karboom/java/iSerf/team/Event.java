package me.karboom.java.iSerf.team;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class Event {
    static public class TYPE {
        /**
         * 新的目标
         */
        final static public String TARGET = "TARGET";
        /**
         * 任务状态变化
         */
        final static public String TASK_CHANGE = "TASK_CHANGE";
        /**
         * 任务完成 Todo 这个应该可以去掉
         */
        final static public String COMPLETE = "COMPLETE";

        /**
         * 新消息
         */
        final static public String MESSAGE = "MESSAGE";

        /**
         * 任务评论
         */
        final static public String COMMENT = "COMMENT";
    }


    public String id;
    public String type;
    public String desc;
    /**
     * 关联的任务ID（用于 COMMENT 等需要定位具体任务的事件）
     */
    public String taskId;
}
