package com.buukle.agent.channel.spi;

/** 统一事件入口。IM / Webhook / 外部系统都走这里。 */
public interface EventIngestPort {

    IngestResult ingest(NormalizedEvent event);
}
