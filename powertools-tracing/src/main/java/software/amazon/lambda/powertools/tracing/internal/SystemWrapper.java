package software.amazon.lambda.powertools.tracing.internal;

public class SystemWrapper {
    public SystemWrapper() {
    }

    public static String getenv(String name) {
        return System.getenv(name);
    }
}