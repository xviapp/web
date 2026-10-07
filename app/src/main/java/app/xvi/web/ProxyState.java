package app.xvi.web;

import java.util.HashMap;

/** Small host state value, independent of any JavaScript bridge. */
final class ProxyState {
    private final HashMap<String, Object> values = new HashMap<>();
    ProxyState put(String key, Object value) { values.put(key, value); return this; }
    String text(String key) { return String.valueOf(values.getOrDefault(key, "")); }
    boolean flag(String key) { return Boolean.TRUE.equals(values.get(key)); }
}
