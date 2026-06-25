package me.karboom.java.iSerf.server.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 客户端记录：存储 transport 标识和客户端句柄，支持多协议互通
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransportClientRecord {
    /**
     * 所属 transport 的唯一标识
     */
    public String transportId;
    /**
     * transport 特定的客户端句柄（SocketIOClient / mqtt client / etc.）
     */
    public Object clientHandle;
}
