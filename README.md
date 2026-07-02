# ⚖️ LegalAI — Indian Scenario-Based Legal Advisory System

> AI-powered legal advisory for the people who need it most and can afford it least.

LegalAI takes a plain-English description of a legal problem — a landlord who won't return a deposit, a dodgy vendor, a family dispute — and turns it into something usable: a recommended course of action, the actual IPC/CrPC/Contract Act sections that apply, a realistic cost estimate in INR, a timeline, and backup options if plan A doesn't work out. In Hindi too, if that's what you need.

It's not trying to replace a lawyer. It's trying to be the thing you read *before* you can afford one.

---

## Why this exists

India's legal system is enormous, multilingual, and precedent-heavy — and legal advice is expensive enough that most first-generation litigants, small business owners, and rural users just... don't get any, even for questions with fairly standard answers ("can I file a consumer complaint for this?", "what happens if I skip this court date?"). LegalAI is a proof-of-concept for closing that gap using retrieval-augmented generation instead of a static FAQ page — the advice is grounded in real retrieved legal text, not just whatever the model remembers.

## What it actually does

1. You describe your situation on a single-page web UI.
2. The system pulls the 5 most relevant chunks of Indian legal text (IPC sections, CrPC provisions, Contract Act clauses, Consumer Protection Act provisions, case precedents) from an in-memory knowledge base using TF-IDF + cosine similarity — no vector database, no external ML service, just math.
3. Those chunks get stitched into a structured prompt and sent to Claude.
4. Claude's response — deliberately formatted as strict key-value pairs rather than freeform prose — gets parsed into a typed `AdvisoryResult`: primary action, legal reasoning, applicable sections, confidence score, risk assessment, cost range, time range, and two ranked alternatives.
5. If you asked for Hindi, a second Claude call condenses the reasoning into a ≤150-word Devanagari summary a non-lawyer can actually read.

All of this runs as **one self-contained Java process.** No Spring Boot, no Hibernate, no external HTTP client library — just Java 17 and its built-in tooling.

## Why pure Java, no frameworks

Because it didn't need them. `com.sun.net.httpserver` handles routing, `java.net.http.HttpClient` (also built-in, since Java 11) talks to Claude's API, and everything else is hand-rolled JSON parsing and a custom TF-IDF index. The result: clone it, set one environment variable, and it runs on any machine with a JDK. No dependency hell, no version conflicts, no `node_modules`.

---

## Architecture

```
Browser (index.html)
      │  POST /api/analyze
      ▼
HttpServer.java ──────────────► LegalAdvisoryService.java
 (routing, JSON in/out)          (orchestrates the whole pipeline)
                                        │
                        ┌───────────────┼────────────────┐
                        ▼                                ▼
                KnowledgeBase.java                ClaudeClient.java
             (TF-IDF index + retrieval)        (talks to Anthropic API)
                        ▲
                        │
              KaggleDatasetLoader.java
           (optional: ingest external CSV data)
```

**The request flow, start to finish:**
Frontend sends the case description, type, jurisdiction, role, and language → `HttpServer` parses it into a `LegalCase` → `LegalAdvisoryService` retrieves the top-5 relevant documents from `KnowledgeBase`, builds the prompt, calls Claude, parses the structured response into an `AdvisoryResult` → (if Hindi was requested) a second Claude call generates the Indic summary → the whole thing gets serialized back to JSON and rendered in the browser.

---

## Tech stack

| Piece | What's actually used |
|---|---|
| Language/runtime | Java 17 (LTS) |
| HTTP server | `com.sun.net.httpserver` — built into the JDK, zero dependencies |
| HTTP client | `java.net.http.HttpClient` |
| LLM | Anthropic Claude (`claude-sonnet-4-20250514`) |
| Retrieval | Custom TF-IDF + cosine similarity, in-memory |
| Frontend | Vanilla HTML/JS, no framework |
| External data | Kaggle CSV datasets (optional) |

## The RAG pipeline, briefly

**Indexing:** every document is tokenized (split on non-alphanumerics, lowercased, stop-words stripped), and a TF-IDF weight is computed per term using the standard `TF(t,d) × log(N / DF(t))` formula.

**Retrieval:** your query gets tokenized the same way and vectorized against the same IDF weights, then scored against every document via cosine similarity. Top 5 win.

**Injection:** the winning chunks get wrapped in a `=== RETRIEVED LEGAL CONTEXT ===` block and prepended to the prompt — so Claude's answer is grounded in the specific sections and precedents that actually apply, not just general legal knowledge.

It's lexical, not semantic — a known tradeoff, see [Limitations](#known-limitations) below.

---

## Getting started

**You'll need:** a JDK 17+ install and an [Anthropic API key](https://console.anthropic.com).

```bash
# clone it
git clone <your-repo-url>
cd legalai

# set your API key
export ANTHROPIC_API_KEY="your-key-here"

# compile
javac -d out src/**/*.java

# run
java -cp out LegalAdvisoryApp
```

Then open **`http://localhost:8080`** and describe your problem.

---

## API reference

### `POST /api/analyze`

```json
{
  "description": "My landlord in Bangalore won't return my ₹50,000 security deposit after I moved out.",
  "caseType": "civil",
  "jurisdiction": "Karnataka",
  "clientRole": "plaintiff",
  "language": "en"
}
```

| Field | Type | Notes |
|---|---|---|
| `description` | string | Free-text scenario |
| `caseType` | string | `criminal` \| `civil` \| `family` \| `corporate` \| `property` |
| `jurisdiction` | string | State name, or `Central` |
| `clientRole` | string | `plaintiff` \| `defendant` \| `petitioner` \| `respondent` |
| `language` | string | `en` or `hi` |

Returns the full `AdvisoryResult`: `caseId`, `primaryAction`, `reasoning`, `confidenceScore`, `riskAssessment`, `relevantSections`, `costEstimate`, `timeEstimate`, `alternatives`, `ragSources`, and `indicSummary` if Hindi was requested.

### `GET /api/health`
Returns `{ "status": "ok", "service": "LegalAI" }`. For liveness checks.

---

## Sample output

For a ₹50,000 withheld security deposit dispute in Karnataka:

| Field | Value |
|---|---|
| Primary Action | `SEND_LEGAL_NOTICE` |
| Confidence | 0.88 |
| Relevant Sections | Contract Act §73, Consumer Protection Act §35, CrPC §406 |
| Cost Estimate | ₹5,000 – ₹50,000 |
| Time Estimate | 3 – 12 months |
| Alternative 1 | Mediation (70% success probability) |
| Alternative 2 | File Civil Suit (55% success probability) |

Retrieved context scored above 0.7 cosine similarity across the `CONTRACT`, `CONSUMER`, and `CIVIL` categories — a decent signal that the retrieval step is doing its job.

---

## Bringing your own legal data

`KaggleDatasetLoader` auto-detects CSV schema by column headers and slots the data into the right category. Point it at a directory of CSVs and it handles the rest — just call `rebuildIndex()` afterward so the TF-IDF weights reflect the new documents.

| Dataset type | Required columns |
|---|---|
| IPC Sections | `section, description, punishment, bailable` |
| Legal Q&A | `question, answer, category, language` |
| Court Judgments | `case_no, court, year, summary, outcome` |
| Generic Legal Docs | `id, category, title, content` |

Datasets that plug in cleanly: IPC section corpora, annotated Indian legal document sets, court judgment summaries, and bilingual legal Q&A pairs — all findable on Kaggle.

---

## Hindi support

Set `"language": "hi"` and the system fires a second Claude call that condenses the English legal reasoning into a plain-language Devanagari summary, capped at 150 words. The built-in knowledge base also carries Hindi Q&A pairs for common scenarios — FIR registration, divorce and alimony, land disputes, consumer complaints, cyber fraud — tagged `INDIC_QA` so they surface naturally during retrieval when the query overlaps.

---

## Known limitations

Being upfront about these, because a legal tool that oversells itself is worse than no tool at all:

- **TF-IDF is lexical, not semantic.** Paraphrase a query too far from the source wording and retrieval can miss the right document entirely.
- **Structured-output parsing is format-dependent.** If Claude deviates from the expected key-value structure, fields fall back to defaults rather than erroring loudly.
- **Nothing persists.** Every advisory is ephemeral — restart the server, lose the history.
- **No auth, no rate limiting.** This is a proof-of-concept, not something to put behind a public URL without a gateway in front of it.
- **The custom JSON handling is minimal** and hasn't been stress-tested against deeply nested Unicode or huge payloads.

## Where this could go next

- Swap TF-IDF for dense embeddings (sentence-transformers via a Python microservice, or Deep Java Library) for actual semantic retrieval
- Persistent storage — PostgreSQL + pgvector, most likely
- Claude tool use / function calling instead of parsing structured text by hand
- Auth, sessions, advisory history
- Tamil, Telugu, Marathi, Bengali support alongside Hindi
- Scheduled ingestion from India Code for real-time legal updates

---

## Built by

Farhaan Uddin Jabri & Aryan Mishra — B.Tech CSE (AI/ML), SRM Institute of Science and Technology, Delhi-NCR.

Built as a proof-of-concept, not legal advice. If your situation actually matters, talk to a real lawyer — this is meant to help you walk into that conversation informed, not to replace it.

## References

- Lewis, P. et al. (2020). *Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks.* NeurIPS 2020.
- Sparck Jones, K. (1972). *A statistical interpretation of term specificity and its application in retrieval.* Journal of Documentation.
- Indian Penal Code (1860, as amended), Code of Criminal Procedure (1973), Consumer Protection Act (2019) — via the [India Code portal](https://indiacode.nic.in).
- [Anthropic Claude API documentation](https://docs.anthropic.com)
