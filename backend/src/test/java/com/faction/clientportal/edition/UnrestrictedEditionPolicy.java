package com.faction.clientportal.edition;

/**
 * A fully-licensed policy for core tests.
 *
 * <p>Core tests must not reach for {@code EnterpriseEditionPolicy}: it lives in the
 * overlay, and a core test that imports it cannot run in the open source build — which
 * is precisely the build those tests exist to protect. This is the same answer expressed
 * without the dependency.
 */
public class UnrestrictedEditionPolicy implements EditionPolicy {

    @Override
    public Edition edition() {
        return Edition.ENTERPRISE;
    }

    @Override
    public boolean enabled(Feature feature) {
        return true;
    }

    @Override
    public int limit(Quota quota) {
        return Integer.MAX_VALUE;
    }
}
