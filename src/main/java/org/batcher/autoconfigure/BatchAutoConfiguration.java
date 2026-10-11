package org.batcher.autoconfigure;

import org.batcher.BatchBeanPostProcessor;
import org.batcher.BatchConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnMissingBean(BatchBeanPostProcessor.class)
@Import(BatchConfiguration.class)
public class BatchAutoConfiguration {
}
