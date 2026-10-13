package com.trollclient.update;

import com.trollclient.TrollClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What changed in each version, read from {@code assets/trollclient/notes.txt} inside the jar. The
 * notes travel with the build they describe, so reading them asks the website for nothing.
 */
public final class UpdateNotes {
	/** One line of the file: a version heading ({@code version} set, the rest empty) or a note under one. */
	public record Entry(String version, String date, String text) {
		public boolean heading() {
			return !version.isEmpty();
		}
	}

	private static final Pattern DATED = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s+(.+)$");
	private static List<Entry> entries;

	private UpdateNotes() {
	}

	public static List<Entry> entries() {
		if (entries == null) {
			entries = load();
		}
		return entries;
	}

	private static List<Entry> load() {
		List<Entry> out = new ArrayList<>();
		try (InputStream in = UpdateNotes.class.getResourceAsStream("/assets/trollclient/notes.txt")) {
			if (in == null) {
				return out;
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line; (line = reader.readLine()) != null; ) {
				line = line.strip();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				if (line.startsWith("=")) {
					out.add(new Entry(line.substring(1).strip(), "", ""));
					continue;
				}
				Matcher m = DATED.matcher(line);
				out.add(m.matches() ? new Entry("", m.group(1), m.group(2)) : new Entry("", "", line));
			}
		} catch (IOException e) {
			TrollClient.LOGGER.warn("Couldn't read the update notes", e);
		}
		return out;
	}
}
