package com.buukle.agent.channel.spi;

/** Unified event entry point. IM / Webhook / external systems all go through here. */
public interface EventIngestPort {

    IngestResult ingest(NormalizedEvent event);
}
