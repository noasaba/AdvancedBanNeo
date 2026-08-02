package me.leoko.advancedban.velocity;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class YamlConfig {
    private final Path file;
    private final Map<String, Object> root;

    private YamlConfig(Path file, Map<String, Object> root) {
        this.file = file;
        this.root = root;
    }

    static YamlConfig load(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
            if (loaded instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> values = (Map<Object, Object>) loaded;
                return new YamlConfig(file, normalizeMap(values));
            }
        }
        return new YamlConfig(file, Collections.emptyMap());
    }

    Object get(String path) {
        Object value = root;
        for (String part : path.split("\\.")) {
            if (!(value instanceof Map)) {
                return null;
            }
            value = ((Map<?, ?>) value).get(part);
        }
        return value;
    }

    Set<String> keys(String path) {
        Object value = get(path);
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Set<String> keys = ((Map<String, Object>) value).keySet();
            return keys;
        }
        return Collections.emptySet();
    }

    List<String> stringList(String path) {
        Object value = get(path);
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        java.util.ArrayList<String> strings = new java.util.ArrayList<>(list.size());
        for (Object element : list) {
            strings.add(String.valueOf(element));
        }
        return strings;
    }

    String fileName() {
        return file.getFileName().toString();
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                value = normalizeMap((Map<?, ?>) value);
            }
            normalized.put(String.valueOf(entry.getKey()), value);
        }
        return normalized;
    }
}
