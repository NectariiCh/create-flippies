package com.nectariich.create_flippies.content.computer.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A content pack's {@code pack.json} (spec 12.4.3): its format (1), id ({@code [a-z0-9-]+}),
 * version, title and the hosts it serves, as
 * {@code {"format": 1, "id": "debian-os", "version": "20261025T063552Z", "title": "...", "hosts": ["get.flip"]}}.
 * Other keys are ignored, so a later format can add some. Read with a small strict JSON reader
 * of its own, so this package keeps to plain Java.
 */
public record PackManifest(int format, String id, String version, String title, List<String> hosts) {

    public static final int FORMAT = 1;
    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");
    /** Lower-case DNS labels joined by dots: what the modem's DNS can answer. */
    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");

    public PackManifest {
        hosts = List.copyOf(hosts);
    }

    /** The manifest in {@code json}; IllegalArgumentException saying what is wrong. */
    public static PackManifest parse(String json) {
        Object root = new Reader(json).document();
        if (!(root instanceof Map<?, ?> map))
            throw new IllegalArgumentException("pack.json is not a JSON object");
        Object format = map.get("format");
        if (!(format instanceof Double f) || f != FORMAT)
            throw new IllegalArgumentException("pack.json format is " + format + ", not " + FORMAT);
        String id = string(map, "id"), version = string(map, "version"), title = string(map, "title");
        if (!ID.matcher(id).matches())
            throw new IllegalArgumentException("pack.json id \"" + id + "\" is not [a-z0-9-]+");
        if (version.isEmpty())
            throw new IllegalArgumentException("pack.json version is empty");
        if (!(map.get("hosts") instanceof List<?> list) || list.isEmpty())
            throw new IllegalArgumentException("pack.json hosts is not a list of host names");
        List<String> hosts = new ArrayList<>();
        for (Object h : list) {
            if (!(h instanceof String host) || host.length() > 253 || !HOST.matcher(host).matches())
                throw new IllegalArgumentException("pack.json host " + h + " is not a lower-case host name");
            if (host.equals(SharedFolder.HOST))
                throw new IllegalArgumentException("pack.json host " + host + " is the world's file share");
            if (!hosts.contains(host))
                hosts.add(host);
        }
        return new PackManifest(FORMAT, id, version, title, hosts);
    }

    private static String string(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof String s))
            throw new IllegalArgumentException("pack.json " + key + " is not a string");
        return s;
    }

    /** RFC 8259 JSON into Map, List, String, Double, Boolean and null; IllegalArgumentException on anything else. */
    private static final class Reader {

        private static final int MAX_DEPTH = 32;
        private final String s;
        private int at;

        Reader(String s) {
            this.s = s;
        }

        Object document() {
            Object v = value(0);
            space();
            if (at < s.length())
                throw bad("text after the JSON value");
            return v;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH)
                throw bad("nested too deep");
            space();
            if (at >= s.length())
                throw bad("unexpected end");
            char c = s.charAt(at);
            switch (c) {
                case '{' -> {
                    at++;
                    Map<String, Object> map = new LinkedHashMap<>();
                    space();
                    if (peek('}'))
                        return map;
                    do {
                        space();
                        if (at >= s.length() || s.charAt(at) != '"')
                            throw bad("expected a key");
                        String key = string();
                        space();
                        expect(':');
                        map.put(key, value(depth + 1));
                        space();
                    } while (peek(','));
                    expect('}');
                    return map;
                }
                case '[' -> {
                    at++;
                    List<Object> list = new ArrayList<>();
                    space();
                    if (peek(']'))
                        return list;
                    do {
                        list.add(value(depth + 1));
                        space();
                    } while (peek(','));
                    expect(']');
                    return list;
                }
                case '"' -> {
                    return string();
                }
                default -> {
                    if (s.startsWith("true", at)) {
                        at += 4;
                        return Boolean.TRUE;
                    }
                    if (s.startsWith("false", at)) {
                        at += 5;
                        return Boolean.FALSE;
                    }
                    if (s.startsWith("null", at)) {
                        at += 4;
                        return null;
                    }
                    return number();
                }
            }
        }

        private Double number() {
            int start = at;
            while (at < s.length() && "+-0123456789.eE".indexOf(s.charAt(at)) >= 0)
                at++;
            String n = s.substring(start, at);
            if (!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
                throw bad("not a value");
            return Double.parseDouble(n);
        }

        private String string() {
            at++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (at >= s.length())
                    throw bad("unterminated string");
                char c = s.charAt(at++);
                if (c == '"')
                    return b.toString();
                if (c < 0x20)
                    throw bad("control character in a string");
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (at >= s.length())
                    throw bad("unterminated string");
                char e = s.charAt(at++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (at + 4 > s.length())
                            throw bad("short \\u escape");
                        try {
                            b.append((char) Integer.parseInt(s.substring(at, at + 4), 16));
                        } catch (NumberFormatException x) {
                            throw bad("bad \\u escape");
                        }
                        at += 4;
                    }
                    default -> throw bad("bad escape \\" + e);
                }
            }
        }

        private void space() {
            while (at < s.length() && " \t\r\n".indexOf(s.charAt(at)) >= 0)
                at++;
        }

        private boolean peek(char c) {
            if (at < s.length() && s.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!peek(c))
                throw bad("expected '" + c + "'");
        }

        private IllegalArgumentException bad(String what) {
            return new IllegalArgumentException("pack.json is not valid JSON at character " + at + ": " + what);
        }
    }
}
