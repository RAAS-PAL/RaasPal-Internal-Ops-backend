# RAASPAL — AI Robot Solution & Proposal Generator

An internal platform for the RAASPAL team to generate robot solution recommendations and customer proposals from uploaded survey forms — powered by Claude AI.

---

## Platform Workflow

```mermaid
flowchart TD
    A([Team member logs in]) --> B[Team Dashboard]
    B --> C[Click **Start here** → Generate Solution]

    C --> D[Name the solution\ne.g. ABC Mall Cleaning Project]
    D --> E[Select robot type\nCleaning · Delivery · Mowing]
    E --> F[Upload customer survey\nExcel · PDF · Image]

    F --> G[[AI extracts customer requirements]]
    G --> H[(Backend loads robot catalog\nfrom PostgreSQL)]
    H --> I[[AI compares requirements\nvs. verified robot catalog]]

    I --> J{2–3 ranked robot\noptions returned}

    J --> K[Review recommendations\nfit level · specs · pricing · reasoning]
    K --> L[Select best robot option]

    L --> M[[AI generates customer proposal]]
    M --> N[Review proposal]

    N --> O{Export}
    O --> P[Copy / Print]
    O --> Q[Download PowerPoint]

    N --> R[(Proposal saved to history)]
    R --> S([View anytime in\nSolutions & Proposals pages])

    style G fill:#0e7490,color:#fff,stroke:#0e7490
    style I fill:#0e7490,color:#fff,stroke:#0e7490
    style M fill:#0e7490,color:#fff,stroke:#0e7490
    style H fill:#1e3a5f,color:#fff,stroke:#1e3a5f
    style R fill:#1e3a5f,color:#fff,stroke:#1e3a5f
```

> **Teal nodes** = AI steps &nbsp;|&nbsp; **Dark navy nodes** = Database operations &nbsp;|&nbsp; Everything else = User actions

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend API | Spring Boot 3.4.5 · Java 21 |
| Frontend | Next.js 16 · TypeScript · Tailwind CSS v4 |
| Database | PostgreSQL via Supabase (session-mode pooler) |
| Migrations | Flyway |
| AI Provider | Anthropic Claude API |
| Auth | JWT (Bearer token) |
| File Upload | Multipart — PDF, Excel, PNG, JPG |

**AI Models used:**
- Requirement extraction → `claude-sonnet-4-6`
- Robot recommendation → `claude-sonnet-4-6`
- Proposal generation → `claude-opus-4-8`

---

## Project Structure

```
AI-RobotRecommendationSystem-Backend/
├── src/main/java/com/raaspal/robotrecommendation/
│   ├── auth/            # JWT auth, login, user principal
│   ├── user/            # User entity & management
│   ├── robot/           # Robot catalog (entity, specs, import)
│   ├── requirement/     # Customer requirement extraction
│   ├── recommendation/  # AI recommendation engine
│   ├── proposal/        # Proposal generation & PPTX export
│   └── ai/              # Claude AI service (real + mock)
└── src/main/resources/
    ├── application.properties
    └── db/migration/    # Flyway SQL migrations (V1–V8)
```

---

## Running Locally

### Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL (or use the Supabase connection below)

### 1. Clone and configure

Create `src/main/resources/application-local.properties` (gitignored):

```properties
spring.datasource.url=jdbc:postgresql://<supabase-host>:5432/postgres?sslmode=require
spring.datasource.username=postgres.<project-ref>
spring.datasource.password=<your-password>

app.jwt.secret=<min-32-char-secret>

# Optional — leave blank to use MockAiService (hardcoded responses)
app.anthropic.api-key=sk-ant-...
```

### 2. Run

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The API starts on `http://localhost:8080`.

### 3. Run tests

```bash
./mvnw clean test
```

---

## Key API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/auth/login` | Login, returns JWT |
| `POST` | `/api/v1/files/upload` | Upload customer survey file |
| `POST` | `/api/v1/requirements/extract-from-file/{fileId}` | AI extracts requirements |
| `POST` | `/api/v1/recommendations/generate/{requirementId}` | AI generates robot recommendations |
| `POST` | `/api/v1/proposals/generate` | AI generates customer proposal |
| `GET` | `/api/v1/proposals/{id}/export/pptx` | Download proposal as PowerPoint |
| `GET` | `/api/v1/robots` | List robot catalog |

All endpoints (except login) require `Authorization: Bearer <token>`.

---

## Environment Variables (Render / Production)

| Variable | Description |
|---|---|
| `DB_URL` | Supabase JDBC connection URL |
| `DB_USERNAME` | Supabase username |
| `DB_PASSWORD` | Supabase password |
| `JWT_SECRET` | Min 32-character secret for signing tokens |
| `ANTHROPIC_API_KEY` | Claude API key — omit to use MockAiService |
| `ANTHROPIC_MODEL` | Override extraction/recommendation model |
| `ANTHROPIC_PROPOSAL_MODEL` | Override proposal generation model |
| `CORS_ALLOWED_ORIGINS` | Comma-separated list of allowed frontend URLs |
| `FILE_UPLOAD_DIR` | Directory for uploaded survey files |

---

## AI Behaviour Rules

- AI only uses robot data provided by the backend from PostgreSQL — it never invents specs or prices.
- If information is missing, the AI marks it as **"Needs confirmation"**.
- Every proposal includes a note that final confirmation requires RAASPAL verification and/or a site survey.

---

## Deployed Services

| Service | URL |
|---|---|
| Backend API | https://ai-robotrecommendationsystem-backend.onrender.com |
| Frontend | *(Vercel — deploy pending)* |
