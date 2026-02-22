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
         * 任务完成
         */
        final static public String COMPLETE = "COMPLETE";
    }


    public String id;
    public String type;
    public String desc;
}
