package com.faction.clientportal.edition;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that asserts behaviour only the paid build has.
 *
 * <p>Both editions share one test tree, so most tests run twice — once with the overlay,
 * once without. A handful cannot: they assert that a gated endpoint succeeds, or that
 * bootstrap seeded a role the open source edition deliberately does not create. Those are
 * not broken in the open source build; they are describing a different product.
 *
 * <p>Skipping is the honest answer, and it is better than the alternatives. Duplicating
 * the class per edition doubles the maintenance; asserting "200 or 402 depending" makes
 * every test assert nothing in particular. A skip says plainly: this behaviour belongs to
 * the other build.
 *
 * <p>What the open source build asserts about these same endpoints lives in
 * {@code CommunityFeatureGatesTest}, so the gate itself is never left uncovered.
 *
 * <p>The property is set by the {@code enterprise} Maven profile, so it is on for a normal
 * build and off under {@code mvn -P'!enterprise'}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@EnabledIfSystemProperty(named = "faction.edition.build", matches = "enterprise",
                         disabledReason = "asserts paid-edition behaviour; the open source build gates it")
public @interface EnterpriseOnly {
}
