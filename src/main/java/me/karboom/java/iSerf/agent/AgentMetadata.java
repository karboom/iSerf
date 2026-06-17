package me.karboom.java.iSerf.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentMetadata {
    public String id;
    public String orgId;
    public String userId;
    public String type;
    public String teamId;
    public String serverId;
    public Map<String, Object> extra;
}
