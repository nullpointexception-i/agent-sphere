package com.buukle.agent.channel.spi;

/**
 * 信道适配器契约。
 *
 * <p>只做协议转换：验签、解析、线程识别、发送；不做业务判断。
 */
public interface ChannelAdapter {

    /** IM_BUILTIN / WEBHOOK / CODE_HOST / MAIL。 */
    String type();

    /**
     * 解析入站请求。实现负责验签与字段校验。
     *
     * @return 规范化事件；无有效事件时返回空列表
     */
    java.util.List<NormalizedEvent> parse(InboundRequest request);

    /** 发送出站消息。 */
    SendResult send(OutboundMessage message);

    /** 更新已发送的消息（如任务卡片状态变更）；默认不支持。 */
    default SendResult update(OutboundMessage message) {
        return SendResult.failed("update not supported by " + type());
    }
}
