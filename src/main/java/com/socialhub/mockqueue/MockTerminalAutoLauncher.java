package com.socialhub.mockqueue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * MOCK-only PowerShell viewer; opens on new Excel Run, not at backend startup.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "socialhub.mock-queue", name = {"enabled", "terminal-enabled"}, havingValue = "true")
public class MockTerminalAutoLauncher {
    private final Environment environment;
    private final String terminalLogPath;

    public MockTerminalAutoLauncher(Environment environment,
                                    @Value("${socialhub.mock-queue.terminal-log-file:logs/mock-worker-terminal.log}") String terminalLogPath) {
        this.environment = environment;
        this.terminalLogPath = terminalLogPath;
    }

    // Legacy synthetic seed demo may still open its own viewer at startup.
    // Excel mode (seed=false) MUST NOT auto-open during application startup.
    @EventListener(ApplicationStartedEvent.class)
    public void onStarted() {
        if (environment.getProperty("socialhub.mock-queue.seed", Boolean.class, false)) {
            log.info("[MOCK TERMINAL] Synthetic seed mode: Excel per-run terminal is not launched on startup");
        }
    }

    public void openForExcelRun(UUID runId, int total) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            log.warn("[MOCK TERMINAL] Windows PowerShell only; run={} proceeding without viewer", runId);
            return;
        }
        if (!"MOCK_ONLY".equals(environment.getProperty("socialhub.mock-queue.run-confirm", ""))
                || !"MOCK".equalsIgnoreCase(environment.getProperty("app.facebook.publishing-mode", "MOCK"))
                || environment.getProperty("app.automation.live.enabled", Boolean.class, false)
                || environment.getProperty("app.automation.live.facebook-worker-enabled", Boolean.class, false)
                || environment.getProperty("app.automation.live.threads-worker-enabled", Boolean.class, false)
                || environment.getProperty("app.automation.queue.enabled", Boolean.class, false)
                || environment.getProperty("app.automation.queue.dispatch-enabled", Boolean.class, false)
                || environment.getProperty("app.automation.queue.live-dispatch-enabled", Boolean.class, false)) {
            log.warn("[MOCK TERMINAL] MOCK safety check failed; run={} viewer not opened", runId);
            return;
        }
        Path script = Path.of("tools", "mock-terminal", "mock-worker-terminal.ps1").toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) {
            log.warn("[MOCK TERMINAL] PowerShell viewer script missing: {}", script);
            return;
        }
        Path logFile = Path.of(terminalLogPath).toAbsolutePath().normalize();
        try {
            String command = "start \"SocialHub Excel MOCK " + runId.toString().substring(0, 8)
                    + "\" powershell.exe -NoProfile -ExecutionPolicy Bypass -File \""
                    + script + "\" -LogFile \"" + logFile + "\" -RunId \"" + runId
                    + "\" -Total " + total;
            new ProcessBuilder("cmd.exe", "/c", command)
                    .directory(Path.of("").toAbsolutePath().toFile())
                    .start();
            log.info("[MOCK TERMINAL] Excel viewer launched | run={} total={} log={}", runId, total, logFile);
        } catch (IOException | RuntimeException ex) {
            log.warn("[MOCK TERMINAL] Could not launch viewer | run={} reason={}", runId, ex.toString());
        }
    }
}
