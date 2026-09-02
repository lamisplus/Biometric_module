package org.lamisplus.modules.biometric.enumeration;

public enum MatchTypes {
    ImperfectMatch("Imperfect Match"), PerfectMatch("Perfect Match"), NoMatch("No Match");
    private String matchType;

    MatchTypes(String matchType)
    {
        this.matchType=matchType;
    }

    public String getMatchType() {
        return matchType;
    }

    public void setMatchType(String matchType) {
        this.matchType = matchType;
    }
}





