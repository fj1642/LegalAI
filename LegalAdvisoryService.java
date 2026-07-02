package com.legalai.service;

import com.legalai.api.ClaudeClient;
import com.legalai.model.AdvisoryResult;
import com.legalai.model.AdvisoryResult.*;
import com.legalai.model.LegalCase;
import com.legalai.rag.KnowledgeBase;
import com.legalai.rag.KnowledgeBase.RetrievedChunk;

import java.util.*;
import java.util.regex.*;

/**
 * Core Legal Advisory Service.
 *
 * Pipeline:
 *   1. Retrieve relevant legal docs via RAG
 *   2. Build structured prompt (system + context + case)
 *   3. Call Claude API
 *   4. Parse structured response → AdvisoryResult
 *   5. Run cost/time estimation sub-model
 */
public class LegalAdvisoryService {

    private final KnowledgeBase knowledgeBase;
    private final ClaudeClient  claudeClient;

    public LegalAdvisoryService(KnowledgeBase kb) {
        this.knowledgeBase = kb;
        this.claudeClient  = new ClaudeClient();
    }

    // ─── Main analysis pipeline ───────────────────────────────────────────────

    public AdvisoryResult analyze(LegalCase legalCase) throws Exception {
        // Step 1 – RAG retrieval
        String query = legalCase.getCaseType() + " " + legalCase.getDescription();
        List<RetrievedChunk> chunks = knowledgeBase.retrieve(query, 5);
        String ragContext = knowledgeBase.buildContext(chunks);

        // Step 2 – Build prompt
        String systemPrompt = buildSystemPrompt(legalCase.getLanguage());
        String userPrompt   = buildUserPrompt(legalCase, ragContext);

        // Step 3 – LLM call
        String llmResponse = claudeClient.complete(systemPrompt, userPrompt, 2000);

        // Step 4 – Parse response
        AdvisoryResult result = parseResponse(llmResponse, legalCase, chunks);

        // Step 5 – If Hindi requested, generate Indic summary
        if ("hi".equals(legalCase.getLanguage())) {
            String indicSummary = generateIndicSummary(result.getReasoning(), legalCase);
            result.setIndicSummary(indicSummary);
        }

        return result;
    }

    // ─── Prompt construction ──────────────────────────────────────────────────

    private String buildSystemPrompt(String lang) {
        return """
            You are LegalAI, an expert Indian legal advisory system trained on IPC, CrPC, \
            Indian Contract Act, Consumer Protection Act, and real case precedents.
            
            Your role is to analyze legal case scenarios and recommend the BEST COURSE OF ACTION \
            to maximise the client's legal gain (outcome + cost efficiency + time savings).
            
            ALWAYS respond in this EXACT structured format:
            
            ACTION: [one of: PRESS_CHARGES | SETTLE_OUT_OF_COURT | MEDIATION | ARBITRATION | \
            WITHDRAW_CASE | FILE_APPEAL | NEGOTIATE_PLEA | SEND_LEGAL_NOTICE | FILE_CIVIL_SUIT | AWAIT_INVESTIGATION]
            
            REASONING: [3-5 paragraph detailed legal analysis]
            
            SECTIONS: [comma-separated list of relevant IPC/CrPC/Act sections]
            
            CONFIDENCE: [0.0 to 1.0]
            
            RISK: [one paragraph risk assessment]
            
            COST_MIN: [minimum cost in INR as integer]
            COST_MAX: [maximum cost in INR as integer]
            COST_BREAKDOWN: [brief breakdown]
            
            TIME_MIN: [minimum duration in months as integer]
            TIME_MAX: [maximum duration in months as integer]
            TIME_STAGES: [brief stage breakdown]
            
            ALT1_ACTION: [alternative action]
            ALT1_REASON: [one sentence rationale]
            ALT1_PROB: [success probability 0.0-1.0]
            
            ALT2_ACTION: [alternative action]
            ALT2_REASON: [one sentence rationale]
            ALT2_PROB: [success probability 0.0-1.0]
            
            Base your analysis on Indian law. Be specific about court hierarchy, timelines, and costs in INR.
            """ + (lang.equals("hi") ? "\nAlso be prepared to provide a Hindi summary when requested." : "");
    }

    private String buildUserPrompt(LegalCase lc, String ragContext) {
        return ragContext + "\n\n"
            + "=== CASE DETAILS ===\n"
            + "Case Type: " + lc.getCaseType() + "\n"
            + "Jurisdiction: " + lc.getJurisdiction() + "\n"
            + "Client Role: " + lc.getClientRole() + "\n"
            + "Description: " + lc.getDescription() + "\n\n"
            + "Based on the legal context above and the case details, provide your advisory.";
    }

    // ─── Response parser ─────────────────────────────────────────────────────

    private AdvisoryResult parseResponse(String llmResponse, LegalCase lc,
                                          List<RetrievedChunk> chunks) {
        AdvisoryResult result = new AdvisoryResult();
        result.setCaseId(lc.getCaseId());

        result.setPrimaryAction(parseEnum(llmResponse, "ACTION:", RecommendedAction.SEND_LEGAL_NOTICE));
        result.setReasoning(parseField(llmResponse, "REASONING:"));
        result.setRelevantSections(parseSections(llmResponse));
        result.setConfidenceScore(parseDouble(llmResponse, "CONFIDENCE:", 0.75));
        result.setRiskAssessment(parseField(llmResponse, "RISK:"));

        // Cost estimate
        long costMin = parseLong(llmResponse, "COST_MIN:", 25000L);
        long costMax = parseLong(llmResponse, "COST_MAX:", 200000L);
        String costBreak = parseField(llmResponse, "COST_BREAKDOWN:");
        result.setCostEstimate(new CostEstimate(costMin, costMax, costBreak));

        // Time estimate
        int timeMin = (int) parseLong(llmResponse, "TIME_MIN:", 6L);
        int timeMax = (int) parseLong(llmResponse, "TIME_MAX:", 24L);
        String timeStages = parseField(llmResponse, "TIME_STAGES:");
        result.setTimeEstimate(new TimeEstimate(timeMin, timeMax, timeStages));

        // Alternatives
        List<AlternativeAction> alts = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            RecommendedAction altAction = parseEnum(llmResponse, "ALT" + i + "_ACTION:",
                    i == 1 ? RecommendedAction.MEDIATION : RecommendedAction.SETTLE_OUT_OF_COURT);
            String altReason = parseField(llmResponse, "ALT" + i + "_REASON:");
            double altProb   = parseDouble(llmResponse, "ALT" + i + "_PROB:", 0.5);
            alts.add(new AlternativeAction(altAction, altReason, altProb));
        }
        result.setAlternatives(alts);

        // RAG sources
        List<String> sources = new ArrayList<>();
        for (RetrievedChunk c : chunks) {
            sources.add(c.getTitle() + " [score=" + String.format("%.2f", c.getScore()) + "]");
        }
        result.setRagSources(sources);

        return result;
    }

    private String generateIndicSummary(String reasoning, LegalCase lc) throws Exception {
        String sys = "You are a legal assistant. Summarise the given legal advice in simple Hindi (Devanagari script). " +
                     "Keep it under 150 words. Use plain language a non-lawyer can understand.";
        String user = "Case: " + lc.getDescription() + "\n\nAdvice to summarise:\n" + reasoning;
        return claudeClient.complete(sys, user, 400);
    }

    // ─── Parse helpers ────────────────────────────────────────────────────────

    private String parseField(String text, String key) {
        int start = text.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        // Find next key or end
        int end = text.length();
        for (String k : new String[]{"ACTION:","REASONING:","SECTIONS:","CONFIDENCE:","RISK:",
                "COST_MIN:","COST_MAX:","COST_BREAKDOWN:","TIME_MIN:","TIME_MAX:","TIME_STAGES:",
                "ALT1_ACTION:","ALT1_REASON:","ALT1_PROB:","ALT2_ACTION:","ALT2_REASON:","ALT2_PROB:"}) {
            int idx = text.indexOf(k, start);
            if (idx > start && idx < end) end = idx;
        }
        return text.substring(start, end).trim();
    }

    private RecommendedAction parseEnum(String text, String key, RecommendedAction def) {
        String val = parseField(text, key).toUpperCase().replaceAll("[^A-Z_]", "");
        try { return RecommendedAction.valueOf(val); } catch (Exception e) { return def; }
    }

    private double parseDouble(String text, String key, double def) {
        String val = parseField(text, key).replaceAll("[^0-9.]", "");
        try { return Double.parseDouble(val); } catch (Exception e) { return def; }
    }

    private long parseLong(String text, String key, long def) {
        String val = parseField(text, key).replaceAll("[^0-9]", "");
        try { return Long.parseLong(val); } catch (Exception e) { return def; }
    }

    private List<String> parseSections(String text) {
        String raw = parseField(text, "SECTIONS:");
        if (raw.isBlank()) return Collections.emptyList();
        return Arrays.asList(raw.split(",\\s*"));
    }
}
