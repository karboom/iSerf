package me.karboom.java.iSerf.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CallCache {
    public String callId;

    public String toolName;

    public String toolVersion;

    public Map<String, Object> params;


}
