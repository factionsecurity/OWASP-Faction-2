package com.faction.clientportal.edition;

import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that asserts behaviour only the open source build has.
 *
 * <p>The counterpart to {@link EnterpriseOnly}, and the reason both exist: the overlay
 * re-runs core's whole suite with itself on the classpath, so a test describing what the
 * open source edition does would fail there for the right reason. Between the two markers
 * every edition-specific behaviour is asserted exactly once, in the build that has it.
 *
 * <p>Reach for this whenever skipping an {@code @EnterpriseOnly} test would leave open
 * source behaviour uncovered — a skip that removes the only coverage of a shipped feature
 * is a gap, not a decision.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@DisabledIfSystemProperty(named = "faction.edition.build", matches = "enterprise",
                          disabledReason = "asserts open source behaviour; the overlay lifts it")
public @interface CommunityOnly {
}
