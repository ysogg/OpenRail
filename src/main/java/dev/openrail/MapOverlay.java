package dev.openrail;

/**
 * A web-map integration. Kept as an interface so the main plugin never references an optional
 * dependency's classes directly; implementations are only loaded when their map plugin is present.
 */
public interface MapOverlay {

    void start();

    void shutdown();
}
