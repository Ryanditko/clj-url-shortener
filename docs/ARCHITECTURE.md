# Diplomat Architecture

## 1. Overview

The **Diplomat Architecture** is the architectural pattern adopted by Nubank for most backend services, heavily inspired by Hexagonal Architecture (Ports & Adapters), but with its own conventions and terminology.  
The central idea is:

- Protect the domain (business rules) from any infrastructure details.  
- Isolate all external communication (HTTP, Kafka, DB, S3, etc.) in a dedicated layer called diplomats.  
- Clearly separate:
  - Internal models (models) – the source of truth for the domain.  
  - External formats (wire) – input and output contracts.  
  - Transformations (adapters) – pure conversions between internal models and external representations.  

In a simplified way, the standard flow is:

External → wire.in → adapters (inbound) → models → logic → controllers → adapters (outbound) → wire.out → diplomats → External.  

### 1.1 High‑level architecture diagram

The diagram (not shown here) summarizes the main layers and how they relate to each other:

- External World  
- Diplomat (entry/exit)  
- wire (in/out/datomic)  
- adapters  
- controllers  
- logic  
- models.  

---

## 2. Core Principles

### 2.1 Domain vs. External Boundary

Diplomat Architecture splits the system into two major groups:  

**Domain (Core Layer)**

- `models/` – internal data structures.  
- `logic/` – pure business rules.  
- `controllers/` – orchestrate use cases, combining logic and effects.  

**External / Boundary Layer**

- `wire/` – data contracts with the external world.  
- `adapters/` – conversions wire ↔ models.  
- `diplomat/` – HTTP server, HTTP client, Kafka producer/consumer, Datomic/DB etc.  

**Golden rule:** dependencies always point from the boundary to the core, never the other way around (diplomats depend on models/adapters, but models/logic know nothing about diplomats).  

### 2.1.1 Layer separation diagram

The diagram (not shown) illustrates:

- Outer ring: diplomats + wire + adapters.  
- Inner ring: controllers + logic + models.  
- All arrows pointing inward to the domain core.  

### 2.2 Purity and side effects

- `logic/` and `adapters/` must be pure functions: same input → same output, no I/O, no network access, no global state changes.  
- `controllers/` and `diplomats/` may perform side effects (HTTP calls, DB writes, Kafka publishing, etc.), but in an explicit and organized way.  

### 2.3 Strong contracts at the edges

- `wire.in` uses “loose” schemas (tolerant reader): accepts extra fields and is resilient to changes from external producers.  
- `wire.out` and `models` use strict schemas (conservative writer): always produce maps with exactly the expected fields.  

---

## 3. Data Flow

The main data flow focuses on inbound and outbound paths without going into internal responsibilities of each layer in detail.  

### 3.1 Inbound Flow (Input)

Example: a client performs `POST /api/client` to create a new client.

**External World**

- An app or another service calls the HTTP endpoint.

**`diplomat.http-server`**

- Receives the request (Pedestal, interceptors, etc.).  
- Applies an `adapt/coerce!` interceptor with the `wire.in` schema to transform JSON → validated Clojure map.  
- Forwards the validated map in `:data` to the handler.  

**`wire.in`**

- A namespace under `wire/in/...` defines the input schema, e.g. `new-client-request.clj`.  

**Inbound adapters**

- A function like `wire-new-client-http-request->internal-client` converts the external payload into a domain `Client` model (generates id, removes fields that don’t belong to the domain, etc.).  

**Models**

- The `Client` structure is defined in `models/client.clj` with a strict schema.  

**Logic (optional in simpler flows)**

- Pure business rules, e.g. additional validations, calculations etc.  

**Controllers**

- An `execute` (or similar) function receives the model and infrastructure abstractions (Kafka producer, Datomic, HTTP client, etc.) and orchestrates the use case: saving data, publishing events, etc.  

#### 3.1.1 Full HTTP endpoint sequence

For a `POST /api/client`, the step‑by‑step sequence is:

1. HTTP request reaches `diplomat.http-server`.  
2. Interceptors coerce payload into `wire.in` structure.  
3. Inbound adapter converts `wire.in` → `models.Client`.  
4. Logic (optional) performs pure domain rules.  
5. Controller executes the use case, using diplomats for I/O.  
6. Outbound adapter and `wire.out` build the response.  
7. Diplomat returns the HTTP response to the caller.  

### 3.2 Outbound Flow (Output)

Continuing the same example, the service:

- Returns HTTP 201 to the caller, and  
- Publishes a Kafka message “new client created”.  

Typical outbound path:

**Controllers**

- Receive the persisted client and call diplomat functions (e.g. `diplomat.producer/send-new-client-email`).  

**Outbound adapters**

- Convert models into `wire.out` structures, e.g. `wire-client->new-email-message` (subject, to, body, etc.).  

**`wire.out`**

- Namespaces like `email_message.clj`, `new_client_created.clj` define strict schemas used for HTTP responses or Kafka messages.  

**Outbound diplomats**

- `producer.clj` publishes messages to Kafka topics based on `wire.out` structures.  
- `http-client.clj` calls external services, applying request/response schemas.  
- `diplomat.datomic/` persists entities using the common Datomic layer.  

**External World**

- Kafka consumers, other HTTP services, or Datomic itself receive already standardized and validated data.  

---

## 4. Layer Descriptions

### 4.1 External World

Includes everything outside the service:

- Mobile/web apps.  
- Other HTTP services.  
- Message brokers (Kafka).  
- Databases, S3, third‑party services, etc.  

In Diplomat Architecture, the domain core never talks directly to these entities. All interaction goes through diplomats + wire + adapters.  

### 4.2 Diplomat (Inbound and Outbound Boundary)

The `diplomat/` folder concentrates all external integrations:  

**`diplomat/http_server.clj`**

- HTTP routes (Pedestal), interceptors, handler mapping.  
- Converts HTTP request → `wire.in` → calls adapters + controllers → converts response into `wire.out` and HTTP status.  

**`diplomat/http_client.clj`**

- “Bookmarks” and functions that call other services via HTTP.  
- Typically holds a `bookmarks-settings` map linking each bookmark to request/response schemas.  

**`diplomat/producer.clj`**

- Configures topics (settings) and functions that publish messages to Kafka. 

**`diplomat/consumer.clj`**

- Handlers for inbound Kafka messages that validate with `wire.in` and call adapters + controllers.

**`diplomat/datomic/` or `db/datomic/`**

- Encapsulates Datomic queries and transactions using adapters and `wire.datomic`.

These modules may perform side effects (I/O) but must not implement complex business rules – those belong in `logic/` and `controllers/`.

### 4.3 Wire (External Contracts)

The `wire/` folder defines all data contracts with the outside world:  

**`wire/in` – inbound payloads (HTTP/Kafka)**

- Use loose schemas (tolerant reader).  
- Often contain comments explaining why tolerance is needed.  

**`wire/out` – outbound payloads (HTTP responses, Kafka messages)**

- Use strict schemas, no extra fields.  

**`wire/datomic` – Datomic attribute schemas**

- Represent how data is persisted; also use strict schemas.

### 4.4 Adapters (Transformations)

The `adapters/` folder contains pure functions that:  

- Convert `wire.in` → models (inbound).  
- Convert models → `wire.out` / `wire.datomic` (outbound).  

Examples:

- `adapters.client/wire-new-client-http-request->internal-client`  
  - Receives validated HTTP payload (`wire.in`) and returns a domain `Client` with generated id and without fields like `:password-confirmation`.  
- `adapters.datomic.client/model->schema`  
  - Receives a `Client` and returns the map expected by Datomic (`:client/id`, `:client/email`, etc.).  

Rules:

- Cannot call HTTP, Kafka, DB or access components.  
- Should not contain business rules – only field mapping, normalization and small structural validations.  

### 4.5 Models (Internal Domain)

In `models/` live the business entities – the internal “language” of the system.  

Characteristics:

- Defined with strict schemas (e.g. Plumatic Schema skeletons).  
- Have no dependencies on diplomats, wire, adapters or infra components – they are the innermost layer.  

### 4.6 Logic (Pure Business Rules)

The `logic/` folder holds all complex business rules:  

- Value calculations.  
- Policy application.  
- Combination of multiple models.  
- Decisions based on internal state.  

Strict rules:

- Functions must not know about diplomats, adapters, wire or infra components.  
- Only depend on models (and other logic functions).  
- Must be fully testable with simple unit tests, without any external environment.  

### 4.7 Controllers (Orchestration – “Logic Sandwich”)

Controllers are the glue between pure domain and side effects:  

Typical responsibilities:

- Receive models and infra dependencies  
  - e.g. `execute` receives a `Client`, an `IProducer`, an `IDatomic`, etc.  
- Apply the “Logic Sandwich / Consume–Compute–Produce” pattern:  
  - Consume: fetch necessary data via diplomats.  
  - Compute: call pure logic functions to decide what to do.  
  - Produce: perform effects (save to DB, publish messages, call APIs).  
- Ensure a single consistent flow per use case  
  - Avoid spreading diplomat calls across many places; the controller is the central point for that business operation.  

---

## 5. Dependency Rules Between Layers

Internal documents often present a detailed table of allowed accesses between layers. The essence is:  

| Layer         | May depend on                          | Must not depend on / do                                      |
|---------------|----------------------------------------|--------------------------------------------------------------|  
| `models`      | (only schema libraries)                | wire, adapters, diplomats, controllers, logic                | 
| `logic`       | models, other logic functions          | diplomats, adapters, wire, components, I/O                   |
| `adapters`    | wire, models                           | logic, controllers, diplomats, I/O                           |
| `wire`        | (sometimes models for types)           | business logic                                               |
| `controllers` | logic, models, diplomats, components   | wire directly, inverted use of adapters                      |
| `diplomats`   | wire, adapters, models, controllers    | complex business logic                                       |

### 5.1 Visual dependency matrix

A visual matrix (not shown) is used by tooling to detect layer violations, e.g. logic calling an HTTP client directly.  

---

## 6. Important Patterns

### 6.1 Logic Sandwich (Consume–Compute–Produce)

Recommended controller pattern:  

- **Consume** – obtain required data via diplomats (HTTP, DB, Kafka).  
- **Compute** – apply pure logic functions to that data.  
- **Produce** – execute side effects: write to DB, emit events, send HTTP responses.  

### 6.2 Tolerant Reader & Conservative Writer

- `wire.in` uses loose schemas to accept payloads with extra fields, ensure compatibility with newer producers and support defensive parsing.  
- `wire.out` and `models` use strict schemas to guarantee stability of what the service produces (HTTP responses, Kafka events, persisted entities).  

### 6.3 Ports & Adapters in practice

Diplomat Architecture is essentially an implementation of Hexagonal Architecture:  

- Controllers act as ports (entry points for use cases).  
- Adapters + diplomats implement external adapters (HTTP, Kafka, DB).  
- Logic + models form the domain core.  

---

## 7. Best Practices and Anti‑patterns

### 7.1 Best practices

- Keep `logic/` fully pure, without `!` in function names and without component access.  
- Use schemas (e.g. `s/defn`) on public functions of adapters, logic, controllers and diplomats to ease validation and test data generation.  
- Put all data transformations between external world and domain in adapters, not in controllers or diplomats.  
- Test:
  - `logic/` and `adapters/` with unit tests.  
  - `diplomats/` + `controllers/` with integration tests.  

### 7.2 Common anti‑patterns

Internal documents list several architectural smells to avoid:  

- Business logic in adapters or diplomats  
  - Adapters should only transform data; rich conditional logic belongs in logic/controllers.  
- Logic calling HTTP, Kafka or DB  
  - Breaks domain isolation and makes tests harder.  
- Controllers using wire directly  
  - Controllers should speak in terms of models; wire is a boundary detail.  
- Diplomats manipulating models without using adapters when needed  
  - Especially with Datomic and complex external APIs.  

---

## 8. How to Apply in Practice (Step‑by‑Step)

### 8.1 Creating a new HTTP flow

1. Define `wire.in` for the endpoint under `wire/in` (input payload).  
2. Create/adjust required models in `models/`.  
3. Write inbound adapters `wire → models`.  
4. Implement business logic in `logic/`, always pure.  
5. Implement the controller:
   - Receive models + components (producer, Datomic, HTTP client, etc.).  
   - Call logic.  
   - Orchestrate side effects.  
6. Create outbound adapters `models → wire.out`.  
7. Define `wire.out` for the HTTP response.  
8. Configure the route in `diplomat.http-server`:
   - Interceptors (`adapt/coerce!`, `adapt/externalize!`).  
   - Handler calling adapters + controllers.  

### 8.2 Creating a new Kafka consumer

1. Define `wire.in` for the topic’s message.  
2. Add an entry in `diplomat/consumer.clj` with handler and schema.  
3. Handler:
   - Validates the message (`wire.in`).  
   - Calls adapters → models.  
   - Calls controllers/logic.  

### 8.3 Persisting in Datomic

1. Define `wire/datomic` with the attribute skeleton.  
2. Create adapter `models → wire.datomic`.  
3. Implement `diplomat.datomic.*` using the common Datomic API (e.g. `transact-entity!`, `new-or-existing-entity`, etc.).  

---

## 9. Conclusion

Diplomat Architecture provides:  

- **Consistency:** every service follows the same organization – when you open a new repository, you already know where to look for HTTP, DB, logic etc.  
- **Testability:** most business behavior lives in pure functions that are easy to unit test.  
- **Resilience:** changes in external contracts (HTTP payloads, Kafka messages, DB schemas) are isolated in wire + adapters.  
- **Architectural governance:** explicit dependency rules and automated tools help prevent architectural drift over time.  
