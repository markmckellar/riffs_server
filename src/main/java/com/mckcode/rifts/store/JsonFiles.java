package com.mckcode.rifts.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Reading and writing the JSON data files (users.json, games.json). Writes go to a temp file that is then moved
 * into place, so a crash mid-write never leaves half a file. This is the "JSON files for now" store; a database
 * can replace it behind the services later.
 */
public final class JsonFiles {

	public static final ObjectMapper MAPPER = new ObjectMapper()
			.enable(SerializationFeature.INDENT_OUTPUT)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	private JsonFiles() {
	}

	public static <T> T read(Path file, TypeReference<T> type, T empty) {
		if (!Files.exists(file)) return empty;
		try {
			return MAPPER.readValue(file.toFile(), type);
		}
		catch (IOException e) {
			throw new IllegalStateException("can't read " + file + ": " + e.getMessage(), e);
		}
	}

	public static synchronized void write(Path file, Object value) {
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			MAPPER.writeValue(tmp.toFile(), value);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException e) {
			throw new IllegalStateException("can't write " + file + ": " + e.getMessage(), e);
		}
	}
}
