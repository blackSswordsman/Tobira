# Local quest storage

Tobira uses Room 2.8.5 with Java annotation processing. The on-device SQLite database is
`tobira-quests.db`. MainActivity opens it asynchronously; it starts with an empty catalog.
No server, credentials, network calls, sample historical claims, or vehicle module are required.

## Data model

- `quest_places`: application-owned place ID, display name and coordinates.
- `place_external_keys`: provider + object type + external ID mapped to a place.
  Photon results retain OSM identity through route editing and Bundle serialization.
  OSM keys use provider `osm`, type `node` / `way` / `relation`, and a decimal ID string.
  A node and a way with the same numeric ID are different objects.
- `quests`: immutable revisions keyed by quest ID + version, with language, title,
  introduction and publication flag.
- `place_quests`: many-to-many links between places and quest revisions.
- `quest_steps`: ordered observations and challenges. Optional hints and condition JSON
  are content only: this storage layer does not evaluate vehicle conditions.
- `quest_progress`: one local-player checkpoint per quest revision.
- `completed_quest_steps`: completed step IDs scoped to the same quest revision.

Catalog revisions and player progress are separate. Publishing revision 2 does not overwrite
a revision 1 playthrough. Place lookup offers the latest published revision linked to that
place, while existing progress can continue using its original revision.

## Repository contract

`QuestRepository.getInstance(context)` uses the application context and a single worker.
One-shot reads and writes return `CompletableFuture`; observe failures instead of silently
ignoring them. Never call `get()` or `join()` on those futures from the UI thread.
Observable catalog/progress reads use lifecycle-aware `LiveData`.

- `importQuest(...)`: import one revision, its places, external keys, links and steps in
  one transaction. Existing revisions return false without changing data. Shared place
  keys can be reused for the same place; conflicting mappings are rejected.
- `findQuestsForPlace(searchResult)`: exact OSM identity lookup. Missing identity produces
  no automatic match; names and nearby coordinates are not treated as proof of identity.
- `startQuest(id, version)`: create a checkpoint or return the existing checkpoint.
- `savePhase(...)`: persist a phase checkpoint.
- `completeStep(...)`: idempotently record a completion and update the checkpoint time.
- `resetProgress(...)`: explicitly delete that revision's progress/completions, keeping
  the catalog intact.

Phases are PLANNED, TRAVELLING, VISITING, CHALLENGE and COMPLETED. Validating phase
transitions, parked-state requirements and task conditions belongs to the future quest engine.

## Schema changes and testing

Schema version 1 is exported to
`app/schemas/com.example.tobira.data.QuestDatabase/1.json`; keep it in version control.
Future schema changes must increment the Room version and provide a migration. There is
no destructive-migration fallback.

QuestDatabaseTest uses separate uniquely named on-disk test databases and removes only
those databases after each test. It verifies persistence across reopening, exact identity
matching, ordered steps, revision isolation, idempotent resume/completion, rollback and
foreign-key protection.

The left quest panel, content-authoring/import UI, quest engine and vehicle signal integration
are not implemented by this storage change. The production catalog is intentionally empty.
