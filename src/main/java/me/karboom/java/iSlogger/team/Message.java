package me.karboom.java.iSlogger.team;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.karboom.java.iSlogger.memory.Item;

import java.util.List;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class Message {
    public String agentId;
    public List<String> mentions;
    public Item item;
}
