package com.faction.clientportal.config;

import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.security.RequiresFeature;
import com.faction.clientportal.security.RequiresFeatureInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.Pointcuts;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;

/**
 * Wires {@link RequiresFeature} enforcement.
 *
 * <p>Mirrors how {@code SecurityConfig} registers the {@code @RequiresPermission}
 * advisor, but ordered ahead of the security interceptors: an endpoint that this build
 * does not include should answer 402 whether or not the caller would have been allowed
 * to reach it, and a 403 for a feature that simply is not installed sends the operator
 * chasing role configuration that was never the problem.
 */
@Configuration
public class EditionConfig {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor requiresFeatureAdvisor(ObjectProvider<EditionPolicy> editionPolicy) {
        Pointcut pointcut = Pointcuts.union(
                new AnnotationMatchingPointcut(null, RequiresFeature.class, true),
                new AnnotationMatchingPointcut(RequiresFeature.class, true));
        DefaultPointcutAdvisor advisor =
                new DefaultPointcutAdvisor(pointcut, new RequiresFeatureInterceptor(editionPolicy));
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return advisor;
    }
}
