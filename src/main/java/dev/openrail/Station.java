package dev.openrail;

/** A registered station: where its [station] sign is and what it says. */
public record Station(BlockKey location, String name, String description) {

    /** Lower-case name, as matched against /dest. */
    public String key() {
        return DestinationStore.normalize(name);
    }
}
