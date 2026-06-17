package me.karboom.java.iSerf.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class AgentEvent {
    static public class Type {
        final static public String ORGANIZE_MEMORY = "ORGANIZE_MEMORY";
        final static public String MESSAGE = "MESSAGE";
        final static public String RECOVERY = "RECOVERY";
    }

    public String id;
    public Integer priority;
    public String type;
    public AgentMessage message;
}
