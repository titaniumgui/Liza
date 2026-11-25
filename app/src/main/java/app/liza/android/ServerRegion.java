package app.liza.android;

enum ServerRegion {
    EUROPE("Europe", 25, "https://ieuopen.ezvizlife.com", "https://ieuopenauth.ezvizlife.com"),
    ASIA("Asia", 10, "https://isgpopen.ezvizlife.com", "https://isgpopenauth.ezvizlife.com"),
    NORTH_AMERICA("North America", 15, "https://iusopen.ezvizlife.com", "https://iusopenauth.ezvizlife.com"),
    SOUTH_AMERICA("South America", 20, "https://isaopen.ezvizlife.com", "https://isaopenauth.ezvizlife.com"),
    RUSSIA("Russia", 5, "https://irusopen.ezvizru.com", "https://irusopenauth.ezvizru.com");

    final String label;
    final int areaId;
    final String apiUrl;
    final String authUrl;

    ServerRegion(String label, int areaId, String apiUrl, String authUrl) {
        this.label = label;
        this.areaId = areaId;
        this.apiUrl = apiUrl;
        this.authUrl = authUrl;
    }

    @Override public String toString() { return label; }

    static ServerRegion fromLabel(String value) {
        for (ServerRegion region : values()) if (region.label.equals(value)) return region;
        return EUROPE;
    }
}
