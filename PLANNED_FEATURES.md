# Planned Features — ImageSense (NLPIC)

> Priority order based on impact × effort. Check off as implemented.

---

## ✅ Done

- [x] **Long-press to share** on search results grid + gallery grid + full-screen viewer
- [x] FileProvider configured for share intent
- [x] **Recent searches** — persist last 10 queries, show as vertical list under empty search bar
- [x] **UI Polish: Professional look** — replaced all emoji with Material Icons, "Offline" → "On-device" with shield icon, pinned search bar on Photos screen, removed bottom nav, 4-col grid with 2dp gaps, square tiles, sticky section headers with counts, vertical recents list, clean empty states

---

## 🎯 P0 — Core UX (High Impact, Low Effort)

| # | Feature | Description | Effort |
|---|---------|-------------|--------|
| 1 | **Smart query suggestions** | Show chips under search bar: "You have 200+ beach photos", "Try: 'sunset with dog'" — derived from indexed vectors | S |
| 2 | ~~**Recent searches**~~ | ✅ Done | — |
| 3 | **Priority indexing** | Index last 30 days first → searchable in minutes; background rest | S |
| 4 | **Hybrid ranking** | `score = 0.7*CLIP + 0.15*temporal + 0.1*diversity + 0.05*resolution` — better relevance | M |
| 5 | **Match reason badges** | Show "Matches 'lake' · July 2024 · 4.2 MB" on result tiles | S |

---

## 🎯 P1 — Gallery Intelligence (High Impact, Med Effort)

| # | Feature | Description | Effort |
|---|---------|-------------|--------|
| 6 | **Implicit collections carousel** | Top of Photos tab: "Screenshots (47) · Documents (12) · Last week (31) · Beach (18)" — auto-clustered from embeddings | M |
| 7 | **Find similar bottom sheet** | In viewer: horizontal scroll "Similar photos" + tabs: Visual / Same place / Same people / Same time | M |
| 8 | **On-device face clustering** | MediaStore + ML Kit Face Mesh → "People" collection (opt-in, fully offline) | L |
| 9 | **Memories / On this day** | "1 year ago today", "Same month last year" — date-based, no ML | XS |

---

## 🎯 P2 — Search Polish (Med Impact, Low Effort)

| # | Feature | Description | Effort |
|---|---------|-------------|--------|
| 10 | **Query builder chips** | "Photos of" + [person/dog/car] + [at beach/at night/last week] — guided composition | S |
| 11 | **Filter chips post-search** | 📅 Last month, 📍 Outdoors, 👥 With people, 🎨 Screenshots | S |
| 12 | **Search history screen** | Full list of past queries with result counts; tap to re-run | XS |
| 13 | **Voice search** | Mic button → on-device SpeechRecognizer (no network) | S |

---

## 🎯 P3 — Trust & Privacy (High Value, Low Effort)

| # | Feature | Description | Effort |
|---|---------|-------------|--------|
| 14 | **Privacy dashboard** | Settings screen: "0 bytes sent · 0 network calls · Model hash: sha256:abc..." | XS |
| 15 | **Verifiable build script** | `./gradlew printOfflineProof` → outputs manifest + deps + model hashes | XS |
| 16 | **Interactive onboarding** | Pre-bundled 5 sample images + vectors; search works *before* permission grant | S |

---

## 🎯 P4 — Performance & Scale (High Impact for Large Libraries)

| # | Feature | Description | Effort |
|---|---------|-------------|--------|
| 17 | **Incremental VectorBuffer load** | Load recent 500 first → searchable in 200ms → stream rest | M |
| 18 | **Background warm-up** | Low-priority coroutine on app start: warm up text encoder | XS |
| 19 | **Persist scan cursor** | Resume MediaStore scan from `_ID`/`DATE_MODIFIED` instead of full re-scan | S |
| 20 | **EmbeddingEngine as Service** | AIDL service keeps model loaded across restarts; enables home-screen widget | L |

---

## 🎯 P5 — Edge Cases & Polish

| # | Feature | Description | Effort |
|---|---------|-------------|--------|
| 21 | **Honest empty states** | "Indexing 234/1200 — search works on indexed photos" instead of blank | XS |
| 22 | **Multi-select share** | Checkbox mode in grids → "Share 3 photos" | S |
| 23 | **Accessibility pass** | TalkBack labels, dynamic text, reduced motion, high contrast toggle | M |
| 24 | **Settings screen** | Index on Wi-Fi only, pause below 20% battery, re-index now, clear history | S |

---

## 📋 Testing Gates (Must Stay Green)

| Test | Target |
|------|--------|
| Embedding parity (cosine vs Python ref) | ≥ 0.99 |
| Recall@3 on labeled eval set | 1.0 |
| Manifest privacy (no INTERNET) | PASS |
| Merged manifest privacy | PASS |
| Unit tests (tokenizer, preprocessor, diff, vector math) | PASS |

---

## 🔄 Next Up

1. **Priority indexing** (S) — index last 30 days first, searchable in minutes
2. **Smart suggestions** (S) — "You have 200+ beach photos" derived from indexed vectors
3. **Hybrid ranking** (M) — better relevance scoring

---

*Update this file as we progress. One feature at a time, verify on device, commit.*