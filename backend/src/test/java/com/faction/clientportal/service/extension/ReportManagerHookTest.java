package com.faction.clientportal.service.extension;

import com.faction.clientportal.model.ExtensionLog;
import com.faction.clientportal.repository.ExtensionLogRepository;
import com.faction.elements.utils.Log;
import com.faction.extender.ReportManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExtensionEventService#applyReportManagers}: the path the bundled bar-chart
 * extension runs on — an extension is handed a piece of the report's rich text and
 * may return a rewritten version.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportManagerHookTest {

    @Mock private ExtensionRegistry registry;
    @Mock private ExtensionLogRepository extensionLogRepository;

    private ExtensionEventService events;

    @BeforeEach
    void setUp() {
        events = new ExtensionEventService(
                registry, new ExtensionMapper(), extensionLogRepository,
                null, null, null, null, null, null, null, null, null,
                new SyncTaskExecutor());
    }

    @Test
    void substitutesAPlaceholderInRichText() {
        StubReportManager stub = new StubReportManager();
        stub.setConfigs(new java.util.HashMap<>(java.util.Map.of("Replacement", "<img src='data:...'>")));
        loaded(stub);

        String result = events.applyReportManagers(null, List.of(),
                "<p>Findings by severity:</p>" + StubReportManager.TOKEN);

        assertThat(result).isEqualTo("<p>Findings by severity:</p><img src='data:...'>");
    }

    @Test
    void contentWithoutAPlaceholderSkipsExtensionsEntirely() {
        CountingReportManager counter = new CountingReportManager();
        loaded(counter);

        String content = "<p>Ordinary narrative with no placeholder.</p>";
        assertThat(events.applyReportManagers(null, List.of(), content)).isEqualTo(content);

        // This runs once per rich-text field per finding, so the short-circuit is
        // what keeps report generation from paying for extensions it does not need.
        assertThat(counter.calls).isZero();
    }

    @Test
    void nullContentIsReturnedUnchanged() {
        loaded(new StubReportManager());
        assertThat(events.applyReportManagers(null, List.of(), null)).isNull();
    }

    @Test
    void extensionsAreChainedInOrderEachSeeingThePreviousResult() {
        loaded(new ReplacingReportManager("${a}", "${b}"),
               new ReplacingReportManager("${b}", "final"));

        assertThat(events.applyReportManagers(null, List.of(), "start ${a}"))
                .isEqualTo("start final");
    }

    @Test
    void anExtensionReturningNullLeavesTheTextAlone() {
        loaded(new ReplacingReportManager(null, null), new ReplacingReportManager("${a}", "done"));

        // The extender contract says a null return means "no change" — it must not
        // wipe the report field.
        assertThat(events.applyReportManagers(null, List.of(), "keep ${a}")).isEqualTo("keep done");
    }

    @Test
    void aThrowingExtensionIsContainedAndTheReportStillGenerates() {
        loaded(new ThrowingReportManager(), new ReplacingReportManager("${a}", "survived"));

        // Report generation must not fail because a third-party JAR threw.
        assertThat(events.applyReportManagers(null, List.of(), "text ${a}")).isEqualTo("text survived");
        verify(extensionLogRepository).save(any(ExtensionLog.class));
    }

    @Test
    void extensionLogsArePersistedForTheOperator() {
        // Extensions run off-request, so anything they print to stdout is invisible
        // to the admin who installed them.
        loaded(new StubReportManager());

        events.applyReportManagers(null, List.of(), StubReportManager.TOKEN);

        verify(extensionLogRepository).saveAll(any());
    }

    @Test
    void withNoReportExtensionsInstalledNothingIsTouched() {
        when(registry.isExtended(ExtensionRegistry.EventType.REPORT_MANAGER)).thenReturn(false);

        String content = "text " + StubReportManager.TOKEN;
        assertThat(events.applyReportManagers(null, List.of(), content)).isEqualTo(content);
        verify(extensionLogRepository, never()).saveAll(any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void loaded(ReportManager... managers) {
        List<ExtensionRegistry.LoadedExtension<ReportManager>> loaded = new ArrayList<>();
        for (int i = 0; i < managers.length; i++) {
            loaded.add(new ExtensionRegistry.LoadedExtension<>(
                    "ext-" + i, "Extension " + i, managers[i]));
        }
        when(registry.isExtended(ExtensionRegistry.EventType.REPORT_MANAGER)).thenReturn(true);
        when(registry.<ReportManager>get(ExtensionRegistry.EventType.REPORT_MANAGER)).thenReturn(loaded);
    }

    /** Records whether it was invoked at all. */
    private static class CountingReportManager extends com.faction.elements.BaseExtension
            implements ReportManager {
        int calls;

        @Override
        public String reportCreate(com.faction.elements.Assessment assessment,
                                   List<com.faction.elements.Vulnerability> vulns, String reportText) {
            calls++;
            return reportText;
        }
    }

    /** Substitutes one token, or returns null when constructed with nulls. */
    private static class ReplacingReportManager extends com.faction.elements.BaseExtension
            implements ReportManager {
        private final String from;
        private final String to;

        ReplacingReportManager(String from, String to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public String reportCreate(com.faction.elements.Assessment assessment,
                                   List<com.faction.elements.Vulnerability> vulns, String reportText) {
            if (from == null) return null;
            return reportText == null ? null : reportText.replace(from, to);
        }
    }

    /** Stands in for a misconfigured extension blowing up mid-report. */
    private static class ThrowingReportManager extends com.faction.elements.BaseExtension
            implements ReportManager {
        @Override
        public String reportCreate(com.faction.elements.Assessment assessment,
                                   List<com.faction.elements.Vulnerability> vulns, String reportText) {
            getLogger().addLog(Log.LEVEL.ERROR, "about to fail");
            throw new IllegalStateException("remote service unreachable");
        }
    }
}
