/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.game.CrashReportAnalyzer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Extracts diagnostic evidence without allowing a Forge/NeoForge mod inventory to become the crash summary.
@NotNullByDefault
final class SaoCrashSummary {
    /// A log4j timestamp/thread prefix, including nested redaction markers left by the privacy scrubber.
    private static final Pattern LOG_PREFIX = Pattern.compile("^\\s*\\[.*?]\\s+\\[[^\\]]+]:\\s?");
    /// Java throwable headers, including custom exceptions, qualified modules and uncaught-thread prefixes.
    private static final Pattern EXCEPTION = Pattern.compile(
            "^(?:Exception in thread \\\"[^\\\"]+\\\"\\s+)?(?:[\\w.$]+/)?[\\w.$]+(?:Exception|Error|Throwable)(?::.*)?$");
    /// Native fault identifiers in HotSpot's header.
    private static final Pattern NATIVE_FAULT = Pattern.compile("^#\\s+(?:EXCEPTION_[A-Z_]+|SIG[A-Z]+|Internal Error|Out of Memory Error).*");

    /// No instances; parsing has no filesystem or UI side effects.
    private SaoCrashSummary() { }

    /// Description/exception headline, selectable evidence and normalized input for HMCL's rule matcher.
    @NotNullByDefault
    record Summary(String headline, String detail, String diagnostic) { }

    /// Parses the newest embedded report even when Minecraft never printed a crash-location end marker.
    static Summary parse(String text) {
        String normalized = text.lines().map(line -> LOG_PREFIX.matcher(line).replaceFirst(""))
                .collect(java.util.stream.Collectors.joining("\n"));
        int start = normalized.lastIndexOf("---- Minecraft Crash Report ----");
        String diagnostic = start >= 0 ? normalized.substring(start) : normalized;
        // Only report evidence is passed to HMCL: mod table names/versions are not evidence of a culprit.
        diagnostic = beforeInventory(diagnostic);
        @Unmodifiable List<String> lines = diagnostic.lines().toList();
        String description = "";
        String firstException = "";
        String nativeFault = "";
        List<String> stack = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        List<String> nativeFrame = new ArrayList<>();
        Set<String> suspects = new LinkedHashSet<>();
        boolean collectingStack = false;
        boolean head = false;
        boolean suspectedBlock = false;
        int frames = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (description.isEmpty() && trimmed.startsWith("Description:")) description = trimmed;
            if (firstException.isEmpty() && EXCEPTION.matcher(trimmed).matches()) {
                firstException = trimmed;
                stack.add(trimmed);
                collectingStack = true;
                frames = 0;
            } else if (trimmed.startsWith("Caused by:")) {
                stack.add(trimmed);
                collectingStack = true;
                frames = 0;
            } else if (collectingStack && trimmed.startsWith("at ")) {
                if (frames++ < 3) stack.add("    " + trimmed);
            } else if (!trimmed.isEmpty() && !trimmed.matches("\\.\\.\\. \\d+ (?:more|common frames omitted)")) {
                collectingStack = false;
            }
            if (nativeFault.isEmpty() && NATIVE_FAULT.matcher(trimmed).matches()) nativeFault = trimmed;
            if (trimmed.contains("Problematic frame:")) {
                nativeFrame.add(trimmed);
                if (i + 1 < lines.size()) nativeFrame.add(lines.get(i + 1).strip());
            }
            if (trimmed.startsWith("-- ")) {
                head = trimmed.equalsIgnoreCase("-- Head --");
                suspectedBlock = false;
            }
            if (trimmed.startsWith("Suspected Mod")) {
                suspectedBlock = true;
                int colon = trimmed.indexOf(':');
                String suspect = colon >= 0 ? trimmed.substring(colon + 1).strip() : "";
                if (!suspect.isEmpty() && !suspect.equalsIgnoreCase("none") && !suspect.equalsIgnoreCase("unknown"))
                    suspects.add(suspect);
            } else if (suspectedBlock && (trimmed.isEmpty() || trimmed.equals("Stacktrace:") || trimmed.equals("Details:"))) {
                suspectedBlock = false;
            } else if (suspectedBlock && !trimmed.startsWith("at ") && !trimmed.startsWith("Mixin")) {
                // Forge can put the mod name and ID on an indented line below "Suspected Mods:".
                if (trimmed.matches(".+\\([\\w.-]+\\).*") && !trimmed.contains(".java:")) suspects.add(trimmed);
            }
            if (head || suspectedBlock) blocks.add(line.stripTrailing());
        }
        // Reuse HMCL's explicit mod-detection rules, never infer guilt from arbitrary package keywords.
        for (CrashReportAnalyzer.Result result : CrashReportAnalyzer.analyze(diagnostic)) {
            @Unmodifiable List<String> groups = switch (result.rule()) {
                case LOADING_CRASHED_FORGE -> List.of("name");
                case BOOTSTRAP_FAILED, LOADING_CRASHED_FABRIC, CONFIG, MIXIN_APPLY_MOD_FAILED -> List.of("id");
                case MOD_RESOLUTION_CONFLICT, MOD_RESOLUTION_MISSING, MOD_RESOLUTION_COLLECTION -> List.of("sourcemod", "destmod");
                case MOD_RESOLUTION_MISSING_MINECRAFT -> List.of("mod");
                case DUPLICATED_MOD -> List.of("name");
                default -> List.of();
            };
            for (String group : groups) {
                @Nullable String suspect = result.matcher().group(group);
                if (suspect != null && !suspect.isBlank()) suspects.add(suspect.strip());
            }
        }
        // Forge 47's loading-failure section identifies a mod even when the older HMCL rule only sees "-- MOD".
        Matcher modSection = Pattern.compile("(?m)^\\s*-- MOD ([^\\r\\n]+?) --\\s*$").matcher(diagnostic);
        while (modSection.find()) suspects.add(modSection.group(1));
        List<String> parts = new ArrayList<>();
        if (!description.isEmpty()) parts.add(description);
        if (!stack.isEmpty()) parts.add(String.join("\n", stack));
        if (!nativeFault.isEmpty()) parts.add(nativeFault);
        if (!nativeFrame.isEmpty()) parts.add(String.join("\n", nativeFrame));
        if (!suspects.isEmpty()) parts.add("可能是：" + String.join("、", suspects.stream().sorted().toList()));
        if (!blocks.isEmpty()) parts.add(String.join("\n", blocks).strip());
        String headline = !description.isEmpty() ? description.substring("Description:".length()).strip()
                : !firstException.isEmpty() ? firstException : nativeFault;
        return new Summary(headline, String.join("\n\n", parts), diagnostic);
    }

    /// Removes inventory/system sections and their tails while preserving earlier Head and mod-failure sections.
    private static String beforeInventory(String text) {
        Matcher end = Pattern.compile("(?im)^\\s*(?:-- System Details --|(?:Mod List|MOD LIST|Fabric Mods):|[^\\r\\n]*\\|[^\\r\\n]*NOSIGNATURE).*$").matcher(text);
        return end.find() ? text.substring(0, end.start()) : text;
    }
}
