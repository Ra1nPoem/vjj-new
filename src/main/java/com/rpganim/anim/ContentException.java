package com.rpganim.anim;

/** content.json 解析/加载失败（内容问题不应导致游戏崩溃，调用方 catch 后降级并打日志）。 */
public class ContentException extends RuntimeException {
    public ContentException(String message) {
        super(message);
    }

    public ContentException(String message, Throwable cause) {
        super(message, cause);
    }
}
