package com.legalai.model;

import java.util.List;
import java.util.Map;

/**
 * Represents a legal case scenario submitted by the user.
 */
public class LegalCase {
    private String caseId;
    private String description;       // Free-text scenario description
    private String caseType;          // criminal, civil, family, corporate, property
    private String jurisdiction;      // State or "Central"
    private String clientRole;        // plaintiff, defendant, petitioner, respondent
    private String language;          // en, hi (for Indic support)
    private Map<String, String> metadata;

    public LegalCase() {}

    public LegalCase(String caseId, String description, String caseType,
                     String jurisdiction, String clientRole, String language) {
        this.caseId = caseId;
        this.description = description;
        this.caseType = caseType;
        this.jurisdiction = jurisdiction;
        this.clientRole = clientRole;
        this.language = language;
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCaseType() { return caseType; }
    public void setCaseType(String caseType) { this.caseType = caseType; }
    public String getJurisdiction() { return jurisdiction; }
    public void setJurisdiction(String jurisdiction) { this.jurisdiction = jurisdiction; }
    public String getClientRole() { return clientRole; }
    public void setClientRole(String clientRole) { this.clientRole = clientRole; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
