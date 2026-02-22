package me.karboom.java.iSerf.team;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    public String id;
    public String desc;

    public String status;
    public String agentId;

    // 上游任务 ID
    public List<String> upstreamIds;
}
