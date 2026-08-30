package com.faction.clientportal.security;

import com.faction.clientportal.edition.EditionPolicy;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Enforces {@link RequiresFeature} by consulting the {@link EditionPolicy}.
 *
 * <p>A plain AOP Alliance interceptor rather than a Spring Security
 * {@code AuthorizationManager}: the outcome here is not an authorization decision and
 * must not surface as 403. Letting {@link EditionPolicy#require} throw lets the global
 * handler answer 402 with the feature key attached.
 *
 * <p>The policy is resolved lazily through an {@link ObjectProvider} because this
 * interceptor is built inside a {@code static @Bean} advisor, which is instantiated
 * before the rest of the context and cannot take a constructor dependency on a service.
 */
public final class RequiresFeatureInterceptor implements MethodInterceptor {

    private final ObjectProvider<EditionPolicy> editionPolicy;

    public RequiresFeatureInterceptor(ObjectProvider<EditionPolicy> editionPolicy) {
        this.editionPolicy = editionPolicy;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        RequiresFeature annotation = resolveAnnotation(invocation);
        if (annotation != null) {
            editionPolicy.getObject().require(annotation.value());
        }
        return invocation.proceed();
    }

    private RequiresFeature resolveAnnotation(MethodInvocation invocation) {
        RequiresFeature annotation =
                AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), RequiresFeature.class);
        if (annotation != null) {
            return annotation;
        }
        return AnnotatedElementUtils.findMergedAnnotation(
                invocation.getMethod().getDeclaringClass(), RequiresFeature.class);
    }
}
