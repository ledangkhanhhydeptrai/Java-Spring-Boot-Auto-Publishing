# Compilation fixes (2026-11-06)

- Added explicit Jackson 2 databind dependency and an ObjectMapper bean for FanpageQueueProcessor.
- Imported BCryptPasswordEncoder from org.springframework.security.crypto.bcrypt.
- Added explicit two-UUID constructor to WorkspaceMemberId.

Run `mvn clean verify` and then `mvn spring-boot:run`. This archive has not been compiled in the generation environment (JDK 25/Maven unavailable). No guarantee that further compilation or runtime errors are absent.
