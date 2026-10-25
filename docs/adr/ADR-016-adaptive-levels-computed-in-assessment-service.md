# ADR-016: Adaptive levels per topic, computed in assessment-service

- **Status:** Accepted (Phase 9 Step B, 26 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

The client's Adaptive Exam System requirements ask every exam type (Practice, Mock, Adaptive) to adjust difficulty
per topic from the student's accuracy, recent answers and topic performance. They also ask for repeated mistakes to be
prioritised, and for a per-student profile: accuracy and level per topic, weak topics, and progress over sessions.
The Adaptive exam must react within a session: the next question depends on the answer just given.

The original plan (Phase 3) had learning-service build the profile from `AttemptCompleted` events. An event feed
arrives seconds later, so it can't steer the very next question.

## Decision

1. **Rules** (pure functions, `TopicLevels`):
   - Each student starts each topic at level 2 (Medium).
   - The level moves one step only when at least 5 answers have been given since the last change, judged on the
     last 8 of them: up at 80% correct or more, down below 40%. The count then starts again.
   - Status, from the last 8 answers:
     - strong: 80% correct or more
     - needs work: below 50%
     - developing: anything in between
     - just started: fewer than 5 answers
2. **Difficulty mixes** (Easy / Medium / Hard):
   - level 1: 70 / 30 / 0
   - level 2: 25 / 50 / 25
   - level 3: 0 / 40 / 60

   When a difficulty runs out, the nearest one is used.
3. **Choosing a question** (`QuestionPicker.next`):
   - **Topic.** Topics take turns. In an Adaptive exam, topics that need work, or have two or more recent mistakes,
     get twice the share.
   - **Difficulty.** Drawn from the topic's level mix.
   - **Question.** In Practice and Adaptive exams, a question answered wrong last time comes back first, after a
     day, but makes up at most about a quarter of a session. Mock exams never repeat mistakes on purpose. After that,
     questions not seen in 7 days come before ones seen recently.
4. **When questions are chosen.**
   - Practice and Mock exams fix their whole question set at start, using the levels at that moment.
   - An Adaptive exam plans its length at start, then stores one question at a time. Each next question is chosen
     inside the answer's transaction, with levels that already include that answer. Every question given is still
     stored on the attempt with its answer key (ADR-008 holds).
5. **Where it runs.** The profile is computed in assessment-service on demand from its own marked answers. It is not
   stored, so it never goes stale and needs no events. Only marked answers count: checked Practice and Adaptive
   answers, and Mock answers after the paper is finished. At most the latest 5000 answers per exam are replayed.
6. **API.** `GET /api/progress/exams` and `GET /api/progress/exams/{examId}` serve the "My progress" page. Each
   question records the level it was chosen for (`attempt_question.target_level`).

## Consequences

- Behaviour is deterministic given the answers, and easy to test and explain to students. The rules are shown on the
  progress page.
- The thresholds are code constants. If the client wants to tune them per exam, they become exam settings later.
- learning-service keeps its place for later features that suit events: recommendations across exams, AI wording and
  analytics. It can consume `AttemptCompleted` from the outbox without changing these rules.

## Alternatives considered

- **learning-service builds the profile from events.** Rejected for now: too late to choose the next question, and it
  duplicates data assessment-service already owns.
- **Item response theory (IRT) or Elo ratings per question.** Rejected for now: needs calibrated question data, and it
  is harder to explain. It could replace `TopicLevels` later behind the same picker.
