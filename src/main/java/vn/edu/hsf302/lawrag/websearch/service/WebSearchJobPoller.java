package vn.edu.hsf302.lawrag.websearch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the durable queue when the web-search feature is enabled. */
@Component
@ConditionalOnExpression("${app.websearch.enabled:false} && ${app.websearch.scheduler:true}")
public class WebSearchJobPoller {

    private static final Logger log = LoggerFactory.getLogger(WebSearchJobPoller.class);

    private final WebSearchWorker worker;

    public WebSearchJobPoller(WebSearchWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.websearch.poll-interval-ms:1000}")
    public void poll() {
        try {
            while (worker.runOnce()) {
                // Drain ready work; the claim cap still limits concurrent crawls.
            }
        } catch (RuntimeException exception) {
            log.error("Web search poller failed", exception);
        }
    }
}
