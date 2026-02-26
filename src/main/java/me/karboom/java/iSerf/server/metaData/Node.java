package me.karboom.java.iSerf.server.metaData;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Node {
    public String id;
    public String ip;
    public Integer port;
}
