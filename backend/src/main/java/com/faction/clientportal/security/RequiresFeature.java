package com.faction.clientportal.security;

import com.faction.clientportal.edition.Feature;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint or type as belonging to a paid capability.
 *
 * <p>Sits alongside {@link RequiresPermission} but answers a different question.
 * {@code @RequiresPermission} asks whether <em>this caller</em> may act;
 * {@code @RequiresFeature} asks whether <em>this build</em> includes the capability at
 * all. Both can apply to the same method, and the feature check runs first — an
 * unlicensed endpoint should say so regardless of who is asking.
 *
 * <p>Enforced by {@link RequiresFeatureInterceptor}. Fails with HTTP 402, not 403.
 *
 * <p>This is a backstop for whole endpoints. A limit that depends on a count belongs in
 * the service layer as an {@code EditionPolicy.requireHeadroom} call instead.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFeature {
    Feature value();
}
