package com.faction.clientportal.edition;

import com.faction.clientportal.security.RequiresFeature;
import com.faction.clientportal.edition.UnrestrictedEditionPolicy;
import com.faction.clientportal.security.RequiresFeatureInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.Pointcuts;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the {@code @RequiresFeature} advice through a real AOP proxy.
 *
 * <p>Invoking the interceptor directly would prove nothing about the pointcut, and the
 * pointcut is the part that can silently stop matching. Building the same advisor the
 * application wires up and calling through the proxy is what catches an annotation that
 * has quietly stopped being enforced.
 */
class RequiresFeatureInterceptorTest {

    /** Method-level annotation, and an un-annotated neighbour that must stay reachable. */
    static class Guarded {
        @RequiresFeature(Feature.SSO)
        String paid() {
            return "ran";
        }

        String free() {
            return "ran";
        }
    }

    /** Type-level annotation: every method inherits the gate. */
    @RequiresFeature(Feature.BRANDING)
    static class WhollyGuarded {
        String anything() {
            return "ran";
        }
    }

    private <T> T proxy(T target, EditionPolicy policy) {
        Pointcut pointcut = Pointcuts.union(
                new AnnotationMatchingPointcut(null, RequiresFeature.class, true),
                new AnnotationMatchingPointcut(RequiresFeature.class, true));
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvisor(new DefaultPointcutAdvisor(
                pointcut, new RequiresFeatureInterceptor(providerOf(policy))));
        @SuppressWarnings("unchecked")
        T proxied = (T) factory.getProxy();
        return proxied;
    }

    private ObjectProvider<EditionPolicy> providerOf(EditionPolicy policy) {
        return new ObjectProvider<>() {
            @Override
            public EditionPolicy getObject() {
                return policy;
            }

            @Override
            public EditionPolicy getObject(Object... args) {
                return policy;
            }

            @Override
            public EditionPolicy getIfAvailable() {
                return policy;
            }

            @Override
            public EditionPolicy getIfUnique() {
                return policy;
            }
        };
    }

    @Test
    void blocksAnAnnotatedMethodInTheCommunityEdition() {
        Guarded guarded = proxy(new Guarded(), new CommunityEditionPolicy());

        assertThatThrownBy(guarded::paid)
                .isInstanceOf(FeatureNotLicensedException.class)
                .satisfies(ex -> assertThat(((FeatureNotLicensedException) ex).getFeature())
                        .isEqualTo(Feature.SSO));
    }

    @Test
    void allowsTheSameMethodInTheEnterpriseEdition() {
        Guarded guarded = proxy(new Guarded(), new UnrestrictedEditionPolicy());

        assertThat(guarded.paid()).isEqualTo("ran");
    }

    @Test
    void leavesUnannotatedMethodsAlone() {
        Guarded guarded = proxy(new Guarded(), new CommunityEditionPolicy());

        assertThat(guarded.free()).isEqualTo("ran");
    }

    @Test
    void honoursATypeLevelAnnotation() {
        WhollyGuarded guarded = proxy(new WhollyGuarded(), new CommunityEditionPolicy());

        assertThatThrownBy(guarded::anything)
                .isInstanceOf(FeatureNotLicensedException.class)
                .satisfies(ex -> assertThat(((FeatureNotLicensedException) ex).getFeature())
                        .isEqualTo(Feature.BRANDING));
    }
}
