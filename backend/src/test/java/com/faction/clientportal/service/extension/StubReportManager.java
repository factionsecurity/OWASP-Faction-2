package com.faction.clientportal.service.extension;

import com.faction.elements.Assessment;
import com.faction.elements.BaseExtension;
import com.faction.elements.Vulnerability;
import com.faction.elements.utils.Log;
import com.faction.extender.ReportManager;

import java.util.List;

/**
 * A minimal real extension, written exactly as a third-party author would write
 * one: extend {@code BaseExtension}, implement a hook interface, read
 * {@code getConfigs()}, and report through {@code getLogger()}.
 *
 * <p>Packaged into a fixture JAR by the registry test so discovery is exercised
 * end to end — {@code ServiceLoader} over a real {@code URLClassLoader} — rather
 * than against a mock.
 */
public class StubReportManager extends BaseExtension implements ReportManager {

    /** Mirrors the bundled bar-chart extension's placeholder-substitution shape. */
    public static final String TOKEN = "${stub-token}";

    @Override
    public String reportCreate(Assessment assessment, List<Vulnerability> vulns, String reportText) {
        getLogger().addLog(Log.LEVEL.INFO, "reportCreate called");
        if (reportText == null || !reportText.contains(TOKEN)) {
            return reportText;
        }
        String replacement = getConfigs().getOrDefault("Replacement", "DEFAULT");
        return reportText.replace(TOKEN, replacement);
    }
}
