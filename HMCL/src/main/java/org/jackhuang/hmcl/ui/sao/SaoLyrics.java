/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The bilingual title lyrics: each stamp carries a Japanese line (with optional per-word `<mm:ss.xx>` timing)
/// followed by its Chinese line under the same stamp.
@NotNullByDefault
public final class SaoLyrics {
    /// One sung line.
    ///
    /// @param start   when the line starts, in seconds
    /// @param end     when the next line starts (or start + 6 s for the last), in seconds
    /// @param main    the Japanese line without timing tags
    /// @param sub     the Chinese line, or empty
    /// @param marks   the times at which each character of [#main] starts, one per character
    public record Line(double start, double end, String main, String sub, double @Unmodifiable [] marks) {
        /// How much of the main line has been sung at a moment, by the word timing when present.
        ///
        /// @param t seconds into the track
        /// @return 0 to 1
        public double sung(double t) {
            if (t <= start) return 0;
            if (t >= end) return 1;
            if (marks.length == 0) return Math.min(1, (t - start) / Math.max(0.1, end - start - 0.4));
            int done = 0;
            while (done < marks.length && marks[done] <= t) done++;
            if (done == 0) return 0;
            double from = marks[done - 1];
            double to = done < marks.length ? marks[done] : Math.min(end, from + 0.6);
            double part = to > from ? Math.min(1, (t - from) / (to - from)) : 1;
            return (done - 1 + part) / marks.length;
        }
    }

    /// A line stamp at the start of a row.
    private static final Pattern STAMP = Pattern.compile("^\\[(\\d+):(\\d+(?:\\.\\d+)?)]");
    /// A per-word stamp inside a row.
    private static final Pattern WORD = Pattern.compile("<(\\d+):(\\d+(?:\\.\\d+)?)>");

    /// The lines in time order.
    private final @Unmodifiable List<Line> lines;

    /// Builds the lyrics from parsed lines.
    ///
    /// @param lines the lines in time order
    private SaoLyrics(List<Line> lines) {
        this.lines = List.copyOf(lines);
    }

    /// The lines in time order.
    ///
    /// @return the lines
    public @Unmodifiable List<Line> lines() {
        return lines;
    }

    /// The line being sung at a moment.
    ///
    /// @param t seconds into the track
    /// @return the line, or null between verses and before the first
    public @Nullable Line at(double t) {
        for (Line l : lines) if (t >= l.start() && t < l.end()) return l;
        return null;
    }

    /// Parses LRC text; metadata rows and empty rows are skipped.
    ///
    /// @param text the file's text
    /// @return the lyrics, possibly with no lines
    public static SaoLyrics parse(String text) {
        record Row(double t, String body) {
        }
        List<Row> rows = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            Matcher m = STAMP.matcher(raw);
            if (!m.find()) continue;
            rows.add(new Row(seconds(m.group(1), m.group(2)), raw.substring(m.end())));
        }
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String sub = "";
            if (i + 1 < rows.size() && rows.get(i + 1).t() == r.t()) sub = rows.get(++i).body().strip();
            StringBuilder main = new StringBuilder();
            List<Double> marks = new ArrayList<>();
            Matcher w = WORD.matcher(r.body());
            int pos = 0;
            double current = r.t();
            while (w.find()) {
                append(main, marks, r.body().substring(pos, w.start()), current);
                current = seconds(w.group(1), w.group(2));
                pos = w.end();
            }
            append(main, marks, r.body().substring(pos), current);
            String text1 = main.toString().strip();
            if (text1.isEmpty() && sub.isEmpty()) {
                out.add(new Line(r.t(), r.t(), "", "", new double[0]));
                continue;
            }
            boolean timed = marks.stream().anyMatch(d -> d != r.t());
            double[] arr = timed ? marks.stream().mapToDouble(Double::doubleValue).toArray() : new double[0];
            out.add(new Line(r.t(), r.t() + 6, text1, sub, arr));
        }
        // Each line ends where the next row starts (empty rows mark the gaps).
        List<Line> fixed = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            Line l = out.get(i);
            if (l.main().isEmpty() && l.sub().isEmpty()) continue;
            double end = i + 1 < out.size() ? Math.min(out.get(i + 1).start(), l.start() + 12) : l.start() + 6;
            double[] marks = trimMarks(l.marks(), l.main());
            fixed.add(new Line(l.start(), end, l.main(), l.sub(), marks));
        }
        return new SaoLyrics(fixed);
    }

    /// Appends a piece of text, giving each of its characters the piece's start time.
    ///
    /// @param main  the line being built
    /// @param marks the per-character times being built
    /// @param piece the text
    /// @param t     when the piece starts
    private static void append(StringBuilder main, List<Double> marks, String piece, double t) {
        for (int k = 0; k < piece.length(); k++) {
            if (main.length() == 0 && Character.isWhitespace(piece.charAt(k))) continue;
            main.append(piece.charAt(k));
            marks.add(t);
        }
    }

    /// Drops trailing marks left by whitespace that [String#strip] removed.
    ///
    /// @param marks the marks, one per character before stripping
    /// @param main  the stripped line
    /// @return marks with the line's length, or none
    private static double @Unmodifiable [] trimMarks(double[] marks, String main) {
        if (marks.length == 0) return marks;
        double[] out = new double[main.length()];
        System.arraycopy(marks, 0, out, 0, Math.min(marks.length, out.length));
        for (int k = marks.length; k < out.length; k++) out[k] = marks[marks.length - 1];
        return out;
    }

    /// Converts minutes and seconds to seconds.
    ///
    /// @param min the minutes
    /// @param sec the seconds, maybe with a fraction
    /// @return seconds
    private static double seconds(String min, String sec) {
        return Integer.parseInt(min) * 60 + Double.parseDouble(sec);
    }
}
