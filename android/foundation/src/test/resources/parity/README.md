# Task 5 parity fixtures

These language-neutral JSON fixtures derive from the pinned iOS commit
`e10aa3c0101cc99511ad667029a32a0bcdc998cf`:

- `domain-name-cases.json`: `Sources/LavaSecKit/DomainName.swift`,
  `Tests/LavaSecCoreTests/DomainRuleSetTests.swift`, and executable pinned
  Foundation `URL(...).host` probes for non-transitional UTS46, Unicode 16 age,
  bidi/context, ignored mappings, and trim-order boundaries.
- `filter-precedence-cases.json`: `Sources/LavaSecKit/FilterSnapshot.swift` and
  `Tests/LavaSecCoreTests/FilterSnapshotTests.swift`.
- `dns-malformed-cases.json`: `Sources/LavaSecDNS/DNSMessage.swift` and the
  adversarial parsing cases in `Tests/LavaSecCoreTests/DNSMessageTests.swift`.
- `dns-response-cases.json`: `Sources/LavaSecDNS/DNSMessage.swift`,
  `Sources/LavaSecDNS/DNSMessageTraits.swift`, `DNSMessageTests.swift`, and
  `DNSMessageTraitsTests.swift`.

Android bounds DNS input to 4096 bytes. Blocked-response synthesis preserves the
identifier, question, and recursion-desired flag, returns NOERROR with TTL 1,
and answers only A (`0.0.0.0`) and AAAA (`::`) questions. Malformed formable
queries instead receive SERVFAIL and never become filter-block evidence.

Domain fixtures execute through ICU4J 77.1 with non-transitional UTS46, STD3,
bidi, and ContextJ checks. ContextO is intentionally not enabled because pinned
Foundation accepts the covered middle-dot labels. This is the Slice 3
domain/filter checkpoint.
