package me.karboom.java.iSerf.server.BO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message<T> {
    public String msgId;

    public T body;
}
