package com.criteo.hadoop.garmadon.jvm.utils;

public final class JavaRuntime {
    private static final Version VERSION = parseVersion(System.getProperty("java.version"));

    private JavaRuntime() {
    }

    static Version parseVersion(String version) {
        try {
            int versionNumber;
            if (version.startsWith("1.")) {
                versionNumber = Integer.parseInt(version.substring(2, 3));
            } else {
                int dot = version.indexOf(".");
                versionNumber = Integer.parseInt(version.substring(0, dot));
            }
            return new Version(version, versionNumber);
        } catch (RuntimeException e) {
            return new Version(version, e);
        }
    }

    public static int feature() throws RuntimeException {
        if (VERSION.feature == -1) {
            throw new RuntimeException("Could not parse Java version.", VERSION.parsingError);
        }
        return VERSION.feature;
    }

    public static String version() {
        return VERSION.version;
    }

    final static class Version {
        private final String version;
        private final int feature;
        private final RuntimeException parsingError;

        private Version(String version, int feature) {
            this.version = version;
            this.feature = feature;
            this.parsingError = null;
        }

        private Version(String version, RuntimeException parsingError) {
            this.version = version;
            this.feature = -1;
            this.parsingError = parsingError;
        }

        public String getVersion() {
            return version;
        }

        public int getFeature() {
            return feature;
        }

        public RuntimeException getParsingError() {
            return parsingError;
        }
    }
}
