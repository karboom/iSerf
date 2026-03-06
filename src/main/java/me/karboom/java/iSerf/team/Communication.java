package me.karboom.java.iSerf.team;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.karboom.java.iSerf.agent.Message;

import java.util.List;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class Communication {
    public String agentId;
    public List<String> mentions;
    public Message message;
}
