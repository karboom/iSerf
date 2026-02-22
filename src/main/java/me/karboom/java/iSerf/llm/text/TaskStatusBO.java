package me.karboom.java.iSerf.llm.text;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusBO {
    static public class STATUS {
        static public final String DOING = "DOING";
        static public final String DONE = "DONE";
        static public final String ERROR = "ERROR";
        static public final String EXPIRED = "EXPIRED";
        static public final String CANCELLED = "CANCELLED";
    }

    public String id;
    public String status;
    public String successResultId;
    public String errorResultId;
}
