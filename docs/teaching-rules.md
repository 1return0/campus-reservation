# CampusReserve teaching rules

This document must be read together with `learning-cursor.md` and
`project-layout.md` before every new CampusReserve lesson. It prevents the
course from becoming a list of loosely related concepts.

## Required format for every lesson

1. State the exact cursor position and what business question the lesson
   answers.
2. Start from one concrete request or event and walk the complete real call
   chain in execution order. Name every relevant controller, service, mapper,
   scheduler, publisher or consumer along the way.
3. Open and explain the core code on that chain. For each important method,
   explain its input, output, ownership, branch conditions and why it belongs
   in that layer. Do not replace code explanation with a diagram alone.
4. Separate the material into:
   - **Must remember**: contracts, boundaries and code patterns expected in
     interviews or later lessons.
   - **Need understand**: design reasoning that can be revisited from code.
   - **Can look up**: framework syntax or incidental implementation detail.
5. Give clickable links to the exact files being discussed. Avoid unrelated
   files and avoid inventing a call chain that the repository does not use.
6. End with three questions that test the actual lesson. Grade the answers
   before moving the cursor. A lesson may not silently skip to a later day.
   Questions may test only concepts, code and business facts already explained
   in that lesson. If a question needs a later schema or mechanism, first
   teach that material or defer the question to its scheduled lesson.

## Accuracy and pacing rules

- Distinguish a unit test, a local integration smoke and an unverified
  production claim.
- If new code was added before a lesson, teach that code at the lesson where
  it belongs in the chain; do not merely announce that it exists.
- Preserve the fixed sequence in `learning-cursor.md`. If the sequence must
  change, update that file first and explain the reason.
- Frontend work may be summarized after backend learning; backend lessons must
  remain focused on Java, database, messaging, security and operations.
