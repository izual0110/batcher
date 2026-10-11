package org.batcher;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

/** Imported explicitly so component scanning does not enable batching accidentally. */
public class BatchConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static BatchBeanPostProcessor batchBeanPostProcessor() {
        return new BatchBeanPostProcessor();
    }
}
