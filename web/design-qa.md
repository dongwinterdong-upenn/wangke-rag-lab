# Mobile design QA — Option 3

- Source visual truth: `L:/files/yugu-voice-assessment/.codex-mobile-design/selected-option-3.png`
- Implementation screenshot: `L:/files/yugu-voice-assessment/.codex-mobile-design/qa-student-option3-pass2.png`
- Combined comparison: `L:/files/yugu-voice-assessment/.codex-mobile-design/qa-student-side-by-side-pass2.png`
- Supporting portal evidence:
  - `L:/files/yugu-voice-assessment/.codex-mobile-design/qa-agent-option3-final.png`
  - `L:/files/yugu-voice-assessment/.codex-mobile-design/qa-admin-option3-final.png`
- Viewport: 390 × 844 CSS px, light theme, authenticated test accounts.
- Source pixels: 853 × 1844. It was downsampled with high-quality bicubic interpolation to 390 × 844 before comparison.
- Implementation pixels: 390 × 844. Browser capture was already CSS-pixel normalized, so no second density transform was applied.
- State: the source mock shows a returning learner with a saved last lesson; the local preview used a fresh origin with no saved last-lesson record, so it correctly shows the first-use fallback “开始学习”. This is an intentional state difference, not layout drift. The existing last-lesson branch remains wired to the same focus surface.

## Full-view comparison evidence

The pass-2 side-by-side comparison confirms the same composition: compact brand header, serif “今天继续” heading, one dominant learning surface, one red primary action in the thumb zone, two equal quick actions, a compact course row, and a persistent four-item bottom navigation. The implementation preserves the source proportions while using real application data and the existing icon family.

No separate focused crop was needed: at the normalized 780 × 844 combined view, the header, primary surface, progress, quick-action icons, course row, and bottom navigation are all readable at native comparison scale. Agent/admin evidence was reviewed separately because those portals intentionally adapt the same interaction system rather than reproduce the student screen.

## Required fidelity surfaces

- Fonts and typography: passed. The existing self-hosted Noto Serif SC subset remains the display face; sans-serif body and JetBrains Mono numeric treatment match the source system. Hierarchy, weights, line heights, and truncation are stable at 390 px.
- Spacing and layout rhythm: passed. Page margins, section gaps, focus-card padding, 44 px minimum controls, 64 px bottom navigation, and 108 px course row preserve the selected hierarchy without overlap.
- Colors and visual tokens: passed. Warm paper, white surfaces, deep ink, Dragon red primary action, and orange live progress use the existing design tokens. No generic blue, gradient, or unapproved shadow system was introduced.
- Image quality and asset fidelity: passed. The screen contains no raster hero or editorial imagery. Existing code-native brand mark, course cover treatment, and project icon family are reused; no placeholder image, emoji, handcrafted SVG, or generated decoration was introduced.
- Copy and content: passed. Student copy changes with state (“开始学习” without history, “接着看” with history). Agent and admin copy remains factual and role-correct.

## Comparison history

### Pass 1 — blocked

- [P2] The implementation header retained the small English `DRAGONAI` line while the selected source used a tighter one-line mobile lockup.
- [P2] Quick-action icons were visually too weak and the course row was shorter than the selected source, reducing thumb-zone emphasis.

Fixes made:

- Hid the English wordmark only on mobile and increased the Chinese mobile brand size.
- Added the selected circular icon treatment using the existing project icons.
- Increased the course-row touch surface to 108 px.
- Scoped live progress to the orange motion token while preserving the red primary action.

### Pass 2 — passed

Post-fix evidence: `qa-student-side-by-side-pass2.png`. No actionable P0/P1/P2 visual or interaction differences remain. The remaining source/implementation copy difference is the expected saved-history state described above.

## Interaction and responsive verification

- Student: login, start-course CTA, course-detail navigation, bottom tabs.
- Agent: login, thumb-first stock action, horizontal KPI rail, bottom common navigation, full drawer navigation, profile route.
- Admin: login, compact pending rows, two-column metric layout, refund route from the bottom common navigation.
- 390 × 844: no horizontal overflow on student or admin; agent KPI overflow is intentionally contained inside the horizontal rail.
- 430 × 932: admin layout remains within viewport.
- Console: no warning/error entries in student, agent, or admin preview tabs.

## Follow-up polish

- [P3] A real-device Safari pass can still refine momentum scrolling and safe-area spacing on notched iPhones.

final result: passed
