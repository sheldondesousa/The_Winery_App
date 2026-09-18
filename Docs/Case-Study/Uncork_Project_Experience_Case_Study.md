# Building Uncork with an AI coding assistant

*A product manager’s account of decisions, failures and learning*

Sheldon De Sousa | Experience paper | 10 to 17 September 2026

## Abstract

Uncork began as a personal wine and cheese recommendation app for a native Android device. Over eight calendar days, the work developed from a product brief and conversational interface into an application combining an on-device language model, a bundled wine-review database, local history and saved wines, and a web-search fallback. The central learning was that generating an interface and code did not remove the need for product judgment. It made the definition of behavior, evidence, failure and scope more consequential.

This case examines the experience of a senior product manager without formal computer-science or coding qualifications working with an AI coding assistant. The record shows rapid implementation alongside repeated visual revisions, a startup crash, an assistant change that exceeded the request, evolving rules for trustworthy wine information, and a correction for exhausted model context. The strongest outcome is an increasingly explicit product contract. The application remains an MVP in development, with important validation and completion work outstanding. [1–8]

## The product and the working relationship

The product intent was a private, conversational sommelier for personal use. The initial boundary was deliberately narrow: native Android in Kotlin, local storage, no cloud synchronization, no discovery feed, and no bottle imagery. Typography and conversation were expected to carry the experience. The intended device was a Pixel 10 Pro Fold. Wine discovery was primary; cheese pairing was an additional capability. [1]

The division of work placed product intent, visual judgment and acceptance with the product owner, while the assistant translated requests into documentation and implementation. This created a practical dependency: the product owner needed explanations clear enough to judge whether a change matched the request, even when the implementation itself was unfamiliar.

### How to read the chronology

Day 1 means the first recorded repository day, 10 September, rather than the first moment the idea was conceived. The account follows dated changes and retained project exchanges; a commit date identifies when a change entered the record, not necessarily when every related discussion occurred. References distinguish implemented code, requirements and historical validation reports. The lessons are interpretations of this case, not claims from a controlled study.

## Day 1  10 September 2026

### Turning a product intention into a working interaction

The first recorded commit was followed by the product requirements and design brief, a native Android splash screen, and the conversational interface. Later the same day, the project added first-launch model acquisition and on-device inference, followed by a wine detail screen and structured profile extraction. This sequence established the main journey from opening the app to asking a question and inspecting a recommendation. [1, 2]

The platform decision constrained implementation to native Kotlin rather than a web or cross-platform application. The model choice was the pinned Gemma 4 E2B LiteRT-LM artifact documented by the project. Its approximately 2.58 GB download made first launch a substantive product flow: token entry, download progress, interruption recovery, file verification and later offline readiness all mattered. Privacy therefore carried an onboarding and storage cost, rather than being only a positioning statement. [1, 2]

### Design became a sequence of concrete decisions

The visual direction used parchment, dark ink, restrained wine-red accents and hairline separation. The commit trail records repeated changes to navigation placement, labels, active states, message text size and response backgrounds. A fully highlighted navigation treatment was explicitly reverted before further refinements removed the active-tab background. These were design iterations; the record does not establish that each earlier version was a defect. [2]

The lesson is that a broad phrase such as “restrained editorial design” leaves many decisions unresolved. A working screen exposes questions about contrast, hierarchy, spacing and emphasis that a brief cannot settle by itself. At the same time, repeated small changes carry a coordination cost. A short visual acceptance checklist could have made the emerging rules easier to preserve across screens.

### Conversation and structured information needed separate contracts

The detail view initially carried the internal name Stage Show. Gemma was asked to produce natural conversational prose together with a hidden structured wine profile. That structure allowed the app to populate fields without treating a paragraph as a database record. This was an important architectural step: a readable answer and a usable application response are related, but they need different validation. [2]

The original prompt was provisional, and the dataset comparison was still a scaffold. The record supports an early implementation milestone, not a finished or independently verified sommelier. An attractive detail screen could display model-generated fields while the rules governing their reliability were still being worked out. That gap became a central theme over the following days.

## Day 2  11 September 2026

### Making recommendations persistent and understandable

The second day concentrated on History, wine details and saving. History began storing structured suggestions locally, grouping them by date and linking back to the corresponding detail view. The active conversation was preserved when switching tabs. Request labels were shortened into useful keywords so the list could explain why a recommendation had appeared without repeating the entire chat. [3]

This established two different user intentions: History records what the user encountered; Saved Wines records what the user deliberately chose to keep. Opening a recommendation should not silently save it. The Save control became toggleable, while deletion in History developed into an explicit selection mode with Clear, Cancel and a separate delete action. Clear All was ultimately hidden. [3]

### A runtime fault exposed a testing boundary

A change to hide structured JSON from chat was followed by the commit “Fix Android startup regex crash.” The correction replaced brace matching in two regular expressions with explicit character classes. The practical failure was severe: a formatting helper could prevent the application from starting. The correction was small, but its scope reached the whole app. [4]

This incident illustrates why successful code generation and desktop checks do not establish runtime compatibility on Android. The appropriate learning is to include a startup check on the intended runtime, especially after changing code initialized at launch. It is not evidence that all earlier tests were absent or ineffective; it identifies a class of failure those checks did not prevent.

### An assistant overreach created avoidable rework

The product owner requested that the page title “Saved Wines” become “Saved.” The assistant changed both the page title and bottom navigation. The user explicitly corrected the scope: only the page title had been requested. The assistant acknowledged the mistake and restored the bottom navigation to “Saved Wines,” while retaining “Saved” as the page heading. Code and documentation were corrected together. [3, 5]

This was an instruction-following fault rather than a design disagreement. The assistant treated a local wording change as a global consistency exercise without authorization. The lesson is to identify the exact surface named in a request and preserve intentional differences between labels. Build and lint checks can pass while a change still fails the product requirement.

The detail screen also moved from Stage Show to Wine Profile and then Attributes. Its Save control underwent several revisions to achieve a fixed footer with an oversized circular button. These changes show the value of visual inspection, but also the need to distinguish evolving design preferences from actual implementation faults. [3]

## Day 3  12 September 2026

### Closing the gap between merged work and the local project

A retained project exchange on 12 September checked whether GitHub and the local project were synchronized. The feature branch matched its remote counterpart, but local Master was reported as 62 commits behind GitHub’s Master after the History pull request had merged. The subsequent action switched to Master and fast-forwarded it to the merged version, preserving the feature branch. [5]

This was a workflow-state issue, not evidence of lost code. For a product manager, “the work is merged” can sound equivalent to “the application I am opening contains that work.” The exchange demonstrated why those are different states. A useful completion report should identify what was changed, where it was merged, and which version the local development environment is using.

## Day 4  13 September 2026

### Moving from a model answer to a sourced recommendation

The response-sourcing revision changed the role of the wine dataset. Earlier descriptions treated Kaggle as an optional comparison beside an independent Gemma profile. The revised requirements made the bundled database part of resolving local gaps and finding bottles with genuine critic scores, with web search filling unresolved cases. A growing local profile table was specified to retain reusable variety and region information. [6]

The strongest trust decision was to reserve critic rating for actual Kaggle points. The revised text also tightened bottle-specific claims: winery, vintage and provenance should remain unknown unless a separate source supplies them. This reduced the risk that a plausible model answer would be presented with the authority of a documented review. [6]

### An explicit performance tradeoff

The requirements chose to fetch web-sourced attributes when producing the bottle options, so opening a tile would not require another search and loading state. The document acknowledged the cost: the app could spend time and resources resolving details for options the user never opened. That is a useful example of a decision recorded with its rationale and a condition for reconsidering it. [6]

The interpretation of this day is that the project began treating evidence as part of the product architecture. A model could guide the interaction, while other sources supplied different kinds of facts. However, this date represents a requirements change; it should not be read as the date every part of that architecture became operational.

## Day 5  14 September 2026

### Defining geography and honest fallback behavior

The next group of requirements updates clarified the sommelier flow, country-aware storage, offline behavior, cached fallback and missing-value ranking. Country became a distinct field rather than an implicit part of region. The reusable profile identity expanded to country, province and variety. This made geographic specificity part of the data model and reduced ambiguity when grouping profiles. [6]

Changing a key also creates a migration responsibility. The current implementation preserves older rows whose country was never stored by assigning “Unknown” during migration rather than inferring a country. A later migration adds cached-option fields. This is a useful example of preserving existing information without manufacturing missing facts, although the migration code entered the recorded implementation later. [7]

### Offline first needed a definition of failure

The product could run a local model and still lack sufficient evidence for a particular request. The requirements therefore had to distinguish local capability from universal answerability. When Gemma, the dataset and cache could not support an answer and the device was offline, the app was expected to explain the limit rather than fabricate a bottle. Retry behavior also needed to reflect whether repeating the action could actually help. [6]

Caching became a functional part of that promise. A previously resolved web option could be reused without another live search. This improved the usefulness of the local store, but it also introduced questions about matching, completeness, replacement and how to preserve usable results if an additional search failed. The later specification continued to refine those cases.

### Missing information could not behave like a valid score

Kaggle ranking was specified as points descending and winery ascending. The null-handling clarification made the ordering of absent ratings explicit, including when results were processed outside SQLite. The product implication is straightforward: an unknown rating must not accidentally outrank a real score or be silently represented as zero. [6]

The learning from this day is that failure paths are part of the main experience. “Use a fallback” is too broad to implement reliably. A workable specification has to say when each source is consulted, what qualifies as usable, which preferences remain binding, and what the user sees when no source can answer. The increasing specificity was productive, but it also increased the need to reconcile overlapping acceptance criteria.

## Day 6  15 September 2026

### Representing evidence without overstating certainty

The sixth day refined web summaries, attribute value shapes, supplementary search caching and review aggregation. The data contract distinguished a model summary from actual critic-review text and an AI synthesis of web findings. These distinctions later became source-specific sections on the profile page. A short generated summary should not be mistaken for the original critic’s words. [6, 7]

Body, tannin and acidity were expanded beyond a small set of fixed categories. They could contain a single value, a range, an indication of thin evidence or Unknown. The requirements used a trailing asterisk internally for thin support, with “Insufficient data” as the intended user-facing explanation. The purpose was to preserve variation and limited support rather than force a false precision into the interface. [6]

The thresholds themselves are product rules, not proof of statistical calibration. For example, the requirement that a category contribute at least 20 percent of matched signal before appearing in a range describes how the aggregation should behave. It does not establish that the resulting description accurately represents every bottle in that geographic and varietal group.

### Aggregation needed to retain the underlying evidence

The review-aggregation update addressed duplicate wines and preservation of full review text. The resulting requirements called for labeled concatenation where multiple reviews had been combined, without shortening or discarding the underlying reviews. This mattered because a compact recommendation card should not determine how much evidence remains available in its detailed view. [6]

The general lesson is to separate the compactness of presentation from the completeness of storage. A summary may help users scan, but it should not silently replace the source information that justifies a recommendation. Similarly, cache behavior should preserve a usable result when an attempted supplementary search cannot improve it.

## Day 7  16 September 2026

### A boundary in the dated record

The available Git history has no commit dated 16 September. It therefore supports no separate implementation milestone for this day. Work appearing in the large 17 September change may have involved earlier preparation, but assigning individual activities to this date would require additional contemporaneous evidence.

The sequence nevertheless exposes a documentation lesson: several days of detailed requirements can later arrive as one large implementation change. A brief daily decision log would make the relationship between discussion, implementation and validation easier to reconstruct, particularly when requirements evolve faster than their code lands.

## Day 8  17 September 2026

### Integrating the recommendation pipeline

The recommendation-pipeline change brought together the bundled wine-review database, 4,119 seeded regional profiles, country-aware Room storage, recommendation orchestration, web search, source-preserving history and saved records, and expanded tests. The local Git record contains the merged pipeline pull request and the subsequent context-recovery pull request. These are substantial implementation milestones, although they do not establish final device-level quality. [7, 8]

The conversation contract became more explicit. Gemma leads three questions covering wine type, country and other attributes. An explicit lack of preference can close a question; ambiguity requires clarification. Compound answers should close every applicable question, and already supplied answers should not be requested again. Kotlin retains compact field coverage and coordinates data sources. When coverage completes, the current main path queries Kaggle immediately rather than asking for a separate confirmation first. [7]

### Search relevance depended on preserving intent

The implementation includes tests and rules for retaining clarification preferences, prioritizing user terms, excluding previously displayed options and handling wine-type mismatches. Rosé matching is constrained to wine name and variety so a tasting-note reference to rose does not become evidence that a bottle is rosé. These details show why general keyword matching is insufficient for a conversational recommendation product. [7]

Attribute filters also became meaningful constraints. Under the current requirements, a miss with body, tannin or acidity filtering should explain the likely limiting attribute rather than silently broaden to another source. This protects the user’s stated choice, but the explanation should remain appropriately cautious: a database miss is not proof that no suitable wine exists anywhere.

### The model ran out of working context

The latest correction handles the LiteRT-LM error “Prefill input length exceeds available state entries.” The responder identifies this specific failure, including wrapped causes, closes the exhausted conversation, creates a new one and retries the current turn once. The prompt also carries the pending question and compact coverage state. Unrelated errors are not treated as this recovery case. [8]

The lesson is that conversational continuity should not depend entirely on the model retaining the full transcript. Essential preferences need an application-owned representation that can support recovery. The code and new checks demonstrate the intended correction; they do not yet establish recovery reliability over repeated long sessions on the physical device. A one-time retry is a bounded response to a known fault, not a claim of unlimited conversation capacity.

## What the experience taught us

### Product correctness needs its own acceptance checks

The Saved title incident demonstrated that technically valid code can implement the wrong scope. The assistant’s error was not an inability to change a label; it was changing an additional surface the user had not requested. Future requests and completion notes should name the affected screen or control precisely. A product acceptance check must compare the outcome with the instruction, alongside the engineering checks that assess whether the code builds. [5]

### Trust is implemented through field and source rules

Restricting critic scores to real review data, preserving source-specific narratives, using Unknown for missing facts and marking thin evidence all made trust more concrete. These are stronger mechanisms than asking the model to sound careful. They also require consistency across the whole journey: a web-derived option must not lose its origin when saved or later reopened from History. [6, 7]

### A smaller brief is not always a simpler product

The initial promise of a conversational sommelier sounded compact, but the experience depended on downloads, session state, structured output, persistence, matching, ranking, caching and failure behavior. AI assistance accelerated construction while exposing these dependencies. The case supports the value of explicit contracts and staged validation; it does not provide enough evidence to quantify time saved against conventional development.

### Iteration and avoidable rework should be separated

Revising the empty state, navigation emphasis and Save-button layout reflects exploration. The navigation-label overreach reflects a scope fault. The startup crash reflects runtime incompatibility. Context exhaustion reflects a resource limitation requiring recovery. Treating all four as “bugs” would obscure their different causes and the different interventions each needs.

### Documentation must describe the current behavior

The current record contains visible drift. The README still describes older comparison behavior and lists some capabilities as open that the later PRD and code describe as implemented. Within the PRD, early statements restrict web summaries to post-miss fallback, while later criteria permit a broader web search after Kaggle results. Another cache criterion contains both a stop-before-web statement and later supplementary-search behavior. These should be reconciled before treating the document as a single acceptance authority. [1, 7]

### Validation must preserve the development environment

The project’s device-safety rule forbids test commands that can reinstall or clear the application on the physical device. With a large downloaded model and private local data, verification can otherwise destroy the state needed for continued testing. Instrumented tests belong on an emulator or managed virtual device; if unavailable, compiling their APK provides a narrower check. Authorized manual device installation uses a data-preserving update. The rule documents a safeguard, not proof that data loss occurred. [9]

## Where the project stands today

### A working foundation with explicit limits

By 17 September, the recorded implementation contains the native shell, local model acquisition and inference, conversational onboarding, History, saving and removal, the bundled review database and profile seed, source-aware recommendation plumbing, a web integration and bounded model-context recovery. Historical project records report computer-side builds, lint, unit tests, test-APK compilation and asset checks. Those are distinct from running every instrumented test or validating every live response on the target device. [5, 7, 8]

The current PRD still identifies open work: production-suitable Brave credential delivery, complete web error and offline behavior, cloud routing, separate winery verification, full event logging, the final product-owned prompt, final font bundling, rating and note editing, cheese-pairing quality, removal of the old comparison scaffold and final responsive visual validation. The API key and model-response quality also require appropriate live verification. These gaps prevent a defensible claim of a finished release. [7]

### Recommended next steps

First, reconcile the PRD, README and actual source-selection flow into one current contract. Second, validate a compact set of representative journeys: first launch, no preference, compound answers, corrected preferences, no local match, offline cache reuse, live-search failure, and a long conversation that exhausts context. Third, finish the remaining user-facing features before expanding scope. Record the expected outcome, observed outcome and evidence for each journey.

For continued collaboration, a short decision log should capture the request, rationale, affected surface, accepted tradeoff and verification result. This would make future changes easier to review and the next experience paper less dependent on reconstructing a large conversation and commit trail.

### Conclusion

The defining experience was learning to turn a product intention into precise behavior while an AI assistant could implement changes quickly. The product owner’s contribution remained central: deciding what mattered, noticing when an instruction had been exceeded, shaping the interaction and insisting on distinctions between plausible output and supported information. The strongest next step is to consolidate and validate that work so the reliability of the experience catches up with the breadth of the implementation.

## References to the project record

[1] README.md, Docs/PRD.md and Docs/Design-Brief.md; initial requirements commit 9c7944d; current checkout 16b6f9d. Read 17 September 2026.

[2] Day 1 implementation: 956bb56, 1442e61, 1dc6902, 7ec5613, bdfc1f1, 944e2b4 and 279ec43. Git dates: 10 September 2026.

[3] History and design evolution: 2fd3085, a07f7fb, 78444ac, d83e518, fb59bde, aae0fab, 1db9848, 406fdf4 and c8071be. Git dates: 11 September 2026.

[4] Startup parser correction: 90a97e6, changes in GemmaConversationResponder.kt, 11 September 2026.

[5] Retained task “Read Docs markdown files,” 11–12 September exchanges on Saved wording, PR 1 and local synchronization. Label correction: c8071be.

[6] Requirements evolution: d2337b2, 3fd762c, 26592b6, 45f8bb2, 32a0748, 2628c2d, c9826a1, a4fa58f, f64c3a0 and 9c5e1e2; 13–15 September 2026.

[7] Pipeline implementation 9235634 and merge 18cebb5; current PRD sections 4, 6, 7 and implementation status; recommendation, repository, migration and test code. 17 September 2026.

[8] Context-recovery correction adc9a85 and merge 16b6f9d; GemmaConversationResponder, WineFieldCoverage and associated test changes. 17 September 2026.

[9] AGENTS.md device-safety instructions in the project checkout, read 17 September 2026.
