package me.karboom.java.iSlogger.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.karboom.java.iSlogger.memory.Item;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class Event {
    static public class Type {
        final static public String ORGANIZE_MEMORY = "ORGANIZE_MEMORY";
        final static public String MESSAGE = "MESSAGE";
    }

    public Integer priority;
    public String type;
    public Item item;
}
